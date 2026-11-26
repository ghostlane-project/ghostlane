//! olcRTC room links, as the CLI's Go `olcrtc.go` reads them:
//! `olcrtc://<provider>?<transport>[&k=v…]@<room>#<key>[%<client>][$<label>]`.

use std::fmt;

use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct OlcrtcLine {
    pub provider: String,
    pub transport: String,
    pub room: String,
    pub key: String,
    pub label: String,
    #[serde(rename = "vp8FPS")]
    pub vp8_fps: i32,
    #[serde(rename = "vp8Batch")]
    pub vp8_batch: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refusal {
    NotOlcrtc,
    Crypt1,
    Malformed(&'static str),
    UnknownCarrier(String),
    UnknownTransport(String),
}

impl Refusal {
    pub fn text(&self) -> String {
        match self {
            Refusal::NotOlcrtc => "not an olcrtc:// line".to_string(),
            Refusal::Crypt1 => {
                "olcrtc://crypt1 lists need a build secret and are not supported by this version"
                    .to_string()
            }
            Refusal::Malformed(why) => format!("olcrtc line: {why}"),
            Refusal::UnknownCarrier(c) => format!("olcrtc line: unknown carrier \"{c}\""),
            Refusal::UnknownTransport(t) => format!("olcrtc line: unknown transport \"{t}\""),
        }
    }
}

impl fmt::Display for Refusal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.text())
    }
}

pub fn normalize_provider(s: &str) -> Option<&'static str> {
    match s.trim().to_lowercase().as_str() {
        "telemost" | "yandex" | "yandex_telemost" => Some("telemost"),
        "wbstream" | "wb-stream" | "wb_stream" | "wildberries" => Some("wbstream"),
        "jitsi" | "jitsi-meet" | "jitsi_meet" | "meet" => Some("jitsi"),
        "salutejazz" | "jazz" | "sberjazz" | "sber_jazz" => Some("salutejazz"),
        "vkcalls" | "vk" | "vkcall" | "vk_calls" | "vk-calls" => Some("vkcalls"),
        "mtslink" | "mts" | "mts-link" | "mts_link" => Some("mtslink"),
        _ => None,
    }
}

pub fn normalize_transport(s: &str) -> Option<&'static str> {
    match s.trim().to_lowercase().as_str() {
        "datachannel" | "data" | "dc" => Some("datachannel"),
        "vp8channel" | "vp8" | "video_vp8" | "video-vp8" => Some("vp8channel"),
        "seichannel" | "sei" => Some("seichannel"),
        "videochannel" | "video" => Some("videochannel"),
        _ => None,
    }
}

fn index_from(s: &str, needle: char, from: usize) -> Option<usize> {
    if from > s.len() {
        return None;
    }
    s[from..].find(needle).map(|i| from + i)
}

fn is_hex64(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())
}

pub fn parse_olcrtc(line: &str) -> Result<OlcrtcLine, Refusal> {
    let line = line.trim();
    let payload = line.strip_prefix("olcrtc://").ok_or(Refusal::NotOlcrtc)?;
    if payload.starts_with("crypt1/") {
        return Err(Refusal::Crypt1);
    }
    let t = payload.find('?');
    let r = t.and_then(|t| index_from(payload, '@', t + 1));
    let k = r.and_then(|r| index_from(payload, '#', r + 1));
    let (t, r, k) = match (t, r, k) {
        (Some(t), Some(r), Some(k)) if t > 0 && r > t && k > r => (t, r, k),
        _ => return Err(Refusal::Malformed("expected provider?transport@room#key")),
    };
    let mut key_end = payload.len();
    let label_at = index_from(payload, '$', k + 1);
    if let Some(i) = index_from(payload, '%', k + 1) {
        key_end = key_end.min(i);
    }
    if let Some(i) = label_at {
        key_end = key_end.min(i);
    }
    let provider = normalize_provider(&payload[..t])
        .ok_or_else(|| Refusal::UnknownCarrier(payload[..t].to_string()))?
        .to_string();
    let tokens: Vec<&str> = payload[t + 1..r].split('&').collect();
    let transport = normalize_transport(tokens[0])
        .ok_or_else(|| Refusal::UnknownTransport(tokens[0].to_string()))?
        .to_string();
    let mut vp8_fps = 0;
    let mut vp8_batch = 0;
    for kv in tokens.iter().skip(1) {
        let (name, value) = match kv.split_once('=') {
            Some((name, value)) => (name, value),
            None => continue,
        };
        let n = match value.trim().parse::<i32>() {
            Ok(n) if n > 0 => n,
            _ => continue,
        };
        match name.trim().to_lowercase().as_str() {
            "fps" | "vp8-fps" => vp8_fps = n,
            "batch" | "vp8-batch" => vp8_batch = n,
            _ => {}
        }
    }
    let room = payload[r + 1..k].trim();
    if room.is_empty() {
        return Err(Refusal::Malformed("empty room"));
    }
    let key = payload[k + 1..key_end].trim();
    if !is_hex64(key) {
        return Err(Refusal::Malformed("key must be 64 hex characters"));
    }
    let mut label = label_at
        .map(|i| payload[i + 1..].trim().to_string())
        .unwrap_or_default();
    if label.is_empty() {
        label = room.to_string();
    }
    Ok(OlcrtcLine {
        provider,
        transport,
        room: room.to_string(),
        key: key.to_string(),
        label,
        vp8_fps,
        vp8_batch,
    })
}
