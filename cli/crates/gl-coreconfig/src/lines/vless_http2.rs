//! VLESS over HTTP/2 (`type=http`, or `h2`), over ordinary TLS or over
//! Reality. The core reads `network: "http"` with `httpSettings` and runs it
//! as XHTTP in stream-one mode. With no TLS the link is this kind's and is
//! refused, and so is one with a flow.

use super::{not_mine, Line};
use crate::json::{strings, Object};
use crate::spec::{Spec, Transport, Vless};
use crate::support::{hello_name, pin_vless, put_reality, put_tls, put_vless, TlsOut};
use crate::text::is_blank;

pub struct VlessHttp2;

const TLS: &str = "vless-http2-tls";
const REALITY: &str = "vless-http2-reality";
/// The shape of a link over HTTP/2 with no TLS: this kind's, never written.
const NONE: &str = "vless-http2-none";

const NEEDS_TLS: &str = "the link asks for HTTP/2 (type=http) with no TLS (security=none), \
                         and the core carries HTTP/2 only inside TLS or Reality";
const NO_FLOW: &str =
    "the link asks for Vision over HTTP/2 (type=http), and Vision runs only on raw TCP";

fn own(spec: &Spec) -> &Vless {
    match spec {
        Spec::Vless(s) => s,
        _ => not_mine("VlessHttp2"),
    }
}

impl Line for VlessHttp2 {
    fn name(&self) -> &'static str {
        "VlessHttp2"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[TLS, REALITY]
    }

    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        let Spec::Vless(s) = spec else { return None };
        if !matches!(s.transport, Transport::Http { .. }) {
            return None;
        }
        Some(if !is_blank(&s.public_key) {
            REALITY
        } else if s.plain {
            NONE
        } else {
            TLS
        })
    }

    /// Refused: no TLS, a flow, and for a Reality line waived certificate
    /// checks and no server name.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        let reality = !is_blank(&s.public_key);
        if s.plain && !reality {
            return Some(NEEDS_TLS.to_string());
        }
        if s.flow.is_some() {
            return Some(NO_FLOW.to_string());
        }
        if reality && s.insecure {
            return Some(crate::WAIVED_CHECKS.to_string());
        }
        (reality && is_blank(&s.sni)).then(|| "a Reality line with no server name".to_string())
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        Spec::Vless(pin_vless(own(spec), address))
    }

    /// `network: "http"` and `httpSettings` (`host` when the link names
    /// hosts, and `path`), then ordinary TLS or Reality.
    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        let Transport::Http { path, hosts } = &s.transport else {
            not_mine("VlessHttp2")
        };
        put_vless(s, out);
        let mut stream = Object::new();
        stream.put("network", "http");
        let mut settings = Object::new();
        if !hosts.is_empty() {
            settings.put("host", strings(hosts));
        }
        settings.put("path", path);
        stream.put("httpSettings", settings);
        if is_blank(&s.public_key) {
            put_tls(
                TlsOut {
                    server_name: &s.sni,
                    fingerprint: Some(hello_name(Some(&s.fingerprint))),
                    insecure: s.insecure,
                    ..TlsOut::default()
                },
                &mut stream,
            );
        } else {
            put_reality(
                &s.sni,
                &super::reality_hello(Some(&s.fingerprint)),
                &s.public_key,
                &s.short_id,
                &mut stream,
            );
        }
        out.put("streamSettings", stream);
    }
}
