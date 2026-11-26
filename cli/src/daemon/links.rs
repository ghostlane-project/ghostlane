//! The slice of the link parser the daemon needs: a line becomes an entry, a
//! selector becomes entries, and a list body becomes lines. This is the
//! daemon's own reading of the same grammar C2 ports and holds to Kotlin; it
//! keeps C3 free of a dependency on a branch that has not landed here yet.

use std::collections::BTreeMap;

use base64::Engine as _;
use sha2::{Digest as _, Sha256};

use crate::store::{self, Cache, Headers};

/// A machine as a list host sees it: its operating system and the id the
/// daemon reads from the engine's device-id file.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Device {
    pub id: String,
    pub os: String,
    pub os_version: String,
    pub model: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Unsupported,
    Olcrtc,
    Vless,
    Hysteria2,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Unsupported => "unsupported",
            Kind::Olcrtc => "olcrtc",
            Kind::Vless => "vless",
            Kind::Hysteria2 => "hysteria2",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OlcrtcLine {
    pub provider: String,
    pub transport: String,
    pub room: String,
    pub key: String,
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VlessLine {
    pub host: String,
    pub port: u16,
    pub transport: String,
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Hy2Line {
    pub host: String,
    pub port: u16,
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub id: String,
    pub label: String,
    pub country: String,
    pub kind: Kind,
    pub raw: String,
    pub olcrtc: Option<OlcrtcLine>,
    pub vless: Option<VlessLine>,
    pub hy2: Option<Hy2Line>,
    pub problem: String,
}

impl Entry {
    pub fn connectable(&self) -> bool {
        self.problem.is_empty()
            && (self.olcrtc.is_some() || self.vless.is_some() || self.hy2.is_some())
    }
}

pub const REFUSAL_NONE: &str = "";
pub const REFUSAL_LIMIT: &str = "limit";
pub const REFUSAL_NO_DEVICE: &str = "no-device";

/// The sentence a host's refusal carries when it sent no announcement.
pub fn refusal_text(kind: &str, announce: &str) -> String {
    if kind.is_empty() {
        return String::new();
    }
    if !announce.trim().is_empty() {
        return announce.trim().to_string();
    }
    match kind {
        REFUSAL_LIMIT => {
            "This list has no place for this device. Ask your provider.".to_string()
        }
        REFUSAL_NO_DEVICE => "The provider could not read this device's id. Refresh the list; if it stays so, ask your provider.".to_string(),
        other => other.to_string(),
    }
}

/// A fetch either answered or refused this device. A refusal is an answer:
/// the daemon keeps an empty record with the host's headers.
#[derive(Debug)]
pub enum FetchError {
    Refused {
        kind: String,
        announce: String,
        headers: Box<Headers>,
    },
    Other(String),
}

impl FetchError {
    pub fn other(error: impl std::fmt::Display) -> FetchError {
        FetchError::Other(error.to_string())
    }
}

impl std::fmt::Display for FetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FetchError::Refused { kind, announce, .. } => {
                write!(f, "device refused: {}", refusal_text(kind, announce))
            }
            FetchError::Other(why) => f.write_str(why),
        }
    }
}

/// A body may be base64 as a whole, or encrypted when the build has a key.
/// The decryptor a build has, borrowed for one body.
pub type DecryptRef<'a> = Option<&'a dyn Fn(&str) -> Option<Vec<u8>>>;

pub fn decode_body(body: &[u8], decrypt: DecryptRef<'_>) -> Vec<String> {
    let text = String::from_utf8_lossy(body).into_owned();
    let compact: String = text
        .chars()
        .filter(|c| !matches!(c, '\n' | '\r' | ' ' | '\t'))
        .collect();
    let plain = if text.contains("://") {
        text
    } else if let Some(verified) = decrypt.and_then(|d| d(&compact)) {
        String::from_utf8_lossy(&verified).into_owned()
    } else if let Some(decoded) = base64_text(&compact) {
        decoded
    } else {
        text
    };
    plain
        .replace("\r\n", "\n")
        .split('\n')
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(str::to_string)
        .collect()
}

fn base64_text(text: &str) -> Option<String> {
    if text.is_empty() {
        return None;
    }
    for alphabet in [
        base64::engine::general_purpose::STANDARD,
        base64::engine::general_purpose::STANDARD_NO_PAD,
        base64::engine::general_purpose::URL_SAFE,
        base64::engine::general_purpose::URL_SAFE_NO_PAD,
    ] {
        if let Ok(bytes) = alphabet.decode(text) {
            if let Ok(decoded) = String::from_utf8(bytes) {
                if decoded.contains("://") {
                    return Some(decoded);
                }
            }
        }
    }
    None
}

