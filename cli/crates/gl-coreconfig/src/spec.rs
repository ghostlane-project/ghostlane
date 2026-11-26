//! What a share link says, as the app's link parser reads it
//! (`OutboundSpec`, `TransportSpec`, `TlsSpec` and `SocksLogin` in
//! sharedUI `net`), and how a core is to listen (`CoreInbound.Plan`).
//!
//! As JSON every field is written, null where the app's is null: the form
//! the vectors carry and the CLI keeps.

use serde::{Deserialize, Serialize};

/// A line, by its protocol. Ports are `i32`, as the app's `Int`: a port no
/// listener or server can have is one more thing a refusal names.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Spec {
    Vless(Vless),
    Hysteria2(Hysteria2),
    Trojan(Trojan),
    Shadowsocks(Shadowsocks),
    Vmess(Vmess),
}

impl Spec {
    /// The server, a name or an address as the link gives it.
    pub fn host(&self) -> &str {
        match self {
            Spec::Vless(s) => &s.host,
            Spec::Hysteria2(s) => &s.host,
            Spec::Trojan(s) => &s.host,
            Spec::Shadowsocks(s) => &s.host,
            Spec::Vmess(s) => &s.host,
        }
    }

    pub fn port(&self) -> i32 {
        match self {
            Spec::Vless(s) => s.port,
            Spec::Hysteria2(s) => s.port,
            Spec::Trojan(s) => s.port,
            Spec::Shadowsocks(s) => s.port,
            Spec::Vmess(s) => s.port,
        }
    }

    /// The link's label.
    pub fn tag(&self) -> &str {
        match self {
            Spec::Vless(s) => &s.tag,
            Spec::Hysteria2(s) => &s.tag,
            Spec::Trojan(s) => &s.tag,
            Spec::Shadowsocks(s) => &s.tag,
            Spec::Vmess(s) => &s.tag,
        }
    }

    /// The protocol by the name of the app's class, as a refusal says it.
    pub fn protocol_name(&self) -> &'static str {
        match self {
            Spec::Vless(_) => "Vless",
            Spec::Hysteria2(_) => "Hysteria2",
            Spec::Trojan(_) => "Trojan",
            Spec::Shadowsocks(_) => "Shadowsocks",
            Spec::Vmess(_) => "Vmess",
        }
    }

    /// The same line with `address` where the connection goes.
    pub(crate) fn with_host(&self, address: &str) -> Spec {
        let mut out = self.clone();
        match &mut out {
            Spec::Vless(s) => s.host = address.to_string(),
            Spec::Hysteria2(s) => s.host = address.to_string(),
            Spec::Trojan(s) => s.host = address.to_string(),
            Spec::Shadowsocks(s) => s.host = address.to_string(),
            Spec::Vmess(s) => s.host = address.to_string(),
        }
        out
    }
}

/// The transport a link names by `type`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "lowercase",
    rename_all_fields = "camelCase"
)]
pub enum Transport {
    Tcp,
    Grpc {
        service_name: String,
    },
    Xhttp {
        path: String,
        host: String,
        mode: String,
    },
    /// WebSocket; `host` is the Host header, empty for the server's own name.
    Ws {
        path: String,
        host: String,
    },
    /// HTTP upgrade, the lighter WebSocket handshake.
    HttpUpgrade {
        path: String,
        host: String,
    },
    /// HTTP/2 (`type=http` or `h2`), which no kind writes: every kind refuses it.
    Http {
        path: String,
        hosts: Vec<String>,
    },
}

/// Ordinary TLS as a Trojan or VMess link spells it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Tls {
    pub sni: String,
    pub insecure: bool,
    /// The hello's name as the link gives it; null leaves the core's own.
    pub fingerprint: Option<String>,
    pub alpn: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Vless {
    pub uuid: String,
    pub host: String,
    pub port: i32,
    pub sni: String,
    /// Reality's `pbk`, blank for ordinary TLS or none.
    pub public_key: String,
    /// Reality's `sid`.
    pub short_id: String,
    /// `fp` as the link gives it, `chrome` when it gives none.
    pub fingerprint: String,
    pub flow: Option<String>,
    pub transport: Transport,
    pub tag: String,
    /// `security=none`: no TLS at all.
    pub plain: bool,
    /// `allowInsecure=1` on ordinary TLS.
    pub insecure: bool,
    /// The link's `alpn`, read for WebSocket and HTTP upgrade over ordinary TLS only.
    pub alpn: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Hysteria2 {
    pub password: String,
    pub host: String,
    pub port: i32,
    pub sni: String,
    /// Salamander's password.
    pub obfs_password: Option<String>,
    pub insecure: bool,
    pub tag: String,
    /// The certificate's SHA-256 the operator published, in the link's spelling.
    pub cert_pin_sha256: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Trojan {
    pub password: String,
    pub host: String,
    pub port: i32,
    pub tls: Tls,
    pub transport: Transport,
    pub tag: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Shadowsocks {
    pub method: String,
    pub password: String,
    pub host: String,
    pub port: i32,
    pub tag: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Vmess {
    pub uuid: String,
    pub host: String,
    pub port: i32,
    pub alter_id: i32,
    /// The body cipher, `scy`.
    pub security: String,
    /// Null for no TLS.
    pub tls: Option<Tls>,
    pub transport: Transport,
    pub tag: String,
}

/// A SOCKS5 login: a username and a password.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SocksLogin {
    pub username: String,
    pub password: String,
}

impl SocksLogin {
    pub fn new(username: &str, password: &str) -> Self {
        SocksLogin {
            username: username.to_string(),
            password: password.to_string(),
        }
    }
}

/// What a core listens on: its own inbound and, while the tunnel is shared
/// with the local network, the LAN's.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Plan {
    pub own: Own,
    pub lan: Option<Lan>,
}

/// The core's own inbound, the one the session's traffic comes in by.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Own {
    /// The tun's (hev's): SOCKS5 on 127.0.0.1 demanding the session's login.
    Tunnel { port: i32, login: SocksLogin },
    /// Proxy mode's: SOCKS5 on a loopback address, demanding a login when
    /// there is one and open to the machine's programs when there is none.
    Proxy {
        port: i32,
        login: Option<SocksLogin>,
        listen: String,
    },
}

impl Own {
    pub fn port(&self) -> i32 {
        match self {
            Own::Tunnel { port, .. } | Own::Proxy { port, .. } => *port,
        }
    }
}

/// LAN sharing's inbound: SOCKS5 on one private IPv4 address of this
/// machine, demanding the login the devices there are given.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Lan {
    pub address: String,
    pub port: i32,
    pub login: SocksLogin,
}
