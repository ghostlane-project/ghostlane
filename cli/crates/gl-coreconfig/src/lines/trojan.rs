//! Trojan over raw TCP, WebSocket, gRPC and HTTP upgrade, always with
//! ordinary TLS. Waived certificate checks are carried, and written as the
//! link asks.

use super::{not_mine, Line};
use crate::json::{Json, Object};
use crate::spec::{Spec, Transport};
use crate::support::{hello_name, put_tls, put_transport, upgrade_host_or, TlsOut};
use crate::text::if_blank;

pub struct Trojan;

const RAW: &str = "trojan";
const WS: &str = "trojan-ws";
const GRPC: &str = "trojan-grpc";
const HTTP_UPGRADE: &str = "trojan-httpupgrade";

/// Trojan over HTTP/2: a shape of this kind's links that it does not write.
const HTTP: &str = "trojan-http";

const MAX_PASSWORD_BYTES: usize = 4096;

/// Xray's name for a value taken from its MITM inbound.
const FROM_MITM: &str = "frommitm";

fn own(spec: &Spec) -> &crate::spec::Trojan {
    match spec {
        Spec::Trojan(s) => s,
        _ => not_mine("Trojan"),
    }
}

impl Line for Trojan {
    fn name(&self) -> &'static str {
        "Trojan"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[RAW, WS, GRPC, HTTP_UPGRADE]
    }

    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        let Spec::Trojan(s) = spec else { return None };
        match s.transport {
            Transport::Tcp => Some(RAW),
            Transport::Ws { .. } => Some(WS),
            Transport::Grpc { .. } => Some(GRPC),
            Transport::HttpUpgrade { .. } => Some(HTTP_UPGRADE),
            Transport::Http { .. } => Some(HTTP),
            // The link parser gives a Trojan link no XHTTP.
            Transport::Xhttp { .. } => None,
        }
    }

    /// Refused: HTTP/2, a password the core does not send, a port it cannot
    /// dial, and `fromMitm` as the server name or an ALPN.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        let why = if matches!(s.transport, Transport::Http { .. }) {
            "the link asks for HTTP/2 (type=http), a transport the core does not have"
        } else if s.password.is_empty() || s.password.len() > MAX_PASSWORD_BYTES {
            "a Trojan password the core does not send (it sends 1 to 4096 bytes)"
        } else if !(1..=65535).contains(&s.port) {
            "the port is not one from 1 to 65535"
        } else if s.tls.sni.to_lowercase() == FROM_MITM
            || s.tls.alpn.iter().any(|a| a.to_lowercase() == FROM_MITM)
        {
            "the link asks for fromMitm, which only Xray's MITM inbound gives"
        } else {
            return None;
        };
        Some(why.to_string())
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        let s = own(spec);
        let name = s.host.as_str();
        let mut tls = s.tls.clone();
        tls.sni = if_blank(&s.tls.sni, name);
        Spec::Trojan(crate::spec::Trojan {
            host: address.to_string(),
            tls,
            transport: upgrade_host_or(&s.transport, name),
            ..s.clone()
        })
    }

    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        out.put("protocol", "trojan");
        let mut server = Object::new();
        server.put("address", &s.host);
        server.put("port", s.port);
        server.put("password", &s.password);
        let mut settings = Object::new();
        settings.put("servers", Json::Array(vec![server.into()]));
        out.put("settings", settings);
        let mut stream = Object::new();
        put_transport(&s.transport, &mut stream);
        put_tls(
            TlsOut {
                server_name: &s.tls.sni,
                fingerprint: Some(hello_name(s.tls.fingerprint.as_deref())),
                alpn: &s.tls.alpn,
                insecure: s.tls.insecure,
                ..TlsOut::default()
            },
            &mut stream,
        );
        out.put("streamSettings", stream);
    }
}