/// A leading regional-indicator pair is a flag, not the country's code.
pub fn country_of(label: &str) -> String {
    let mut text = label.trim();
    loop {
        let Some(ch) = text.chars().next() else {
            return String::new();
        };
        if !('\u{1f1e6}'..='\u{1f1ff}').contains(&ch) {
            break;
        }
        text = text[ch.len_utf8()..].trim_start();
    }
    let first = text.split([' ', '|']).next().unwrap_or("").trim();
    if first.len() == 2 && first.bytes().all(|b| b.is_ascii_uppercase()) {
        first.to_string()
    } else {
        String::new()
    }
}

/// Every line gets an id stable across refreshes by (list, label), a repeated
/// label counted up.
fn entry_id(sub_url: &str, label: &str, ordinal: usize) -> String {
    let digest = Sha256::digest(format!("{sub_url}\0{label}\0{ordinal}").as_bytes());
    gocompat::text::hex(&digest[..6])
}

/// Parse the lines of one list. A line that cannot be connected keeps its
/// reason in `problem`, so the list can show it.
pub fn entries(sub_url: &str, lines: &[String]) -> Vec<Entry> {
    let mut seen: BTreeMap<String, usize> = BTreeMap::new();
    let mut out = Vec::with_capacity(lines.len());
    for raw in lines {
        let (kind, label, parsed, problem) = if let Some(room) = parse_olcrtc(raw) {
            (
                Kind::Olcrtc,
                room.label.clone(),
                Parsed::Olcrtc(room),
                String::new(),
            )
        } else if raw.starts_with("olcrtc://crypt1/") {
            (
                Kind::Unsupported,
                raw.clone(),
                Parsed::None,
                "olcrtc://crypt1 lists need a build secret and are not supported by this version"
                    .to_string(),
            )
        } else if raw.starts_with("vless://") {
            match parse_vless(raw) {
                Ok(line) => (
                    Kind::Vless,
                    line.label.clone(),
                    Parsed::Vless(line),
                    String::new(),
                ),
                Err(why) => (Kind::Vless, fragment_label(raw), Parsed::None, why),
            }
        } else if raw.starts_with("hysteria2://") || raw.starts_with("hy2://") {
            match parse_hy2(raw) {
                Ok(line) => (
                    Kind::Hysteria2,
                    line.label.clone(),
                    Parsed::Hy2(line),
                    String::new(),
                ),
                Err(why) => (Kind::Hysteria2, fragment_label(raw), Parsed::None, why),
            }
        } else {
            let scheme = raw.split_once("://").map_or("", |(s, _)| s);
            (
                Kind::Unsupported,
                raw.clone(),
                Parsed::None,
                format!("{scheme}:// is not supported"),
            )
        };
        let country = country_of(&label);
        let ordinal = seen.entry(label.clone()).or_default();
        *ordinal += 1;
        let (olcrtc, vless, hy2) = match parsed {
            Parsed::Olcrtc(line) => (Some(line), None, None),
            Parsed::Vless(line) => (None, Some(line), None),
            Parsed::Hy2(line) => (None, None, Some(line)),
            Parsed::None => (None, None, None),
        };
        out.push(Entry {
            id: entry_id(sub_url, &label, *ordinal),
            label,
            country,
            kind,
            raw: raw.clone(),
            olcrtc,
            vless,
            hy2,
            problem,
        });
    }
    out
}

enum Parsed {
    None,
    Olcrtc(OlcrtcLine),
    Vless(VlessLine),
    Hy2(Hy2Line),
}

