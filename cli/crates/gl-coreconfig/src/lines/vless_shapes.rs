//! VLESS with no Reality key: over ordinary TLS on raw TCP (with Vision or
//! without a flow), gRPC and XHTTP, and with no TLS at all on raw TCP,
//! WebSocket, gRPC, HTTP upgrade and XHTTP, which the core sends only to a
//! private, loopback or link-local server. VLESS over HTTP/2 is
//! `VlessHttp2`'s.

use super::{not_mine, Line};
use crate::json::Object;
use crate::spec::{Spec, Transport, Vless};
use crate::support::{
    flow_refusal, hello_name, pin_vless, put_tls, put_transport, put_vless, TlsOut,
};
use crate::text::is_blank;

pub struct VlessShapes;

const TLS: &str = "vless-tls";
const TLS_GRPC: &str = "vless-tls-grpc";
const TLS_XHTTP: &str = "vless-tls-xhttp";
const NONE: &str = "vless-none";
const NONE_WS: &str = "vless-none-ws";
const NONE_GRPC: &str = "vless-none-grpc";
const NONE_HTTP_UPGRADE: &str = "vless-none-httpupgrade";
const NONE_XHTTP: &str = "vless-none-xhttp";

const VISION_NEEDS_TLS: &str =
    "the link asks for Vision with no TLS (security=none), and Vision runs only inside TLS or Reality";
const PLAIN_NEEDS_PRIVATE_SERVER: &str =
    "the link asks for VLESS with no TLS (security=none) to a public address, \
     and the core sends VLESS in the clear only to a private, loopback or link-local server";

fn own(spec: &Spec) -> &Vless {
    match spec {
        Spec::Vless(s) => s,
        _ => not_mine("VlessShapes"),
    }
}

impl Line for VlessShapes {
    fn name(&self) -> &'static str {
        "VlessShapes"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[
            TLS,
            TLS_GRPC,
            TLS_XHTTP,
            NONE,
            NONE_WS,
            NONE_GRPC,
            NONE_HTTP_UPGRADE,
            NONE_XHTTP,
        ]
    }

    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        let Spec::Vless(s) = spec else { return None };
        if matches!(s.transport, Transport::Http { .. }) {
            return None;
        }
        if !is_blank(&s.public_key) {
            return None;
        }
        if s.plain {
            return Some(match s.transport {
                Transport::Tcp => NONE,
                Transport::Ws { .. } => NONE_WS,
                Transport::Grpc { .. } => NONE_GRPC,
                Transport::HttpUpgrade { .. } => NONE_HTTP_UPGRADE,
                Transport::Xhttp { .. } => NONE_XHTTP,
                Transport::Http { .. } => return None,
            });
        }
        match s.transport {
            Transport::Tcp => Some(TLS),
            Transport::Grpc { .. } => Some(TLS_GRPC),
            Transport::Xhttp { .. } => Some(TLS_XHTTP),
            // Ordinary TLS over WebSocket and HTTP upgrade: WebTunnel's.
            _ => None,
        }
    }

    /// Refused: a flow other than Vision, Vision with no TLS, and no
    /// TLS to a server the core does not send VLESS in the clear to. Waived
    /// certificate checks are carried, and written as the link asks.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        if let Some(why) = flow_refusal(s.flow.as_deref()) {
            return Some(why);
        }
        if s.plain && s.flow.is_some() {
            return Some(VISION_NEEDS_TLS.to_string());
        }
        if s.plain && !is_private_server(&s.host) {
            return Some(PLAIN_NEEDS_PRIVATE_SERVER.to_string());
        }
        None
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        Spec::Vless(pin_vless(own(spec), address))
    }

    /// `security: none` for a link with no TLS; otherwise ordinary TLS with
    /// the link's server name and hello, `allowInsecure` exactly when the
    /// link waives the check, and no ALPN.
    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        put_vless(s, out);
        let mut stream = Object::new();
        put_transport(&s.transport, &mut stream);
        if s.plain {
            stream.put("security", "none");
        } else {
            put_tls(
                TlsOut {
                    server_name: &s.sni,
                    fingerprint: Some(hello_name(Some(&s.fingerprint))),
                    insecure: s.insecure,
                    ..TlsOut::default()
                },
                &mut stream,
            );
        }
        out.put("streamSettings", stream);
    }
}

