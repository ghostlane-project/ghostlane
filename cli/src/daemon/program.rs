//! What the program gives the daemon: the real list fetch, the engine child
//! of the pin, the tun, the liveness probe, and the loop that serves the
//! control socket from the daemon's one thread.
//!
//! The state machine (`state.rs`) asks for five things and this file answers
//! them with the program's own parts:
//!
//! - a room (`start_engine`): `ghostlane-engine` with a room session, its
//!   SOCKS port behind a login of this start;
//! - an XHTTP line (`start_xray`): `ghostlane-engine` with a core session of
//!   the line (`gl-coreconfig`, from the link), its own inbound behind a login
//!   of this start;
//! - the front (`start_front`): a core in a process of its own whose inbound
//!   is the probe's (and, in proxy mode, the proxy the person asked for), its
//!   way out the line itself (VLESS, Hysteria2) or the SOCKS port of the
//!   engine in front of it (a room, an XHTTP line); in tun mode hev
//!   (`gl-routes`) takes what the device carries to that inbound;
//! - the probe: one request through a SOCKS port.
//!
//! Proxy mode is the core's own inbound. No other program stands in front.

use std::net::{TcpListener, ToSocketAddrs as _};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, PoisonError};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use coreconfig::spec::{Hysteria2, Vless};
use coreconfig::{
    build_plan, pin_host, text::is_ip_literal, Own, Plan, SocksLogin, Spec, Transport,
};
use gl_routes::hev::{Hev, HevParams};
use gocompat::time::Timestamp;
use serde_json::{json, Value};

use super::clock::{self, Clock};
use super::links::{Device, FetchError};
use super::routes::RulesRoutes;
use super::{scrub, Daemon, Deps, Engine, Front, FrontParams, OlcrtcParams, XrayParams};
use crate::engine::{self as engine_child, Options};
use crate::ipc::{self, Request, Response};
use crate::store::{Headers, UserInfo};

/// The tag of the proxy's own inbound beside the probe's (`in`).
const USER_TAG: &str = "in-user";

/// The most a list may be.
const MAX_BODY: usize = 4 << 20;

/// What a list host's sentence may be, in characters.
const ANNOUNCE_MAX: usize = 200;

type Log = Arc<dyn Fn(&str) + Send + Sync>;

/// What `run` is told.
#[derive(Clone)]
pub struct Settings {
    pub state_dir: PathBuf,
    pub probe_url: String,
    pub version: String,
}

/// The log: standard error, one line each, the secrets scrubbed.
pub fn stderr_log() -> Log {
    Arc::new(|line| eprintln!("{} {}", Timestamp::now().to_rfc3339(), scrub::scrub(line)))
}

// ---------------------------------------------------------------------------
// crypt1

/// The crypt1 decryptor of this build: none without the key it is built with.
pub fn decryptor() -> Option<super::Decrypt> {
    let master = option_env!("GHOSTLANE_CRYPT_KEY_V1").and_then(gl_links::parse_key)?;
    Some(Box::new(gl_links::crypt1_decryptor(master)))
}

// ---------------------------------------------------------------------------
// The lines, as a core is started from them

fn tcp_or_xhttp(kind: &str, path: &str, host: &str, mode: &str) -> Transport {
    if kind == "xhttp" {
        Transport::Xhttp {
            path: path.to_string(),
            host: host.to_string(),
            mode: mode.to_string(),
        }
    } else {
        Transport::Tcp
    }
}

fn non_empty(text: &str) -> Option<String> {
    (!text.is_empty()).then(|| text.to_string())
}