fn parse_olcrtc(raw: &str) -> Option<OlcrtcLine> {
    let body = raw.strip_prefix("olcrtc://")?;
    if body.starts_with("crypt1/") {
        return None;
    }
    let question = body.find('?')?;
    if question == 0 {
        return None;
    }
    let at = body[question + 1..].find('@')? + question + 1;
    let hash = body[at + 1..].find('#')? + at + 1;
    let provider = normalize_provider(&body[..question])?;
    let tokens: Vec<&str> = body[question + 1..at].split('&').collect();
    let transport = normalize_transport(tokens.first().copied()?)?;
    let before_label = body.len();
    let percent = body[hash + 1..].find('%').map(|at| at + hash + 1);
    let dollar = body[hash + 1..].find('$').map(|at| at + hash + 1);
    let key_end = [Some(before_label), percent, dollar]
        .into_iter()
        .flatten()
        .min()?;
    let room = body[at + 1..hash].trim();
    let key = body[hash + 1..key_end].trim();
    if room.is_empty() || !is_hex64(key) {
        return None;
    }
    let label = dollar
        .map(|at| body[at + 1..].trim())
        .filter(|label| !label.is_empty())
        .unwrap_or(room)
        .to_string();
    Some(OlcrtcLine {
        provider,
        transport,
        room: room.to_string(),
        key: key.to_string(),
        label,
    })
}

fn normalize_provider(value: &str) -> Option<String> {
    let name = match value.trim().to_ascii_lowercase().as_str() {
        "telemost" | "yandex" | "yandex_telemost" => "telemost",
        "wbstream" | "wb-stream" | "wb_stream" | "wildberries" => "wbstream",
        "jitsi" | "jitsi-meet" | "jitsi_meet" | "meet" => "jitsi",
        "salutejazz" | "jazz" | "sberjazz" | "sber_jazz" => "salutejazz",
        "vkcalls" | "vk" | "vkcall" | "vk_calls" | "vk-calls" => "vkcalls",
        "mtslink" | "mts" | "mts-link" | "mts_link" => "mtslink",
        _ => return None,
    };
    Some(name.to_string())
}

fn normalize_transport(value: &str) -> Option<String> {
    let name = match value.trim().to_ascii_lowercase().as_str() {
        "datachannel" | "data" | "dc" => "datachannel",
        "vp8channel" | "vp8" | "video_vp8" | "video-vp8" => "vp8channel",
        "seichannel" | "sei" => "seichannel",
        "videochannel" | "video" => "videochannel",
        _ => return None,
    };
    Some(name.to_string())
}

fn is_hex64(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| b.is_ascii_hexdigit())
}

struct Share {
    user: String,
    host: String,
    port: u16,
    query: BTreeMap<String, String>,
    label: String,
}

fn split_share(raw: &str, scheme: &str) -> Result<Share, String> {
    let mut body = &raw[scheme.len()..];
    let mut label = String::new();
    if let Some(at) = body.find('#') {
        label = form_unescape(&body[at + 1..]).trim().to_string();
        body = &body[..at];
    }
    let mut query = BTreeMap::new();
    if let Some(at) = body.find('?') {
        for pair in body[at + 1..].split('&') {
            let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
            query.insert(form_unescape(key), form_unescape(value));
        }
        body = &body[..at];
    }
    let at = body.rfind('@').ok_or("no credentials before @")?;
    let user = &body[..at];
    let host_port = &body[at + 1..];
    let colon = host_port.rfind(':').ok_or("no host:port")?;
    if colon == 0 || user.is_empty() {
        return Err("empty host or credentials".to_string());
    }
    let port: u16 = host_port[colon + 1..]
        .parse()
        .map_err(|_| "bad port".to_string())?;
    let host = host_port[..colon].trim_matches(['[', ']']).to_string();
    if host.is_empty() {
        return Err("empty host or credentials".to_string());
    }
    Ok(Share {
        user: user.to_string(),
        host,
        port,
        query,
        label,
    })
}

