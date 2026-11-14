//! Lab only (ghostlane lab/olc-alps): a TLS 1.3 server that accepts ALPS
//! (application_settings) for h2 as Google's front ends do, on BoringSSL,
//! and logs what each client sent, one JSON line per connection: the raw
//! ClientHello record, the ALPS codepoint and protocols it offered, the
//! ALPN the server picked, whether ALPS was negotiated and the client's own
//! ALPS bytes (its EncryptedExtensions), and the first bytes of its
//! application data (the h2 preface). It answers enough HTTP/2 and
//! HTTP/1.1 for a browser's page load to finish.
//!
//! Usage: alps-lab-server --log FILE [--port N]... [--codepoint auto|new|old]
//!        [--settings-hex HEX]
//!
//! Memory: a ClientHello is read whole and kept at most MAX_HELLO bytes; a
//! connection's application data is buffered at most MAX_APP bytes, and the
//! first MAX_APP_LOG of them are logged; at most MAX_CONNECTIONS
//! connections are served at once (others are closed unread).

use std::fs::OpenOptions;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use boring::asn1::Asn1Time;
use boring::bn::{BigNum, MsbOption};
use boring::ec::{EcGroup, EcKey};
use boring::hash::MessageDigest;
use boring::nid::Nid;
use boring::pkey::PKey;
use boring::ssl::{
    select_next_proto, AlpnError, Ssl, SslContext, SslContextBuilder, SslMethod, SslRef,
    SslVersion,
};
use boring::x509::{X509Builder, X509NameBuilder, X509};
use foreign_types::ForeignTypeRef;

const MAX_HELLO: usize = 64 * 1024;
const MAX_APP: usize = 64 * 1024;
const MAX_APP_LOG: usize = 4096;
const MAX_HTTP1_HEAD: usize = 8 * 1024;
const MAX_CONNECTIONS: usize = 64;
const MAX_H2_REQUESTS: usize = 8;
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const H2_IDLE: Duration = Duration::from_secs(3);

const ALPS_OLD: u16 = 0x4469;
const ALPS_NEW: u16 = 0x44cd;
const ALPN_SERVER: &[u8] = b"\x02h2\x08http/1.1";
const H2_PREFACE: &[u8] = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

#[derive(Clone, Copy, PartialEq)]
enum Codepoint {
    /// The codepoint the client offered (the new one if it offered none).
    Auto,
    New,
    Old,
}

struct Config {
    ports: Vec<u16>,
    log: String,
    codepoint: Codepoint,
    /// The server's ALPS for h2: an HTTP/2 SETTINGS frame, as Chrome's
    /// session reads a server's ALPS (frames).
    settings: Vec<u8>,
}

fn usage() -> ! {
    eprintln!("usage: alps-lab-server --log FILE [--port N]... [--codepoint auto|new|old] [--settings-hex HEX]");
    std::process::exit(2)
}

fn parse_args() -> Config {
    let mut cfg = Config {
        ports: Vec::new(),
        log: String::new(),
        codepoint: Codepoint::Auto,
        // SETTINGS, stream 0: MAX_CONCURRENT_STREAMS (3) = 100.
        settings: vec![0, 0, 6, 4, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 100],
    };
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        let mut val = || args.next().unwrap_or_else(|| usage());
        match a.as_str() {
            "--port" => cfg.ports.push(val().parse().unwrap_or_else(|_| usage())),
            "--log" => cfg.log = val(),
            "--codepoint" => {
                cfg.codepoint = match val().as_str() {
                    "auto" => Codepoint::Auto,
                    "new" => Codepoint::New,
                    "old" => Codepoint::Old,
                    _ => usage(),
                }
            }
            "--settings-hex" => cfg.settings = unhex(&val()).unwrap_or_else(|| usage()),
            _ => usage(),
        }
    }
    if cfg.ports.is_empty() || cfg.log.is_empty() {
        usage();
    }
    cfg
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn unhex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}

fn json_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

struct Log(Mutex<std::fs::File>);