/// The line a link says, as the builder takes it.
pub fn spec_from_link(raw: &str) -> Result<Spec, String> {
    if raw.starts_with("vless://") {
        let line = gl_links::parse_vless(raw).map_err(|e| e.to_string())?;
        return Ok(Spec::Vless(Vless {
            uuid: line.uuid,
            host: line.host,
            port: line.port,
            sni: line.sni,
            public_key: line.public_key,
            short_id: line.short_id,
            fingerprint: line.fingerprint,
            flow: non_empty(&line.flow),
            transport: tcp_or_xhttp(
                &line.transport.kind,
                &line.transport.path,
                &line.transport.host,
                &line.transport.mode,
            ),
            tag: line.label,
            plain: line.plain,
            insecure: line.insecure,
            alpn: Vec::new(),
        }));
    }
    if raw.starts_with("hysteria2://") || raw.starts_with("hy2://") {
        let line = gl_links::parse_hy2(raw).map_err(|e| e.to_string())?;
        return Ok(Spec::Hysteria2(Hysteria2 {
            password: line.password,
            host: line.host,
            port: line.port,
            sni: line.sni,
            obfs_password: non_empty(&line.obfs_password),
            insecure: line.insecure,
            tag: line.label,
            cert_pin_sha256: non_empty(&line.pin_sha256),
        }));
    }
    Err("this line cannot be connected by this version".to_string())
}

/// A server named by a hostname is resolved here, on the network the machine
/// has, and written by its address: the core would resolve it through the
/// tunnel it is the other end of.
fn resolved(spec: Spec) -> Result<Spec, String> {
    if is_ip_literal(spec.host()) {
        return Ok(spec);
    }
    let found: Vec<std::net::SocketAddr> = (spec.host(), spec.port().clamp(1, 65535) as u16)
        .to_socket_addrs()
        .map_err(|e| format!("the server's name does not resolve: {e}"))?
        .collect();
    let address = found
        .iter()
        .find(|a| a.is_ipv4())
        .or_else(|| found.first())
        .ok_or("the server's name resolves to nothing")?;
    Ok(pin_host(&spec, &address.ip().to_string()))
}

/// What the proxy's own inbound is.
pub struct UserInbound {
    pub listen: String,
    pub port: u16,
    pub user: String,
    pub pass: String,
}

fn loopback(listen: &str) -> bool {
    listen
        .trim_matches(['[', ']'])
        .parse::<std::net::IpAddr>()
        .is_ok_and(|a| a.is_loopback())
}

/// The line `spec` gives, the probe's inbound (`in`, port and login), the
/// proxy's inbound beside it when there is one, and `through` for its way
/// out when the line is behind a SOCKS port instead of the spec's own.
pub fn core_config(
    spec: &Spec,
    probe_port: u16,
    probe_login: &SocksLogin,
    user: Option<&UserInbound>,
    through: Option<(&str, u16, &str, &str)>,
) -> Result<String, String> {
    let plan = Plan {
        own: Own::Tunnel {
            port: i32::from(probe_port),
            login: probe_login.clone(),
        },
        lan: None,
    };
    let text = build_plan(spec, &plan, false).map_err(|e| e.to_string())?;
    if user.is_none() && through.is_none() {
        return Ok(text);
    }
    let mut doc: Value = serde_json::from_str(&text).map_err(|e| format!("core config: {e}"))?;
    if let Some(user) = user {
        if !loopback(&user.listen) && (user.user.is_empty() || user.pass.is_empty()) {
            return Err(format!(
                "a proxy on {} is reachable from the network and needs a login",
                user.listen
            ));
        }
        let mut inbound = doc["inbounds"][0].clone();
        inbound["tag"] = json!(USER_TAG);
        inbound["listen"] = json!(user.listen.trim_matches(['[', ']']));
        inbound["port"] = json!(user.port);
        let mut settings = json!({"udp": true});
        if user.user.is_empty() && user.pass.is_empty() {
            settings["auth"] = json!("noauth");
        } else {
            settings["auth"] = json!("password");
            settings["accounts"] = json!([{"user": user.user, "pass": user.pass}]);
        }
        if !loopback(&user.listen) {
            settings["allowUnauthenticatedLan"] = json!(true);
        }
        inbound["settings"] = settings;
        doc["inbounds"]
            .as_array_mut()
            .ok_or("core config: no inbounds")?
            .push(inbound);
        if let Some(rules) = doc["routing"]["rules"].as_array_mut() {
            for rule in rules {
                if let Some(tags) = rule.get_mut("inboundTag").and_then(Value::as_array_mut) {
                    tags.push(json!(USER_TAG));
                }
            }
        }
    }
    if let Some((host, port, name, pass)) = through {
        let mut server = json!({"address": host, "port": port});
        if !name.is_empty() {
            server["user"] = json!(name);
            server["pass"] = json!(pass);
        }
        doc["outbounds"][0] =
            json!({"tag": coreconfig::LINE, "protocol": "socks", "settings": server});
    }
    Ok(doc.to_string())
}