/// Whether the core sends VLESS in the clear to `host`: a private, loopback
/// or link-local address (an IPv4-mapped IPv6 one read as IPv4), read as
/// strictly as the core reads an address, brackets or not.
fn is_private_server(host: &str) -> bool {
    let text = host
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(host);
    if let Some(v4) = ipv4(text) {
        return is_private_v4(&v4);
    }
    let Some(v6) = ipv6(text) else { return false };
    if v6[..10].iter().all(|b| *b == 0) && v6[10] == 0xff && v6[11] == 0xff {
        return is_private_v4(&v6[12..16]);
    }
    let unique_local = (v6[0] & 0xfe) == 0xfc;
    let link_local = v6[0] == 0xfe && (v6[1] & 0xc0) == 0x80;
    let loopback = v6[..15].iter().all(|b| *b == 0) && v6[15] == 1;
    unique_local || link_local || loopback
}

/// 10/8, 172.16/12, 192.168/16, 127/8 and 169.254/16.
fn is_private_v4(octets: &[u8]) -> bool {
    let (a, b) = (octets[0], octets[1]);
    a == 10
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168)
        || a == 127
        || (a == 169 && b == 254)
}

/// Four decimal octets, none with a leading zero.
fn ipv4(text: &str) -> Option<Vec<u8>> {
    let parts: Vec<&str> = text.split('.').collect();
    if parts.len() != 4 {
        return None;
    }
    parts
        .iter()
        .map(|part| {
            let ok = !part.is_empty()
                && part.len() <= 3
                && part.bytes().all(|b| b.is_ascii_digit())
                && !(part.len() > 1 && part.starts_with('0'));
            if !ok {
                return None;
            }
            part.parse::<u16>().ok().and_then(|n| u8::try_from(n).ok())
        })
        .collect()
}

/// The sixteen bytes of an IPv6 address: groups of one to four hex digits,
/// at most one `::` standing for at least one group, an IPv4 address only at
/// the end.
fn ipv6(text: &str) -> Option<Vec<u8>> {
    let Some(gap) = text.find("::") else {
        return groups(text, true).filter(|b| b.len() == 16);
    };
    if text.rfind("::") != Some(gap) {
        return None;
    }
    let head = groups(&text[..gap], false)?;
    let tail = groups(&text[gap + 2..], true)?;
    let zeros = 16usize.checked_sub(head.len() + tail.len())?;
    if zeros < 2 {
        return None;
    }
    let mut bytes = head;
    bytes.extend(std::iter::repeat_n(0u8, zeros));
    bytes.extend(tail);
    Some(bytes)
}

/// The bytes of colon-separated groups, the last an IPv4 address when `ipv4_last` lets it be.
fn groups(text: &str, ipv4_last: bool) -> Option<Vec<u8>> {
    if text.is_empty() {
        return Some(Vec::new());
    }
    let parts: Vec<&str> = text.split(':').collect();
    let mut bytes = Vec::new();
    for (i, part) in parts.iter().enumerate() {
        if ipv4_last && i == parts.len() - 1 && part.contains('.') {
            bytes.extend(ipv4(part)?);
        } else {
            if part.is_empty() || part.len() > 4 || !part.bytes().all(|b| b.is_ascii_hexdigit()) {
                return None;
            }
            let group = u16::from_str_radix(part, 16).ok()?;
            bytes.push((group >> 8) as u8);
            bytes.push((group & 0xff) as u8);
        }
    }
    Some(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn private_servers_are_the_cores() {
        for host in [
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "127.0.0.1",
            "169.254.1.1",
            "::1",
            "[::1]",
            "fd00::1",
            "FE80::1",
            "::ffff:10.1.2.3",
            "0:0:0:0:0:0:0:1",
        ] {
            assert!(is_private_server(host), "{host}");
        }
        for host in [
            "8.8.8.8",
            "172.32.0.1",
            "10.01.0.1",
            "2001:db8::1",
            "::ffff:8.8.8.8",
            "fe80::1%eth0",
            "fe80::1::2",
            "1:2:3:4:5:6:7::8",
            "[::1",
            "",
        ] {
            assert!(!is_private_server(host), "{host}");
        }
    }
}