impl Log {
    fn line(&self, fields: &[(&str, String)]) {
        let body: Vec<String> = fields
            .iter()
            .map(|(k, v)| format!("{}:{}", json_str(k), v))
            .collect();
        let line = format!("{{{}}}\n", body.join(","));
        let mut f = self.0.lock().unwrap_or_else(|e| e.into_inner());
        let _ = f.write_all(line.as_bytes());
        let _ = f.flush();
    }
}

fn certificate() -> Result<(X509, PKey<boring::pkey::Private>), boring::error::ErrorStack> {
    let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1)?;
    let key = PKey::from_ec_key(EcKey::generate(&group)?)?;
    let mut b = X509Builder::new()?;
    b.set_version(2)?;
    let mut serial = BigNum::new()?;
    serial.rand(64, MsbOption::MAYBE_ZERO, false)?;
    b.set_serial_number(serial.to_asn1_integer()?.as_ref())?;
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    let at = |secs: u64| libc::time_t::try_from(secs).unwrap_or(libc::time_t::MAX);
    b.set_not_before(&Asn1Time::from_unix(at(now.saturating_sub(86_400)))?)?;
    b.set_not_after(&Asn1Time::from_unix(at(now + 7 * 86_400))?)?;
    let mut name = X509NameBuilder::new()?;
    name.append_entry_by_text("CN", "alps.test")?;
    let name = name.build();
    b.set_subject_name(&name)?;
    b.set_issuer_name(&name)?;
    b.set_pubkey(&key)?;
    b.sign(&key, MessageDigest::sha256())?;
    Ok((b.build(), key))
}

fn context(cert: &X509, key: &PKey<boring::pkey::Private>) -> Result<SslContext, boring::error::ErrorStack> {
    let mut ctx = SslContextBuilder::new(SslMethod::tls())?;
    ctx.set_min_proto_version(Some(SslVersion::TLS1_3))?;
    ctx.set_max_proto_version(Some(SslVersion::TLS1_3))?;
    ctx.set_certificate(cert)?;
    ctx.set_private_key(key)?;
    // The server's preference: h2, then http/1.1.
    ctx.set_alpn_select_callback(|_, client| {
        select_next_proto(ALPN_SERVER, client).ok_or(AlpnError::NOACK)
    });
    Ok(ctx.build())
}

/// A stream that first replays the bytes already read from it.
struct Replay {
    head: Vec<u8>,
    at: usize,
    inner: TcpStream,
}

impl Read for Replay {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.at < self.head.len() {
            let n = buf.len().min(self.head.len() - self.at);
            buf[..n].copy_from_slice(&self.head[self.at..self.at + n]);
            self.at += n;
            if self.at == self.head.len() {
                self.head = Vec::new();
                self.at = 0;
            }
            return Ok(n);
        }
        self.inner.read(buf)
    }
}

impl Write for Replay {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.inner.write(buf)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

/// Reads TLS records until the first handshake message (the ClientHello)
/// is whole. Returns the raw records and the message.
fn read_client_hello(tcp: &mut TcpStream) -> io::Result<(Vec<u8>, Vec<u8>)> {
    let mut raw = Vec::new();
    let mut msg = Vec::new();
    loop {
        let mut header = [0u8; 5];
        tcp.read_exact(&mut header)?;
        if header[0] != 22 {
            return Err(io::Error::other(format!("record type {} before the ClientHello", header[0])));
        }
        let len = usize::from(u16::from_be_bytes([header[3], header[4]]));
        if raw.len() + 5 + len > MAX_HELLO {
            return Err(io::Error::other("ClientHello over the lab's bound"));
        }
        let mut body = vec![0u8; len];
        tcp.read_exact(&mut body)?;
        raw.extend_from_slice(&header);
        raw.extend_from_slice(&body);
        msg.extend_from_slice(&body);
        if msg.len() >= 4 {
            let want = 4 + (usize::from(msg[1]) << 16 | usize::from(msg[2]) << 8 | usize::from(msg[3]));
            if msg.len() >= want {
                msg.truncate(want);
                return Ok((raw, msg));
            }
        }
    }
}

struct Offered {
    alpn: Vec<Vec<u8>>,
    alps: Option<(u16, Vec<Vec<u8>>)>,
}

fn names(list: &[u8]) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    if list.len() < 2 {
        return out;
    }
    let end = (2 + usize::from(u16::from_be_bytes([list[0], list[1]]))).min(list.len());
    let mut i = 2;
    while i < end {
        let n = usize::from(list[i]);
        if i + 1 + n > end {
            break;
        }
        out.push(list[i + 1..i + 1 + n].to_vec());
        i += 1 + n;
    }
    out
}

