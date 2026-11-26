//! VMess AEAD, as v2rayN's share link gives it: over raw TCP, WebSocket,
//! gRPC or HTTP upgrade, each with ordinary TLS or with none.

use super::{not_mine, Line};
use crate::json::{Json, Object};
use crate::spec::{Spec, Transport};
use crate::support::{hello_name, put_tls, put_transport, upgrade_host_or, TlsOut};
use crate::text::{if_blank, trim};

pub struct Vmess;

const RAW: &str = "vmess-raw";
const WS: &str = "vmess-ws";
const GRPC: &str = "vmess-grpc";
const HTTP_UPGRADE: &str = "vmess-httpupgrade";
const RAW_TLS: &str = "vmess-raw-tls";
const WS_TLS: &str = "vmess-ws-tls";
const GRPC_TLS: &str = "vmess-grpc-tls";
const HTTP_UPGRADE_TLS: &str = "vmess-httpupgrade-tls";

/// The body ciphers the core speaks.
const CIPHERS: [&str; 3] = ["auto", "aes-128-gcm", "chacha20-poly1305"];

const LEGACY: &str = "a legacy VMess line (alterId above 0), which the core does not speak: it speaks VMess AEAD alone";
const CIPHER: &str = "a VMess body cipher other than auto, aes-128-gcm and chacha20-poly1305 (none, zero or a legacy one), \
     which the core does not speak";
const ID: &str =
    "a VMess id that is neither a UUID nor 1 to 30 bytes of text, which the core does not read";
const PORT: &str = "a port outside 1 to 65535";

fn own(spec: &Spec) -> &crate::spec::Vmess {
    match spec {
        Spec::Vmess(s) => s,
        _ => not_mine("Vmess"),
    }
}

/// The link's `scy` as the core reads it: trimmed, lower case, and empty as `auto`.
fn cipher(spec: &crate::spec::Vmess) -> String {
    let c = trim(&spec.security).to_lowercase();
    if c.is_empty() {
        "auto".to_string()
    } else {
        c
    }
}

/// Whether the core reads `id` as a user id, as Xray-core's
/// `uuid.ParseString` does: 1 to 30 bytes of any text, or 32 to 36 bytes
/// that are hex digits in groups of 8, 4, 4, 4 and 12, each after at most
/// one `-`, with whatever follows the last group ignored.
fn reads_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    if (1..=30).contains(&bytes.len()) {
        return true;
    }
    if !(32..=36).contains(&bytes.len()) {
        return false;
    }
    let mut at = 0;
    for group in [8usize, 4, 4, 4, 12] {
        if at < bytes.len() && bytes[at] == b'-' {
            at += 1;
        }
        if at + group > bytes.len() {
            return false;
        }
        if !bytes[at..at + group].iter().all(|b| b.is_ascii_hexdigit()) {
            return false;
        }
        at += group;
    }
    true
}

impl Line for Vmess {
    fn name(&self) -> &'static str {
        "Vmess"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[
            RAW,
            WS,
            GRPC,
            HTTP_UPGRADE,
            RAW_TLS,
            WS_TLS,
            GRPC_TLS,
            HTTP_UPGRADE_TLS,
        ]
    }

    /// By the transport and whether there is TLS; HTTP/2 and XHTTP are no shape of it.
    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        let Spec::Vmess(s) = spec else { return None };
        let tls = s.tls.is_some();
        match s.transport {
            Transport::Tcp => Some(if tls { RAW_TLS } else { RAW }),
            Transport::Ws { .. } => Some(if tls { WS_TLS } else { WS }),
            Transport::Grpc { .. } => Some(if tls { GRPC_TLS } else { GRPC }),
            Transport::HttpUpgrade { .. } => {
                Some(if tls { HTTP_UPGRADE_TLS } else { HTTP_UPGRADE })
            }
            _ => None,
        }
    }

    /// Refused: legacy VMess, a body cipher the core does not speak, an id it
    /// cannot read, and a port that is none.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        let why = if s.alter_id > 0 {
            LEGACY
        } else if !CIPHERS.contains(&cipher(s).as_str()) {
            CIPHER
        } else if !reads_id(&s.uuid) {
            ID
        } else if !(1..=65535).contains(&s.port) {
            PORT
        } else {
            return None;
        };
        Some(why.to_string())
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        let s = own(spec);
        let name = s.host.as_str();
        Spec::Vmess(crate::spec::Vmess {
            host: address.to_string(),
            tls: s.tls.as_ref().map(|t| {
                let mut t = t.clone();
                t.sni = if_blank(&t.sni, name);
                t
            }),
            transport: upgrade_host_or(&s.transport, name),
            ..s.clone()
        })
    }

    /// One server and one user, with no alterId and the link's cipher; TLS
    /// as the link gives it.
    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        out.put("protocol", "vmess");
        let mut user = Object::new();
        user.put("id", &s.uuid);
        user.put("security", cipher(s));
        let mut server = Object::new();
        server.put("address", &s.host);
        server.put("port", s.port);
        server.put("users", Json::Array(vec![user.into()]));
        let mut settings = Object::new();
        settings.put("vnext", Json::Array(vec![server.into()]));
        out.put("settings", settings);
        let mut stream = Object::new();
        put_transport(&s.transport, &mut stream);
        if let Some(tls) = &s.tls {
            put_tls(
                TlsOut {
                    server_name: &tls.sni,
                    fingerprint: Some(hello_name(tls.fingerprint.as_deref())),
                    alpn: &tls.alpn,
                    insecure: tls.insecure,
                    ..TlsOut::default()
                },
                &mut stream,
            );
        }
        out.put("streamSettings", stream);
    }
}