fn form_unescape(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut at = 0;
    while at < bytes.len() {
        match bytes[at] {
            b'%' if at + 2 < bytes.len() => {
                let hex = |b: u8| char::from(b).to_digit(16);
                if let (Some(high), Some(low)) = (hex(bytes[at + 1]), hex(bytes[at + 2])) {
                    out.push((high * 16 + low) as u8);
                    at += 3;
                    continue;
                }
                out.push(bytes[at]);
            }
            b'+' => out.push(b' '),
            byte => out.push(byte),
        }
        at += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn fragment_label(raw: &str) -> String {
    raw.rsplit_once('#')
        .map(|(_, tag)| form_unescape(tag).trim().to_string())
        .filter(|tag| !tag.is_empty())
        .unwrap_or_else(|| raw.to_string())
}

fn parse_vless(raw: &str) -> Result<VlessLine, String> {
    let share = split_share(raw, "vless://").map_err(|why| format!("vless line: {why}"))?;
    let transport = share
        .query
        .get("type")
        .map_or("tcp", String::as_str)
        .trim()
        .to_ascii_lowercase();
    let transport = match transport.as_str() {
        "" | "tcp" | "raw" => "tcp".to_string(),
        "xhttp" => "xhttp".to_string(),
        other => return Err(format!("transport not supported: vless over {other}")),
    };
    let label = if share.label.is_empty() {
        share.host.clone()
    } else {
        share.label
    };
    let _ = share.user;
    Ok(VlessLine {
        host: share.host,
        port: share.port,
        transport,
        label,
    })
}

fn parse_hy2(raw: &str) -> Result<Hy2Line, String> {
    let scheme = if raw.starts_with("hysteria2://") {
        "hysteria2://"
    } else {
        "hy2://"
    };
    let share = split_share(raw, scheme).map_err(|why| format!("hysteria2 line: {why}"))?;
    let label = if share.label.is_empty() {
        share.host.clone()
    } else {
        share.label
    };
    let _ = (share.user, share.query);
    Ok(Hy2Line {
        host: share.host,
        port: share.port,
        label,
    })
}

/// Countries keep first-appearance order; a line with no country is reachable
/// by its label or index but is in no group.
pub fn group_by_country(entries: &[Entry]) -> Vec<(String, Vec<&Entry>)> {
    let mut groups: Vec<(String, Vec<&Entry>)> = Vec::new();
    for entry in entries {
        if entry.country.is_empty() {
            continue;
        }
        if let Some(group) = groups.iter_mut().find(|(name, _)| *name == entry.country) {
            group.1.push(entry);
        } else {
            groups.push((entry.country.clone(), vec![entry]));
        }
    }
    groups
}

/// A selector is an index, an exact label or a country. Nothing is guessed.
pub fn select<'a>(entries: &'a [Entry], selector: &str) -> Result<Vec<&'a Entry>, String> {
    let selector = selector.trim();
    if selector.is_empty() {
        return Err("no such location: empty selector".to_string());
    }
    if let Ok(index) = selector.parse::<usize>() {
        return entries
            .get(index.wrapping_sub(1))
            .map(|entry| vec![entry])
            .ok_or_else(|| {
                format!(
                    "no such location: index {index}, the list has {} entries",
                    entries.len()
                )
            });
    }
    let labels: Vec<&Entry> = entries.iter().filter(|e| e.label == selector).collect();
    if labels.len() == 1 {
        return Ok(labels);
    }
    if labels.len() > 1 {
        return Err(format!(
            "no such location: {} entries are named {selector:?}, pick one by index",
            labels.len()
        ));
    }
    for (country, group) in group_by_country(entries) {
        if country.eq_ignore_ascii_case(selector) {
            return Ok(group);
        }
    }
    let countries: Vec<String> = group_by_country(entries)
        .into_iter()
        .map(|(name, _)| name)
        .collect();
    Err(format!(
        "no such location: {selector:?}; countries: {}; or a label or an index from `ghostlane list`",
        countries.join(" ")
    ))
}

/// The app's deep links: `ghostlane://add?url=…` and `ghostlane://add/<raw>`.
pub fn import_payload(source: &str) -> String {
    let text = source.trim();
    let Some(rest) = text.get(.."ghostlane://".len()) else {
        return text.to_string();
    };
    if !rest.eq_ignore_ascii_case("ghostlane://") {
        return text.to_string();
    }
    let rest = &text["ghostlane://".len()..];
    for host in ["add", "import"] {
        let prefix = format!("{host}?");
        if rest.len() >= prefix.len() && rest[..prefix.len()].eq_ignore_ascii_case(&prefix) {
            for pair in rest[prefix.len()..].split('&') {
                if let Some((key, value)) = pair.split_once('=') {
                    if key.eq_ignore_ascii_case("url") {
                        return form_unescape(value);
                    }
                }
            }
            return text.to_string();
        }
        let prefix = format!("{host}/");
        if rest.len() >= prefix.len() && rest[..prefix.len()].eq_ignore_ascii_case(&prefix) {
            return rest[prefix.len()..].to_string();
        }
    }
    text.to_string()
}

/// A refusal is kept as a cache with no body and the host's headers.
pub fn refused_cache(headers: Headers) -> Cache {
    Cache {
        body: None,
        headers,
        fetched_at: gocompat::time::Timestamp::now(),
    }
}

pub fn first_non_empty(first: &str, second: &str) -> String {
    if first.is_empty() {
        second.to_string()
    } else {
        first.to_string()
    }
}

pub fn mask(url: &str) -> String {
    store::mask_url(url)
}