/// The ALPN list and the ALPS extension of a ClientHello message.
fn offered(msg: &[u8]) -> Offered {
    let mut out = Offered { alpn: Vec::new(), alps: None };
    let get = |at: usize, n: usize| msg.get(at..at + n);
    let mut at = 4 + 2 + 32;
    let Some(&[sid]) = get(at, 1) else { return out };
    at += 1 + usize::from(sid);
    let Some(&[a, b]) = get(at, 2) else { return out };
    at += 2 + usize::from(u16::from_be_bytes([a, b]));
    let Some(&[c]) = get(at, 1) else { return out };
    at += 1 + usize::from(c);
    let Some(&[a, b]) = get(at, 2) else { return out };
    let end = (at + 2 + usize::from(u16::from_be_bytes([a, b]))).min(msg.len());
    at += 2;
    while at + 4 <= end {
        let kind = u16::from_be_bytes([msg[at], msg[at + 1]]);
        let len = usize::from(u16::from_be_bytes([msg[at + 2], msg[at + 3]]));
        let Some(body) = msg.get(at + 4..at + 4 + len) else { break };
        match kind {
            0x0010 => out.alpn = names(body),
            ALPS_OLD | ALPS_NEW => out.alps = Some((kind, names(body))),
            _ => {}
        }
        at += 4 + len;
    }
    out
}

fn names_json(list: &[Vec<u8>]) -> String {
    let items: Vec<String> = list
        .iter()
        .map(|p| json_str(&String::from_utf8_lossy(p)))
        .collect();
    format!("[{}]", items.join(","))
}

fn ssl_ptr(ssl: &SslRef) -> *mut boring_sys::SSL {
    ssl.as_ptr()
}

/// What ALPS gave: (negotiated, the peer's bytes).
fn negotiated_alps(ssl: &SslRef) -> (bool, Vec<u8>) {
    // SAFETY: the SSL is live for the call; the peer's buffer is owned by
    // it and copied out before it can change.
    unsafe {
        let p = ssl_ptr(ssl);
        let has = boring_sys::SSL_has_application_settings(p) == 1;
        let mut data: *const u8 = std::ptr::null();
        let mut len: usize = 0;
        boring_sys::SSL_get0_peer_application_settings(p, &mut data, &mut len);
        let bytes = if data.is_null() || len == 0 {
            Vec::new()
        } else {
            std::slice::from_raw_parts(data, len).to_vec()
        };
        (has, bytes)
    }
}

fn frame(kind: u8, flags: u8, stream: u32, payload: &[u8]) -> Vec<u8> {
    let len = payload.len();
    let mut f = vec![(len >> 16) as u8, (len >> 8) as u8, len as u8, kind, flags];
    f.extend_from_slice(&(stream & 0x7fff_ffff).to_be_bytes());
    f.extend_from_slice(payload);
    f
}