/// A line the front never dials: the spec the way out is swapped in for.
fn placeholder() -> Spec {
    Spec::Vless(Vless {
        uuid: "00000000-0000-4000-8000-000000000000".to_string(),
        host: "127.0.0.1".to_string(),
        port: 1,
        sni: String::new(),
        public_key: String::new(),
        short_id: String::new(),
        fingerprint: "chrome".to_string(),
        flow: None,
        transport: Transport::Tcp,
        tag: "front".to_string(),
        plain: true,
        insecure: false,
        alpn: Vec::new(),
    })
}

fn split_address(address: &str) -> Result<(String, u16), String> {
    let (host, port) = address
        .rsplit_once(':')
        .ok_or_else(|| format!("{address:?} is not host:port"))?;
    let port = port
        .parse::<u16>()
        .map_err(|_| format!("{address:?} has no port"))?;
    Ok((host.trim_matches(['[', ']']).to_string(), port))
}

fn free_port() -> Result<u16, String> {
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|e| format!("no free port: {e}"))?;
    listener
        .local_addr()
        .map(|a| a.port())
        .map_err(|e| format!("no free port: {e}"))
}

// ---------------------------------------------------------------------------
// The engine, the front

/// The program that stands in front of the tun and its rules.
pub fn locate_hev() -> Result<PathBuf, String> {
    let named = std::env::var_os("GHOSTLANE_HEV").filter(|n| !n.is_empty());
    let path = match named {
        Some(named) => PathBuf::from(named),
        None => std::env::current_exe()
            .map_err(|e| format!("this program's own path: {e}"))?
            .parent()
            .ok_or("this program has no directory to look for ghostlane-hev in")?
            .join("ghostlane-hev"),
    };
    if path.is_file() {
        Ok(path)
    } else {
        Err(format!("no ghostlane-hev at {}", path.display()))
    }
}

fn options(state_dir: &Path, log: &Log) -> Result<Options, String> {
    let program = engine_child::locate_here()?;
    let mut options = Options::new(program, state_dir.join("sessions"));
    options.ready_timeout = Duration::from_secs(60);
    options.log = Arc::clone(log);
    Ok(options)
}

/// A core in a process of its own, with the tun's hev in front of it in tun
/// mode.
struct CoreFront {
    hev: Mutex<Option<Hev>>,
    core: engine_child::EngineChild,
}

impl Front for CoreFront {
    fn close(&self) -> Result<(), String> {
        let hev = self
            .hev
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take();
        // the rules out and hev gone first: nothing points at a dead core
        let first = match hev {
            Some(hev) => hev.close(),
            None => Ok(()),
        };
        let second = Engine::stop(&self.core);
        first.and(second)
    }
}

