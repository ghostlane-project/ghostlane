//! WebTunnel: VLESS over WebSocket or over HTTP upgrade, with ordinary TLS.

use super::{not_mine, Line};
use crate::json::Object;
use crate::spec::{Spec, Transport, Vless};
use crate::support::{
    flow_refusal, hello_name, pin_vless, put_tls, put_transport, put_vless, TlsOut,
};
use crate::text::is_blank;

pub struct WebTunnel;

const WS: &str = "webtunnel-ws";
const HTTP_UPGRADE: &str = "webtunnel-httpupgrade";

fn own(spec: &Spec) -> &Vless {
    match spec {
        Spec::Vless(s) => s,
        _ => not_mine("WebTunnel"),
    }
}

impl Line for WebTunnel {
    fn name(&self) -> &'static str {
        "WebTunnel"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[WS, HTTP_UPGRADE]
    }

    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        let Spec::Vless(s) = spec else { return None };
        if s.plain || !is_blank(&s.public_key) {
            return None;
        }
        match s.transport {
            Transport::Ws { .. } => Some(WS),
            Transport::HttpUpgrade { .. } => Some(HTTP_UPGRADE),
            _ => None,
        }
    }

    /// Refused: `allowInsecure` (a pin is the way to trust a self-signed
    /// certificate) and a flow other than Vision.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        if s.insecure {
            Some(crate::WAIVED_CHECKS.to_string())
        } else {
            flow_refusal(s.flow.as_deref())
        }
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        Spec::Vless(pin_vless(own(spec), address))
    }

    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        put_vless(s, out);
        let mut stream = Object::new();
        put_transport(&s.transport, &mut stream);
        put_tls(
            TlsOut {
                server_name: &s.sni,
                fingerprint: Some(hello_name(Some(&s.fingerprint))),
                alpn: &s.alpn,
                ..TlsOut::default()
            },
            &mut stream,
        );
        out.put("streamSettings", stream);
    }
}
