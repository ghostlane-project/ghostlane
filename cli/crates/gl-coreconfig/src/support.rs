//! What more than one kind of line reads or writes the same way: the app's
//! `CoreSupport` and `CoreStream`.

use crate::json::{strings, Json, Object};
use crate::spec::{Transport, Vless};
use crate::text::{if_blank, trim};

/// Go's stream window for XHTTP on HTTP/2 on iOS, which the app writes for every host.
pub const XHTTP_H2_STREAM_RECEIVE_WINDOW: i32 = 524_288;

/// The upload chunk of XHTTP's packet-up, bounding what the core buffers.
pub const XHTTP_MAX_EACH_POST_BYTES: i32 = 200_000;

/// The names [`hello_name`] answers as they are.
const HELLO_NAMES: [&str; 10] = [
    "chrome",
    "firefox",
    "edge",
    "safari",
    "360",
    "qq",
    "ios",
    "android",
    "random",
    "randomized",
];

const VISION_FLOWS: [&str; 2] = ["xtls-rprx-vision", "xtls-rprx-vision-udp443"];

/// A link's `fp` as the core is given it: the name itself when the core
/// knows it, a spelling of Xray's or sing-box's read as the nearest name,
/// and chrome for any other and for none.
pub fn hello_name(fp: Option<&str>) -> String {
    let name = trim(fp.unwrap_or("")).to_lowercase();
    let nearest = if name.is_empty() {
        "chrome"
    } else if HELLO_NAMES.contains(&name.as_str()) {
        return name;
    } else if name.starts_with("chrome") || name.starts_with("hellochrome") {
        "chrome"
    } else if name.starts_with("hellofirefox") {
        "firefox"
    } else if name.starts_with("hellosafari") {
        "safari"
    } else if name.starts_with("helloios") {
        "ios"
    } else if name.starts_with("helloedge") {
        "edge"
    } else if name.starts_with("hello360") {
        "360"
    } else if name.starts_with("helloqq") {
        "qq"
    } else if name.starts_with("helloandroid") {
        "android"
    } else if name.starts_with("randomized") {
        "randomized"
    } else {
        "chrome"
    };
    nearest.to_string()
}

/// A certificate pin as the core reads `pinnedPeerCertSha256`: compact
/// lower-case hex, colons dropped. None unless it is 32 bytes of hex.
pub fn pin(raw: &str) -> Option<String> {
    let hex = trim(raw).replace(':', "").to_lowercase();
    (hex.len() == 64 && hex.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))).then_some(hex)
}

/// Why the core would refuse a VLESS link's flow: it knows Vision and no other.
pub fn flow_refusal(flow: Option<&str>) -> Option<String> {
    match flow {
        Some(f) if !VISION_FLOWS.contains(&trim(f).to_lowercase().as_str()) => {
            Some("the core knows no flow but Vision".to_string())
        }
        _ => None,
    }
}

/// WebSocket's and HTTP upgrade's Host left blank, given the server's name;
/// every other transport as it is.
pub fn upgrade_host_or(transport: &Transport, name: &str) -> Transport {
    match transport {
        Transport::Ws { path, host } => Transport::Ws {
            path: path.clone(),
            host: if_blank(host, name),
        },
        Transport::HttpUpgrade { path, host } => Transport::HttpUpgrade {
            path: path.clone(),
            host: if_blank(host, name),
        },
        other => other.clone(),
    }
}

/// A VLESS line written for `address`: the name stays the TLS server name
/// and the Host of the transport where the link left them to be the server's own.
pub fn pin_vless(spec: &Vless, address: &str) -> Vless {
    let name = spec.host.as_str();
    let transport = match &spec.transport {
        Transport::Xhttp { path, host, mode } => Transport::Xhttp {
            path: path.clone(),
            host: if_blank(host, name),
            mode: mode.clone(),
        },
        Transport::Http { path, hosts } => Transport::Http {
            path: path.clone(),
            hosts: if hosts.is_empty() {
                vec![name.to_string()]
            } else {
                hosts.clone()
            },
        },
        other => upgrade_host_or(other, name),
    };
    Vless {
        host: address.to_string(),
        sni: if_blank(&spec.sni, name),
        transport,
        ..spec.clone()
    }
}