fn start_front(
    state_dir: &Path,
    log: &Log,
    manager: &Arc<gl_routes::Manager>,
    uid: u32,
    p: &FrontParams,
) -> Result<Box<dyn Front>, String> {
    let options = options(state_dir, log)?;
    let (_, probe_port) = split_address(&p.probe_listen)?;
    let login = SocksLogin::new(&p.probe_user, &p.probe_pass);
    let user = (!p.preflight && p.mode == "proxy").then(|| UserInbound {
        listen: p.proxy_listen.clone(),
        port: p.proxy_port,
        user: p.proxy_user.clone(),
        pass: p.proxy_pass.clone(),
    });
    let text = if p.upstream == "socks" {
        let (host, port) = split_address(&p.upstream_addr)?;
        core_config(
            &placeholder(),
            probe_port,
            &login,
            user.as_ref(),
            Some((
                host.as_str(),
                port,
                p.upstream_user.as_str(),
                p.upstream_pass.as_str(),
            )),
        )?
    } else {
        let spec = resolved(spec_from_link(&p.line)?)?;
        core_config(&spec, probe_port, &login, user.as_ref(), None)?
    };
    let core = engine_child::start_core(
        &options,
        &text,
        Some((p.probe_user.as_str(), p.probe_pass.as_str())),
        None,
        None,
    )?;
    let hev = if p.mode == "tun" && !p.preflight {
        let params = HevParams {
            binary: locate_hev()?,
            work_dir: state_dir.join("hev"),
            socks_addr: p.probe_listen.clone(),
            user: p.probe_user.clone(),
            pass: p.probe_pass.clone(),
            bypass_uid: Some(uid),
        };
        Some(Hev::start(Arc::clone(manager), &params)?)
    } else {
        None
    };
    Ok(Box::new(CoreFront {
        hev: Mutex::new(hev),
        core,
    }))
}

// ---------------------------------------------------------------------------
// The list fetch

fn one_line(text: &str) -> String {
    let mut out = String::new();
    let mut gap = false;
    for c in text.chars() {
        if matches!(c, '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}' | '\u{200e}' | '\u{200f}' | '\u{061c}' | '\u{feff}')
        {
            continue;
        }
        if c.is_whitespace() || c.is_control() {
            gap = !out.is_empty();
            continue;
        }
        if gap {
            out.push(' ');
            gap = false;
        }
        out.push(c);
        if out.chars().count() > ANNOUNCE_MAX {
            break;
        }
    }
    out.chars()
        .take(ANNOUNCE_MAX)
        .collect::<String>()
        .trim_end()
        .to_string()
}

/// The host's sentence as it may be shown: none for "0" and for one whose
/// base64 did not decode.
fn announce_text(text: &str) -> String {
    let text = text.trim();
    let encoded = text
        .get(..7)
        .is_some_and(|p| p.eq_ignore_ascii_case("base64:"));
    if text == "0" || encoded {
        String::new()
    } else {
        one_line(text)
    }
}

fn headers_of(parsed: gl_links::Headers) -> Headers {
    Headers {
        title: parsed.title,
        update_interval_hours: i64::from(parsed.update_interval_hours),
        user_info: parsed.user_info.map(|info| UserInfo {
            upload: info.upload,
            download: info.download,
            total: info.total,
            expire: info.expire,
        }),
        support_url: parsed.support_url,
        web_page_url: parsed.web_page_url,
        announce: parsed.announce,
        refusal: parsed.refusal,
    }
}