/// Enough HTTP/2 for a page load: the server's SETTINGS, ACKs, and a
/// `200` with `ok` to every request. Returns the client's first bytes and
/// the number of requests answered.
fn serve_h2<S: Read + Write>(s: &mut S) -> (Vec<u8>, usize) {
    let mut seen = Vec::new();
    let mut buf: Vec<u8> = Vec::new();
    let mut answered = 0;
    let mut preface_ok = false;
    if s.write_all(&frame(4, 0, 0, &[])).is_err() {
        return (seen, 0);
    }
    let mut chunk = [0u8; 4096];
    'outer: loop {
        let n = match s.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => n,
        };
        if seen.len() < MAX_APP_LOG {
            let take = n.min(MAX_APP_LOG - seen.len());
            seen.extend_from_slice(&chunk[..take]);
        }
        if buf.len() + n > MAX_APP {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
        if !preface_ok {
            if buf.len() < H2_PREFACE.len() {
                continue;
            }
            if &buf[..H2_PREFACE.len()] != H2_PREFACE {
                break;
            }
            buf.drain(..H2_PREFACE.len());
            preface_ok = true;
        }
        while buf.len() >= 9 {
            let len = usize::from(buf[0]) << 16 | usize::from(buf[1]) << 8 | usize::from(buf[2]);
            if buf.len() < 9 + len {
                break;
            }
            let (kind, flags) = (buf[3], buf[4]);
            let stream = u32::from_be_bytes([buf[5], buf[6], buf[7], buf[8]]) & 0x7fff_ffff;
            let payload = buf[9..9 + len].to_vec();
            buf.drain(..9 + len);
            let reply = match kind {
                4 if flags & 1 == 0 => frame(4, 1, 0, &[]),
                6 if flags & 1 == 0 => frame(6, 1, 0, &payload),
                1 | 9 if flags & 4 != 0 && stream != 0 => {
                    answered += 1;
                    let mut r = frame(1, 4, stream, &[0x88]); // :status 200
                    r.extend(frame(0, 1, stream, b"ok\n"));
                    r
                }
                7 => break 'outer,
                _ => continue,
            };
            if s.write_all(&reply).is_err() {
                break 'outer;
            }
            if answered >= MAX_H2_REQUESTS {
                break 'outer;
            }
        }
    }
    (seen, answered)
}

/// One HTTP/1.1 request head, answered with `200` and `ok`.
fn serve_http1<S: Read + Write>(s: &mut S) -> Vec<u8> {
    let mut head = Vec::new();
    let mut chunk = [0u8; 1024];
    while head.len() < MAX_HTTP1_HEAD && !head.windows(4).any(|w| w == b"\r\n\r\n") {
        match s.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => head.extend_from_slice(&chunk[..n.min(MAX_HTTP1_HEAD - head.len())]),
        }
    }
    let _ = s.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 3\r\nConnection: close\r\n\r\nok\n");
    head.truncate(MAX_APP_LOG);
    head
}

