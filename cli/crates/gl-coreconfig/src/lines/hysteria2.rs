//! Hysteria2, with or without Salamander.

use super::{not_mine, Line};
use crate::json::{Json, Object};
use crate::spec::Spec;
use crate::support::{pin, put_tls, TlsOut};
use crate::text::if_blank;

pub struct Hysteria2;

const SLUG: &str = "hysteria2";

fn own(spec: &Spec) -> &crate::spec::Hysteria2 {
    match spec {
        Spec::Hysteria2(s) => s,
        _ => not_mine("Hysteria2"),
    }
}

impl Line for Hysteria2 {
    fn name(&self) -> &'static str {
        "Hysteria2"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[SLUG]
    }

    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        matches!(spec, Spec::Hysteria2(_)).then_some(SLUG)
    }

    /// Refused: a pin that is not a SHA-256 in hex, a password the core will
    /// not send, and a Salamander password outside 4 to 4096 bytes. A link
    /// asked to skip verification with no pin is carried.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        let auth = &s.password;
        let why = if s
            .cert_pin_sha256
            .as_deref()
            .is_some_and(|p| pin(p).is_none())
        {
            "the certificate pin is not a SHA-256 in hex"
        } else if auth.is_empty()
            || auth.len() > 4096
            || auth.chars().any(|c| c < ' ' || c == '\u{7f}')
        {
            "a Hysteria2 password the core does not send"
        } else if s
            .obfs_password
            .as_ref()
            .is_some_and(|o| !(4..=4096).contains(&o.len()))
        {
            "a Salamander password outside 4 to 4096 bytes"
        } else {
            return None;
        };
        Some(why.to_string())
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        let s = own(spec);
        Spec::Hysteria2(crate::spec::Hysteria2 {
            host: address.to_string(),
            sni: if_blank(&s.sni, &s.host),
            ..s.clone()
        })
    }

    /// The core's `hysteria` protocol at version 2 over its QUIC transport
    /// with TLS: no fingerprint, no ALPN, a published pin verified and
    /// `insecure=1` written only when there is none. Under Salamander the
    /// Chrome parrot is off.
    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        out.put("protocol", "hysteria");
        let mut settings = Object::new();
        settings.put("version", 2);
        settings.put("address", &s.host);
        settings.put("port", s.port);
        out.put("settings", settings);

        let mut stream = Object::new();
        stream.put("network", "hysteria");
        put_tls(
            TlsOut {
                server_name: &s.sni,
                pin: s
                    .cert_pin_sha256
                    .as_deref()
                    .map(|p| pin(p).expect("a refused pin is never written")),
                insecure: s.insecure && s.cert_pin_sha256.is_none(),
                ..TlsOut::default()
            },
            &mut stream,
        );
        let mut hysteria = Object::new();
        hysteria.put("version", 2);
        hysteria.put("auth", &s.password);
        stream.put("hysteriaSettings", hysteria);
        if let Some(obfs) = &s.obfs_password {
            let mut salamander_settings = Object::new();
            salamander_settings.put("password", obfs);
            let mut salamander = Object::new();
            salamander.put("type", "salamander");
            salamander.put("settings", salamander_settings);
            let mut quic = Object::new();
            quic.put("disableChromeParrot", true);
            let mut mask = Object::new();
            mask.put("udp", Json::Array(vec![salamander.into()]));
            mask.put("quicParams", quic);
            stream.put("finalmask", mask);
        }
        out.put("streamSettings", stream);
    }
}