/// Asks a list host for its list, naming `device`.
pub fn fetch(
    agent: &ureq::Agent,
    user_agent: &str,
    url: &str,
    device: &Device,
) -> Result<(Vec<u8>, Headers), FetchError> {
    let mut request = agent
        .get(url)
        .header("User-Agent", user_agent)
        .header("Accept", "text/plain, */*");
    for (name, value) in
        gl_links::device_headers(&device.id, &device.os, &device.os_version, &device.model)
    {
        request = request.header(name, value);
    }
    // an error of the client never carries the URL: the URL is the credential
    let mut response = request
        .call()
        .map_err(|e| FetchError::other(format!("list: fetch: {e}")))?;
    let status = response.status().as_u16();
    if !(200..=299).contains(&status) {
        return Err(FetchError::other(format!("list: HTTP {status}")));
    }
    let pairs: Vec<(String, String)> = response
        .headers()
        .iter()
        .filter_map(|(name, value)| {
            value
                .to_str()
                .ok()
                .map(|v| (name.as_str().to_string(), v.to_string()))
        })
        .collect();
    let refs: Vec<(&str, &str)> = pairs
        .iter()
        .map(|(n, v)| (n.as_str(), v.as_str()))
        .collect();
    let mut headers = headers_of(gl_links::parse_headers(&refs));
    if !headers.refusal.is_empty() {
        headers.announce = announce_text(&headers.announce);
        return Err(FetchError::Refused {
            kind: headers.refusal.clone(),
            announce: headers.announce.clone(),
            headers: Box::new(headers),
        });
    }
    if status != 200 {
        return Err(FetchError::other(format!("list: HTTP {status}")));
    }
    let body = response
        .body_mut()
        .with_config()
        .limit(MAX_BODY as u64 + 1)
        .read_to_vec()
        .map_err(|e| FetchError::other(format!("list: {e}")))?;
    if body.len() > MAX_BODY {
        return Err(FetchError::other(format!(
            "list: body over {MAX_BODY} bytes"
        )));
    }
    Ok((body, headers))
}

fn kernel_release() -> String {
    // SAFETY: uname writes into the zeroed struct it is given.
    let mut name: libc::utsname = unsafe { std::mem::zeroed() };
    if unsafe { libc::uname(&mut name) } != 0 {
        return String::new();
    }
    let bytes: Vec<u8> = name
        .release
        .iter()
        .take_while(|c| **c != 0)
        .map(|c| *c as u8)
        .collect();
    String::from_utf8_lossy(&bytes).into_owned()
}

// ---------------------------------------------------------------------------
// The daemon's deps

/// Everything the daemon is given, with the program's own parts.
pub fn deps(settings: &Settings, log: Log) -> Deps {
    let state_dir = settings.state_dir.clone();
    let rules = RulesRoutes::new(Arc::clone(&log));
    let manager = rules.manager();
    // SAFETY: getuid cannot fail.
    let uid = unsafe { libc::getuid() };
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(30)))
        .build()
        .into();
    let user_agent = format!("Ghostlane-cli/{} (linux)", settings.version);
    let probe_url = settings.probe_url.clone();

    let start_engine = {
        let (state_dir, log) = (state_dir.clone(), Arc::clone(&log));
        Box::new(
            move |params: &OlcrtcParams| -> Result<Box<dyn Engine>, String> {
                let options = options(&state_dir, &log)?;
                let login = engine_child::random_login()?;
                let room = engine_child::start_room(&options, params, login, None)?;
                Ok(Box::new(room) as Box<dyn Engine>)
            },
        )
    };
    let start_xray = {
        let (state_dir, log) = (state_dir.clone(), Arc::clone(&log));
        Box::new(
            move |params: &XrayParams| -> Result<Box<dyn Engine>, String> {
                let options = options(&state_dir, &log)?;
                let spec = resolved(spec_from_link(&params.raw)?)?;
                let (user, pass) = engine_child::random_login()?;
                let port = free_port()?;
                let text = core_config(&spec, port, &SocksLogin::new(&user, &pass), None, None)?;
                let core = engine_child::start_core(
                    &options,
                    &text,
                    Some((user.as_str(), pass.as_str())),
                    None,
                    None,
                )?;
                Ok(Box::new(core) as Box<dyn Engine>)
            },
        )
    };
    let start_front_dep = {
        let (state_dir, log) = (state_dir.clone(), Arc::clone(&log));
        Box::new(move |params: &FrontParams| start_front(&state_dir, &log, &manager, uid, params))
    };
    let clock: Clock = Arc::new(Timestamp::now);
    let logf = Arc::clone(&log);
    Deps {
        config_path: state_dir.join("config.yaml"),
        state_dir: state_dir.clone(),
        socket_path: None,
        fetch: Box::new(move |url, device| fetch(&agent, &user_agent, url, &device)),
        device: Device {
            os: "Linux".to_string(),
            os_version: kernel_release(),
            model: "PC".to_string(),
            ..Device::default()
        },
        decrypt: decryptor(),
        start_engine,
        start_xray,
        start_front: start_front_dep,
        routes: Box::new(rules),
        probe: Box::new(move |address, user, pass| {
            engine_child::socks_probe(address, user, pass, &probe_url)
        }),
        probe_inbound: Box::new(engine_child::probe_inbound),
        clock,
        advance: Box::new(|_| {}),
        logf: Box::new(move |line| logf(line)),
        uid: uid as i32,
        fetch_delay_ms: 0,
        stop_delay_ms: 0,
        confirm_timeout_ms: 45_000,
        probe_interval_ms: 30_000,
        probe_failures: 3,
        retry_min_ms: 10_000,
        retry_max_ms: 300_000,
        version: crate::commands::version_info(),
    }
}