fn handle(ctx: &SslContext, cfg: &Config, log: &Log, id: u64, port: u16, mut tcp: TcpStream) {
    let peer = tcp.peer_addr().map(|a| a.to_string()).unwrap_or_default();
    let _ = tcp.set_read_timeout(Some(IO_TIMEOUT));
    let _ = tcp.set_write_timeout(Some(IO_TIMEOUT));
    let mut fields = vec![
        ("id", id.to_string()),
        ("port", port.to_string()),
        ("peer", json_str(&peer)),
    ];
    let (raw, msg) = match read_client_hello(&mut tcp) {
        Ok(h) => h,
        Err(e) => {
            fields.push(("handshake", json_str(&format!("error: no ClientHello: {e}"))));
            log.line(&fields);
            return;
        }
    };
    let off = offered(&msg);
    fields.push(("hello", json_str(&hex(&raw))));
    fields.push(("alpn_offered", names_json(&off.alpn)));
    match &off.alps {
        Some((cp, protos)) => {
            fields.push(("alps_offered", json_str(&format!("{cp:04x}"))));
            fields.push(("alps_protocols", names_json(protos)));
        }
        None => fields.push(("alps_offered", "null".into())),
    }
    let use_new = match cfg.codepoint {
        Codepoint::Auto => off.alps.as_ref().map_or(true, |(cp, _)| *cp == ALPS_NEW),
        Codepoint::New => true,
        Codepoint::Old => false,
    };
    fields.push(("server_codepoint", json_str(if use_new { "44cd" } else { "4469" })));
    let ssl = match Ssl::new(ctx) {
        Ok(s) => s,
        Err(e) => {
            fields.push(("handshake", json_str(&format!("error: {e}"))));
            log.line(&fields);
            return;
        }
    };
    {
        let r: &SslRef = &ssl;
        // SAFETY: the SSL is live and not yet in a handshake; BoringSSL
        // copies the protocol and the settings.
        let ok = unsafe {
            boring_sys::SSL_set_alps_use_new_codepoint(ssl_ptr(r), i32::from(use_new));
            boring_sys::SSL_add_application_settings(
                ssl_ptr(r),
                b"h2".as_ptr(),
                2,
                cfg.settings.as_ptr(),
                cfg.settings.len(),
            )
        };
        if ok != 1 {
            fields.push(("handshake", json_str("error: SSL_add_application_settings")));
            log.line(&fields);
            return;
        }
    }
    let stream = Replay { head: raw, at: 0, inner: tcp };
    let mut tls = match ssl.accept(stream) {
        Ok(s) => s,
        Err(boring::ssl::HandshakeError::Failure(mid)) => {
            fields.push(("handshake", json_str(&format!("error: {}", mid.error()))));
            log.line(&fields);
            return;
        }
        Err(e) => {
            fields.push(("handshake", json_str(&format!("error: {e}"))));
            log.line(&fields);
            return;
        }
    };
    let s = tls.ssl();
    let alpn = s.selected_alpn_protocol().map(<[u8]>::to_vec);
    let (has_alps, peer_alps) = negotiated_alps(s);
    fields.push(("handshake", json_str("ok")));
    fields.push(("version", json_str(s.version_str())));
    fields.push((
        "cipher",
        json_str(s.current_cipher().map_or("?", |c| c.standard_name().unwrap_or("?"))),
    ));
    fields.push((
        "alpn",
        alpn.as_deref().map_or("null".into(), |p| json_str(&String::from_utf8_lossy(p))),
    ));
    fields.push(("alps", has_alps.to_string()));
    fields.push(("peer_alps", json_str(&hex(&peer_alps))));
    if alpn.as_deref() == Some(b"h2") {
        let _ = tls.get_mut().inner.set_read_timeout(Some(H2_IDLE));
        let (app, answered) = serve_h2(&mut tls);
        fields.push(("app", json_str(&hex(&app))));
        fields.push(("requests", answered.to_string()));
    } else {
        let head = serve_http1(&mut tls);
        fields.push(("app", json_str(&hex(&head))));
    }
    let _ = tls.shutdown();
    log.line(&fields);
}

fn main() {
    let cfg = Arc::new(parse_args());
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&cfg.log)
        .unwrap_or_else(|e| panic!("log {}: {e}", cfg.log));
    let log = Arc::new(Log(Mutex::new(file)));
    let (cert, key) = certificate().expect("certificate");
    let digest = cert.digest(MessageDigest::sha256()).expect("digest");
    log.line(&[("cert_sha256", json_str(&hex(&digest)))]);
    let ctx = Arc::new(context(&cert, &key).expect("context"));
    let ids = Arc::new(AtomicU64::new(0));
    let live = Arc::new(AtomicUsize::new(0));
    let mut listeners = Vec::new();
    for &port in &cfg.ports {
        let ln = TcpListener::bind(("127.0.0.1", port)).unwrap_or_else(|e| panic!("bind {port}: {e}"));
        let (ctx, cfg, log, ids, live) = (ctx.clone(), cfg.clone(), log.clone(), ids.clone(), live.clone());
        listeners.push(std::thread::spawn(move || {
            for tcp in ln.incoming() {
                let Ok(tcp) = tcp else { continue };
                if live.fetch_add(1, Ordering::SeqCst) >= MAX_CONNECTIONS {
                    live.fetch_sub(1, Ordering::SeqCst);
                    continue;
                }
                let id = ids.fetch_add(1, Ordering::SeqCst) + 1;
                let (ctx, cfg, log, live) = (ctx.clone(), cfg.clone(), log.clone(), live.clone());
                std::thread::spawn(move || {
                    handle(&ctx, &cfg, &log, id, port, tcp);
                    live.fetch_sub(1, Ordering::SeqCst);
                });
            }
        }));
    }
    eprintln!("alps-lab-server listening on {:?}", cfg.ports);
    for l in listeners {
        let _ = l.join();
    }
}
