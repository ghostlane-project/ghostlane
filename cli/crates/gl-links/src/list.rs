//! Subscription lists: body decoding, entries, country groups, selection and
//! the headers a list host sends, as the CLI's Go `list.go` and `fetch.go`
//! read them.

use std::collections::HashMap;

use crate::crypt1;
use crate::import::country_of;
use crate::olcrtc;
use crate::share;
use crate::text::{ascii_header, one_line, url_decode};

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Unsupported,
    Olcrtc,
    Vless,
    Hysteria2,
}

impl Kind {
    pub fn name(self) -> &'static str {
        match self {
            Kind::Unsupported => "unsupported",
            Kind::Olcrtc => "olcrtc",
            Kind::Vless => "vless",
            Kind::Hysteria2 => "hysteria2",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub id: String,
    pub label: String,
    pub country: String,
    pub kind: Kind,
    pub raw: String,
    pub problem: String,
}

impl Entry {
    pub fn connectable(&self) -> bool {
        self.problem.is_empty() && matches!(self.kind, Kind::Olcrtc | Kind::Vless | Kind::Hysteria2)
    }
}

/// A decryptor for crypt1 blobs, borrowed for one decode.
pub type Decryptor<'a> = &'a dyn Fn(&str) -> Option<Vec<u8>>;

/// A base64 body is decoded only when the decode names a scheme; base64-shaped
/// plaintext stays plaintext. A crypt1 blob is offered to the decryptor only
/// when the body has no scheme in it (its MAC, so a base64 list never passes).
pub fn decode_body(body: &[u8]) -> Vec<String> {
    decode_body_with(body, None)
}

pub fn decode_body_with(body: &[u8], decrypt: Option<Decryptor<'_>>) -> Vec<String> {
    let text = String::from_utf8_lossy(body).into_owned();
    let compact: String = text
        .chars()
        .filter(|c| !matches!(c, '\n' | '\r' | ' ' | '\t'))
        .collect();
    let mut text = text;
    if !text.contains("://") {
        let mut decrypted = false;
        if let Some(dec) = decrypt {
            if let Some(plain) = dec(&compact) {
                text = String::from_utf8_lossy(&plain).into_owned();
                decrypted = true;
            }
        }
        if !decrypted {
            if let Some(decoded) = try_base64(&compact) {
                if decoded.contains("://") {
                    text = decoded;
                }
            }
        }
    }
    text.replace("\r\n", "\n")
        .split('\n')
        .map(|line| line.trim().to_string())
        .filter(|line| !line.is_empty())
        .collect()
}

fn try_base64(s: &str) -> Option<String> {
    use base64::Engine;
    if s.is_empty() {
        return None;
    }
    for enc in [
        base64::engine::general_purpose::STANDARD,
        base64::engine::general_purpose::STANDARD_NO_PAD,
        base64::engine::general_purpose::URL_SAFE,
        base64::engine::general_purpose::URL_SAFE_NO_PAD,
    ] {
        if let Ok(b) = enc.decode(s) {
            if let Ok(decoded) = String::from_utf8(b) {
                return Some(decoded);
            }
        }
    }
    None
}

/// A decryptor for crypt1 blobs, built from a master key, for `decode_body_with`.
pub fn crypt1_decryptor(master: [u8; 32]) -> impl Fn(&str) -> Option<Vec<u8>> {
    move |blob| crypt1::decrypt(&master, blob)
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct UserInfo {
    pub upload: i64,
    pub download: i64,
    pub total: i64,
    pub expire: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Headers {
    pub title: String,
    pub update_interval_hours: i32,
    pub user_info: Option<UserInfo>,
    pub support_url: String,
    pub web_page_url: String,
    pub announce: String,
    pub refusal: String,
}

/// A list's headers, from the map the HTTP client read (lowercase names).
pub fn parse_headers(h: &[(&str, &str)]) -> Headers {
    let get = |key: &str| {
        h.iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(key))
            .map(|(_, v)| v.trim().to_string())
            .unwrap_or_default()
    };
    let mut out = Headers {
        update_interval_hours: 24,
        title: one_line(&maybe_base64(&get("Profile-Title")), 200),
        announce: maybe_base64(&get("Announce")),
        support_url: get("Support-Url").trim().to_string(),
        web_page_url: get("Profile-Web-Page-Url").trim().to_string(),
        ..Default::default()
    };
    let flag = |name: &str| get(name).eq_ignore_ascii_case("true");
    if flag("X-Hwid-Max-Devices-Reached") {
        out.refusal = "limit".to_string();
    } else if flag("X-Hwid-Not-Supported") {
        out.refusal = "no-device".to_string();
    }
    if let Ok(n) = get("Profile-Update-Interval").parse::<i32>() {
        if n > 0 {
            out.update_interval_hours = n;
        }
    }
    let ui = get("Subscription-Userinfo");
    if !ui.is_empty() {
        let mut info = UserInfo::default();
        for part in ui.split(';') {
            let (k, v) = match part.split_once('=') {
                Some((k, v)) => (k.trim().to_lowercase(), v.trim()),
                None => continue,
            };
            let n = match v.parse::<i64>() {
                Ok(n) => n,
                Err(_) => continue,
            };
            match k.as_str() {
                "upload" => info.upload = n,
                "download" => info.download = n,
                "total" => info.total = n,
                "expire" => info.expire = n,
                _ => {}
            }
        }
        out.user_info = Some(info);
    }
    out
}

fn maybe_base64(v: &str) -> String {
    let v = v.trim();
    if let Some(rest) = v
        .strip_prefix("base64:")
        .or_else(|| v.strip_prefix("BASE64:"))
    {
        if let Some(decoded) = try_base64(rest.trim()) {
            return decoded.trim().to_string();
        }
    }
    // Mixed case, as the app reads it.
    if v.get(..7)
        .map(|p| p.eq_ignore_ascii_case("base64:"))
        .unwrap_or(false)
    {
        if let Some(decoded) = try_base64(v[7..].trim()) {
            return decoded.trim().to_string();
        }
    }
    v.to_string()
}

/// The words a device refusal is told with, in the app's words.
pub fn refusal_text(kind: &str, announce: &str) -> String {
    let a = announce.trim();
    let has_prefix = a
        .get(..7)
        .map(|p| p.eq_ignore_ascii_case("base64:"))
        .unwrap_or(false);
    if !a.is_empty() && a != "0" && !has_prefix {
        let one = one_line(a, 200);
        if !one.is_empty() {
            return one;
        }
    }
    if kind == "no-device" {
        return "The provider could not read this device's id. Refresh the list; if it stays so, ask your provider.".to_string();
    }
    "This list has no place for this device. Ask your provider.".to_string()
}

fn fragment_label(raw: &str) -> String {
    if let Some((_, frag)) = raw.rsplit_once('#') {
        let decoded = url_decode(frag);
        let label = decoded.trim();
        if !label.is_empty() {
            return label.to_string();
        }
    }
    raw.to_string()
}

fn entry_id(sub_url: &str, label: &str, ordinal: usize) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(format!("{sub_url}\0{label}\0{ordinal}").as_bytes());
    digest[..6].iter().map(|b| format!("{b:02x}")).collect()
}

pub fn entries(sub_url: &str, lines: &[String]) -> Vec<Entry> {
    let mut seen: HashMap<String, usize> = HashMap::new();
    let mut out = Vec::with_capacity(lines.len());
    for raw in lines {
        let (kind, problem, label) = if raw.starts_with("olcrtc://") {
            match olcrtc::parse_olcrtc(raw) {
                Ok(l) => (Kind::Olcrtc, String::new(), l.label),
                Err(e) => (Kind::Unsupported, e.text(), raw.clone()),
            }
        } else if raw.starts_with("vless://") {
            let mut label = fragment_label(raw);
            match share::parse_vless(raw) {
                Ok(l) => {
                    label = l.label;
                    (Kind::Vless, String::new(), label)
                }
                Err(e) => (Kind::Vless, e.text(), label),
            }
        } else if raw.starts_with("hysteria2://") || raw.starts_with("hy2://") {
            let mut label = fragment_label(raw);
            match share::parse_hy2(raw) {
                Ok(l) => {
                    label = l.label;
                    (Kind::Hysteria2, String::new(), label)
                }
                Err(e) => (Kind::Hysteria2, e.text(), label),
            }
        } else {
            let scheme = raw.split_once("://").map(|(s, _)| s).unwrap_or(raw);
            (
                Kind::Unsupported,
                format!("{scheme}:// is not supported"),
                raw.clone(),
            )
        };
        // The country is read from the label; an unsupported line's label is
        // the raw line, whose scheme gives no country.
        let country = country_of(&label);
        let ordinal = seen.entry(label.clone()).or_insert(0);
        *ordinal += 1;
        let id = entry_id(sub_url, &label, *ordinal);
        out.push(Entry {
            id,
            label,
            country,
            kind,
            raw: raw.clone(),
            problem,
        });
    }
    out
}

#[derive(Debug, Clone)]
pub struct Group {
    pub country: String,
    pub entries: Vec<Entry>,
}

pub fn group_by_country(entries: &[Entry]) -> Vec<Group> {
    let mut out: Vec<Group> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    for entry in entries {
        if entry.country.is_empty() {
            continue;
        }
        let i = match index.get(&entry.country) {
            Some(&i) => i,
            None => {
                let i = out.len();
                index.insert(entry.country.clone(), i);
                out.push(Group {
                    country: entry.country.clone(),
                    entries: Vec::new(),
                });
                i
            }
        };
        out[i].entries.push(entry.clone());
    }
    out
}

/// Select by country, exact label or 1-based index. Nothing is guessed.
pub fn select(entries: &[Entry], selector: &str) -> Result<Vec<Entry>, String> {
    let sel = selector.trim();
    if sel.is_empty() {
        return Err("no such location: empty selector".to_string());
    }
    if let Ok(n) = sel.parse::<usize>() {
        if n == 0 || n > entries.len() {
            return Err(format!(
                "no such location: index {n}, the list has {} entries",
                entries.len()
            ));
        }
        return Ok(vec![entries[n - 1].clone()]);
    }
    let by_label: Vec<Entry> = entries.iter().filter(|e| e.label == sel).cloned().collect();
    if by_label.len() == 1 {
        return Ok(by_label);
    }
    if by_label.len() > 1 {
        return Err(format!(
            "no such location: {} entries are named \"{sel}\", pick one by index",
            by_label.len()
        ));
    }
    let groups = group_by_country(entries);
    for g in &groups {
        if g.country.eq_ignore_ascii_case(sel) {
            return Ok(g.entries.clone());
        }
    }
    let countries: Vec<&str> = groups.iter().map(|g| g.country.as_str()).collect();
    Err(format!(
        "no such location: \"{sel}\"; countries: {}; or a label or an index from `ghostlane list`",
        countries.join(" ")
    ))
}

/// The device fields a fetch sends: printable ASCII, at most 64 characters.
pub fn device_headers(
    id: &str,
    os: &str,
    os_version: &str,
    model: &str,
) -> Vec<(&'static str, String)> {
    [
        ("x-hwid", id),
        ("x-device-os", os),
        ("x-ver-os", os_version),
        ("x-device-model", model),
    ]
    .into_iter()
    .filter_map(|(name, value)| {
        let v = ascii_header(value);
        if v.is_empty() {
            None
        } else {
            Some((name, v))
        }
    })
    .collect()
}