// ---------------------------------------------------------------------------
// systemd

/// Tells systemd (Type=notify) the daemon serves; nothing elsewhere.
pub fn notify_ready() {
    use std::os::linux::net::SocketAddrExt as _;
    use std::os::unix::ffi::OsStrExt as _;
    use std::os::unix::net::{SocketAddr, UnixDatagram};

    let Some(path) = std::env::var_os("NOTIFY_SOCKET").filter(|p| !p.is_empty()) else {
        return;
    };
    let Ok(socket) = UnixDatagram::unbound() else {
        return;
    };
    let bytes = path.as_bytes();
    if let Some(name) = bytes.strip_prefix(b"@") {
        // an abstract socket
        if let Ok(address) = SocketAddr::from_abstract_name(name) {
            let _ = socket.send_to_addr(b"READY=1", &address);
        }
    } else {
        let _ = socket.send_to(b"READY=1", Path::new(&path));
    }
}

// ---------------------------------------------------------------------------
// The loop

enum Message {
    Call(Request, Sender<Response>),
    Stop,
}

/// The daemon's thread and the way to ask it.
pub struct Running {
    tx: Sender<Message>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

impl Running {
    /// Starts the daemon: its deps are made on its own thread, which holds
    /// the state and the clock until `stop`.
    pub fn start(make: impl FnOnce() -> Deps + Send + 'static) -> Running {
        let (tx, rx): (Sender<Message>, Receiver<Message>) = mpsc::channel();
        let thread = thread::spawn(move || {
            let mut daemon = Daemon::new(make());
            daemon.run();
            loop {
                match rx.recv_timeout(Duration::from_millis(15)) {
                    Ok(Message::Call(request, reply)) => {
                        let _ = reply.send(daemon.handle(request));
                    }
                    Ok(Message::Stop) | Err(mpsc::RecvTimeoutError::Disconnected) => {
                        daemon.stop();
                        return;
                    }
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                }
                let due = daemon
                    .next_deadline()
                    .is_none_or(|at| clock::millis(Timestamp::now()) >= at);
                if due {
                    daemon.tick();
                }
            }
        });
        Running {
            tx,
            thread: Mutex::new(Some(thread)),
        }
    }

    /// One request, answered by the daemon's thread.
    pub fn call(&self, request: Request) -> Response {
        let (reply, answer) = mpsc::channel();
        if self.tx.send(Message::Call(request, reply)).is_err() {
            return ipc::fail("stopping", "the daemon is stopping");
        }
        answer
            .recv_timeout(Duration::from_secs(120))
            .unwrap_or_else(|_| ipc::fail("timeout", "the daemon did not answer in time"))
    }

    /// A clean stop: the line is closed, the rules are taken out.
    pub fn stop(&self) {
        let _ = self.tx.send(Message::Stop);
        let thread = self
            .thread
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take();
        if let Some(thread) = thread {
            let _ = thread.join();
        }
    }
}