/// A VLESS outbound's `protocol` and `settings`: one server, one user.
/// Vision is written as its -udp443 variant: the server is sent plain
/// Vision either way, and QUIC to port 443 passes.
pub fn put_vless(spec: &Vless, out: &mut Object) {
    out.put("protocol", "vless");
    let mut user = Object::new();
    user.put("id", &spec.uuid);
    user.put("encryption", "none");
    if spec.flow.is_some() {
        user.put("flow", "xtls-rprx-vision-udp443");
    }
    let mut server = Object::new();
    server.put("address", &spec.host);
    server.put("port", spec.port);
    server.put("users", Json::Array(vec![user.into()]));
    let mut settings = Object::new();
    settings.put("vnext", Json::Array(vec![server.into()]));
    out.put("settings", settings);
}

/// `network` and the transport's own settings.
pub fn put_transport(transport: &Transport, out: &mut Object) {
    match transport {
        Transport::Tcp => out.put("network", "raw"),
        Transport::Grpc { service_name } => {
            out.put("network", "grpc");
            let mut s = Object::new();
            s.put("serviceName", service_name);
            out.put("grpcSettings", s);
        }
        Transport::Ws { path, host } => {
            out.put("network", "ws");
            out.put("wsSettings", path_and_host(path, host));
        }
        Transport::HttpUpgrade { path, host } => {
            out.put("network", "httpupgrade");
            out.put("httpupgradeSettings", path_and_host(path, host));
        }
        Transport::Xhttp { path, host, mode } => {
            out.put("network", "xhttp");
            let mut s = Object::new();
            s.put("path", path);
            s.put("host", host);
            s.put("mode", mode);
            s.put("scMaxEachPostBytes", XHTTP_MAX_EACH_POST_BYTES);
            s.put("h2StreamReceiveWindow", XHTTP_H2_STREAM_RECEIVE_WINDOW);
            out.put("xhttpSettings", s);
        }
        // Every kind refuses HTTP/2, and nothing is written for a refused line.
        Transport::Http { .. } => {
            unreachable!("no kind of line writes HTTP/2 (type=http) for the core")
        }
    }
}

fn path_and_host(path: &str, host: &str) -> Object {
    let mut s = Object::new();
    s.put("path", path);
    if !crate::text::is_blank(host) {
        s.put("host", host);
    }
    s
}

/// Ordinary TLS as a kind hands it on: `security: tls` and `tlsSettings`.
#[derive(Default)]
pub struct TlsOut<'a> {
    /// Blank for the server's own name.
    pub server_name: &'a str,
    /// The hello's name; None where the core takes none.
    pub fingerprint: Option<String>,
    pub alpn: &'a [String],
    /// A certificate's SHA-256 as [`pin`] spells it.
    pub pin: Option<String>,
    /// The link's own wish to waive certificate checks, written only when true.
    pub insecure: bool,
}

pub fn put_tls(tls: TlsOut<'_>, out: &mut Object) {
    out.put("security", "tls");
    let mut s = Object::new();
    if !crate::text::is_blank(tls.server_name) {
        s.put("serverName", tls.server_name);
    }
    if tls.insecure {
        s.put("allowInsecure", true);
    }
    if let Some(fp) = tls.fingerprint {
        s.put("fingerprint", fp);
    }
    if !tls.alpn.is_empty() {
        s.put("alpn", strings(tls.alpn));
    }
    if let Some(pin) = tls.pin {
        s.put("pinnedPeerCertSha256", pin);
    }
    out.put("tlsSettings", s);
}

/// `security: reality` and `realitySettings`.
pub fn put_reality(
    server_name: &str,
    fingerprint: &str,
    public_key: &str,
    short_id: &str,
    out: &mut Object,
) {
    out.put("security", "reality");
    let mut s = Object::new();
    s.put("serverName", server_name);
    s.put("fingerprint", fingerprint);
    s.put("publicKey", public_key);
    s.put("shortId", short_id);
    out.put("realitySettings", s);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pin_is_compact_lower_case_hex_of_32_bytes() {
        let openssl = "9B:D5:34:57:E3:0A:CF:B2:35:6C:8E:A2:8E:DB:9F:95:2E:06:01:F5:6A:1A:A3:70:1A:5B:4E:A2:78:A1:11:F8";
        assert_eq!(
            pin(openssl).as_deref(),
            Some("9bd53457e30acfb2356c8ea28edb9f952e0601f56a1aa3701a5b4ea278a111f8")
        );
        assert_eq!(pin(&openssl[..openssl.len() - 3]), None);
        assert_eq!(pin("zz"), None);
    }

    #[test]
    fn only_vision_is_a_flow() {
        assert_eq!(flow_refusal(None), None);
        assert_eq!(flow_refusal(Some(" XTLS-RPRX-VISION-UDP443 ")), None);
        assert!(flow_refusal(Some("")).is_some());
        assert!(flow_refusal(Some("xtls-rprx-direct")).is_some());
    }
}
