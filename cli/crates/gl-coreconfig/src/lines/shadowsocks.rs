//! Shadowsocks over TCP and UDP: the AEAD ciphers and the 2022 ones, each
//! its own shape. No plugin and no stream cipher.

use super::{not_mine, Line};
use crate::json::Object;
use crate::spec::Spec;
use crate::text::trim;

pub struct Shadowsocks;

const AEAD: &str = "shadowsocks";
const AEAD_2022: &str = "shadowsocks-2022";

/// The AEAD methods the core takes, by the names the app's links use.
const AEAD_METHODS: [&str; 4] = [
    "aes-128-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "xchacha20-ietf-poly1305",
];

/// The 2022 methods and the length in bytes of the key each takes.
const KEY_BYTES: [(&str, i64); 3] = [
    ("2022-blake3-aes-128-gcm", 16),
    ("2022-blake3-aes-256-gcm", 32),
    ("2022-blake3-chacha20-poly1305", 32),
];

/// At most this many identity keys, and a password of at most this many bytes.
const MAX_KEYS: usize = 8;
const MAX_PASSWORD_BYTES: usize = 8192;

const ALPHABET: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn own(spec: &Spec) -> &crate::spec::Shadowsocks {
    match spec {
        Spec::Shadowsocks(s) => s,
        _ => not_mine("Shadowsocks"),
    }
}

fn method(spec: &crate::spec::Shadowsocks) -> String {
    trim(&spec.method).to_lowercase()
}

impl Line for Shadowsocks {
    fn name(&self) -> &'static str {
        "Shadowsocks"
    }

    fn shapes(&self) -> &'static [&'static str] {
        &[AEAD, AEAD_2022]
    }

    fn shape(&self, spec: &Spec) -> Option<&'static str> {
        let Spec::Shadowsocks(s) = spec else {
            return None;
        };
        Some(if method(s).starts_with("2022-") {
            AEAD_2022
        } else {
            AEAD
        })
    }

    /// Refused: a method the core does not speak, and a 2022 key it would
    /// not take. The key's own bytes are never quoted.
    fn refusal(&self, spec: &Spec) -> Option<String> {
        let s = own(spec);
        let method = method(s);
        if AEAD_METHODS.contains(&method.as_str()) {
            return s
                .password
                .is_empty()
                .then(|| "a Shadowsocks password the core does not send".to_string());
        }
        if method == "aes-192-gcm" {
            return Some(
                "the Shadowsocks method aes-192-gcm, which the core does not speak".to_string(),
            );
        }
        match KEY_BYTES.iter().find(|(m, _)| *m == method) {
            Some((_, want)) => key_refusal(&method, *want, &s.password),
            None => Some(format!(
                "the Shadowsocks cipher {method} is a stream cipher or one the core does not speak: \
                 it speaks the AEAD ciphers and the 2022 ones"
            )),
        }
    }

    fn pin_host(&self, spec: &Spec, address: &str) -> Spec {
        own(spec);
        spec.with_host(address)
    }

    /// The core's `shadowsocks` protocol with one flattened server, over its
    /// default stream.
    fn put_outbound(&self, spec: &Spec, out: &mut Object) {
        let s = own(spec);
        out.put("protocol", "shadowsocks");
        let mut settings = Object::new();
        settings.put("address", &s.host);
        settings.put("port", s.port);
        settings.put("method", method(s));
        settings.put("password", &s.password);
        out.put("settings", settings);
    }
}

fn key_refusal(method: &str, want: i64, password: &str) -> Option<String> {
    if password.is_empty() || password.len() > MAX_PASSWORD_BYTES {
        return Some(format!(
            "a Shadowsocks 2022 key the core does not take: empty or over {MAX_PASSWORD_BYTES} bytes"
        ));
    }
    let keys: Vec<&str> = password.split(':').collect();
    if keys.len() > MAX_KEYS {
        return Some(format!(
            "a Shadowsocks 2022 password of more than {MAX_KEYS} identity keys"
        ));
    }
    if method == "2022-blake3-chacha20-poly1305" && keys.len() != 1 {
        return Some(
            "a Shadowsocks 2022 ChaCha20 password with identity keys, which that cipher does not take".to_string(),
        );
    }
    if keys.iter().any(|k| decoded_bytes(k) != want) {
        return Some(format!(
            "a Shadowsocks 2022 key that is not standard base64 of {want} bytes, as {method} takes"
        ));
    }
    None
}

/// The length in bytes of `key` read as standard base64 with its padding
/// and no stray bits, as the core reads it, or -1 when it is not that.
fn decoded_bytes(key: &str) -> i64 {
    if key.is_empty() || !key.len().is_multiple_of(4) {
        return -1;
    }
    let pad = key.len() - key.trim_end_matches('=').len();
    if pad > 2 {
        return -1;
    }
    let body = &key[..key.len() - pad];
    if !body.chars().all(|c| ALPHABET.contains(c)) {
        return -1;
    }
    let spare = match pad {
        1 => 2,
        2 => 4,
        _ => 0,
    };
    if spare > 0 {
        let last = body
            .chars()
            .last()
            .and_then(|c| ALPHABET.find(c))
            .unwrap_or(0);
        if last & ((1 << spare) - 1) != 0 {
            return -1;
        }
    }
    (key.len() / 4 * 3 - pad) as i64
}
