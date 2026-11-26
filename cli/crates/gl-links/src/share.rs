//! Share links: `vless://` (TCP or XHTTP) and `hysteria2://` / `hy2://`, as
//! the CLI's Go `share.go` reads them.

use std::fmt;

use serde::Serialize;

use crate::text::{parse_query, query_value, truthy, url_decode};

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Transport {
    pub kind: String,
    pub path: String,
    pub host: String,
    pub mode: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct VlessLine {
    pub uuid: String,
    pub host: String,
    pub port: i32,
    pub sni: String,
    #[serde(rename = "publicKey")]
    pub public_key: String,
    #[serde(rename = "shortID")]
    pub short_id: String,
    pub fingerprint: String,
    pub flow: String,
    pub transport: Transport,
    pub label: String,
    pub plain: bool,
    pub insecure: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Hy2Line {
    pub password: String,
    pub host: String,
    pub port: i32,
    pub sni: String,
    #[serde(rename = "obfsPassword")]
    pub obfs_password: String,
    #[serde(rename = "pinSHA256")]
    pub pin_sha256: String,
    pub insecure: bool,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refusal {
    NotVless,
    NotHy2,
    UnsupportedTransport(String),
    Line(String),
}

impl Refusal {
    /// The stable words a refusal is written with in the vectors.
    pub fn text(&self) -> String {
        match self {
            Refusal::NotVless => "not a vless:// line".to_string(),
            Refusal::NotHy2 => "not a hysteria2:// line".to_string(),
            Refusal::UnsupportedTransport(t) => format!("transport not supported: {t}"),
            Refusal::Line(why) => why.clone(),
        }
    }
}

struct Parts {
    userinfo: String,
    host: String,
    port: i32,
    query: Vec<(String, String)>,
    tag: String,
}

/// `scheme://userinfo@host:port?query#tag`, the app's `LinkParser.splitLink`.
fn split_link(s: &str, scheme: &str) -> Result<Parts, String> {
    let body = s.strip_prefix(scheme).unwrap_or(s);
    let (body, tag) = match body.split_once('#') {
        Some((before, tag)) => (before, url_decode(tag).trim().to_string()),
        None => (body, String::new()),
    };
    let (body, query) = match body.split_once('?') {
        Some((before, q)) => (before, parse_query(q)),
        None => (body, Vec::new()),
    };
    let at = body.rfind('@').ok_or("no credentials before @")?;
    let (userinfo, host_port) = (&body[..at], &body[at + 1..]);
    let colon = host_port.rfind(':').ok_or("no host:port")?;
    if colon == 0 {
        return Err("no host:port".to_string());
    }
    let port: i32 = host_port[colon + 1..]
        .parse()
        .map_err(|_| "bad port".to_string())?;
    if !(1..=65535).contains(&port) {
        return Err("bad port".to_string());
    }
    let host = host_port[..colon].trim_matches(['[', ']']);
    if host.is_empty() || userinfo.is_empty() {
        return Err("empty host or credentials".to_string());
    }
    Ok(Parts {
        userinfo: userinfo.to_string(),
        host: host.to_string(),
        port,
        query,
        tag,
    })
}

impl fmt::Display for Refusal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.text())
    }
}

pub fn parse_vless(line: &str) -> Result<VlessLine, Refusal> {
    let line = line.trim();
    if !line.starts_with("vless://") {
        return Err(Refusal::NotVless);
    }
    let p =
        split_link(line, "vless://").map_err(|why| Refusal::Line(format!("vless line: {why}")))?;
    let q = &p.query;
    let typ = query_value(q, "type").trim().to_lowercase();
    let typ = if typ.is_empty() { "tcp" } else { &typ };
    let mut fingerprint = query_value(q, "fp").to_string();
    if fingerprint.is_empty() {
        fingerprint = "chrome".to_string();
    }
    let mut label = p.tag.clone();
    if label.is_empty() {
        label = p.host.clone();
    }
    let public_key = query_value(q, "pbk").to_string();
    let (plain, insecure) = if public_key.is_empty() {
        (
            query_value(q, "security")
                .trim()
                .eq_ignore_ascii_case("none"),
            truthy(query_value(q, "allowInsecure")) || truthy(query_value(q, "insecure")),
        )
    } else {
        (false, false)
    };
    let transport = match typ {
        "tcp" | "raw" => Transport {
            kind: "tcp".to_string(),
            path: String::new(),
            host: String::new(),
            mode: String::new(),
        },
        "xhttp" => {
            let host = {
                let h = query_value(q, "host");
                if h.is_empty() {
                    query_value(q, "sni").to_string()
                } else {
                    h.to_string()
                }
            };
            let path = {
                let p = query_value(q, "path");
                if p.is_empty() { "/" } else { p }.to_string()
            };
            let mode = {
                let m = query_value(q, "mode");
                if m.is_empty() { "auto" } else { m }.to_string()
            };
            Transport {
                kind: "xhttp".to_string(),
                path,
                host,
                mode,
            }
        }
        other => return Err(Refusal::UnsupportedTransport(other.to_string())),
    };
    let flow = if transport.kind == "tcp" {
        query_value(q, "flow").trim().to_string()
    } else {
        String::new()
    };
    Ok(VlessLine {
        uuid: p.userinfo,
        host: p.host,
        port: p.port,
        sni: query_value(q, "sni").to_string(),
        public_key,
        short_id: query_value(q, "sid").to_string(),
        fingerprint,
        flow,
        transport,
        label,
        plain,
        insecure,
    })
}

pub fn parse_hy2(line: &str) -> Result<Hy2Line, Refusal> {
    let line = line.trim();
    let scheme = if line.starts_with("hysteria2://") {
        "hysteria2://"
    } else if line.starts_with("hy2://") {
        "hy2://"
    } else {
        return Err(Refusal::NotHy2);
    };
    let p =
        split_link(line, scheme).map_err(|why| Refusal::Line(format!("hysteria2 line: {why}")))?;
    let q = &p.query;
    let label = if p.tag.is_empty() {
        p.host.clone()
    } else {
        p.tag.clone()
    };
    Ok(Hy2Line {
        password: p.userinfo,
        host: p.host,
        port: p.port,
        sni: query_value(q, "sni").to_string(),
        obfs_password: query_value(q, "obfs-password").to_string(),
        pin_sha256: query_value(q, "pinSHA256").to_string(),
        insecure: truthy(query_value(q, "insecure")),
        label,
    })
}
