//! VLESS over Reality: on raw TCP with Vision or without a flow, over gRPC,
//! and over XHTTP.

use super::{not_mine, Line};
use crate::json::Object;
use crate::spec::{Spec, Transport, Vless};
use crate::support::{flow_refusal, hello_name, pin_vless, put_reality, put_transport, put_vless};
use crate::text::is_blank;

pub struct Reality;

const RAW: &str = "reality";
const GRPC: &str = "grpc-reality";
const XHTTP: &str = "xhttp";

/// The hello names the core has no REALITY form of (no X25519 share): the
/// line is carried with the Chrome hello instead.
const NO_REALITY_HELLO: [&str; 2] = ["android", "360"];

fn own(spec: &Spec) -> &Vless {
    match spec {
        Spec::Vless(s) => s,
        _ => not_mine("Reality"),
    }
}

/// A link's `fp` as `realitySettings` takes it: [`hello_name`], with android
/// and 360 given the Chrome hello.
pub fn hello(fp: Option<&str>) -> String {
    let name = hello_name(fp);
    if NO_REALITY_HELLO.contains(&name.as_str()) {
        "chrome".to_string()
    } else {
        name
    }
}

/// The sentence for the log when a Reality line's hello was swapped for
/// Chrome's, or None (for every other line too). It names the hello and
/// nothing of the link.
pub fn hello_note(spec: &Spec) -> Option<String> {
    Reality.shape(spec)?;
    let asked = hello_name(Some(&own(spec).fingerprint));
    NO_REALITY_HELLO.contains(&asked.as_str()).then(|| {
        format!("the link asks for the {asked} hello, which the core has no REALITY form of: the Chrome hello is used instead")
    })
}

impl Line for Reality {
    fn name(&self) -> &'static str {
        "Reality"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[RAW, GRPC, XHTTP]
    }

    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        let Spec::Vless(s) = spec else { return None };
        if s.plain || is_blank(&s.public_key) {
            return None;
        }
        match s.transport {
            Transport::Tcp => Some(RAW),
            Transport::Grpc { .. } => Some(GRPC),
            Transport::Xhttp { .. } => Some(XHTTP),
            _ => None,
        }
    }

    /// Refused: waived certificate checks, a flow other than Vision, and no
    /// server name.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        if s.insecure {
            return Some(crate::WAIVED_CHECKS.to_string());
        }
        if let Some(why) = flow_refusal(s.flow.as_deref()) {
            return Some(why);
        }
        is_blank(&s.sni).then(|| "a Reality line with no server name".to_string())
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        Spec::Vless(pin_vless(own(spec), address))
    }

    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        put_vless(s, out);
        let mut stream = Object::new();
        put_transport(&s.transport, &mut stream);
        put_reality(
            &s.sni,
            &hello(Some(&s.fingerprint)),
            &s.public_key,
            &s.short_id,
            &mut stream,
        );
        out.put("streamSettings", stream);
    }
}
