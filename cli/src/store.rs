//! The daemon's config and state under its state directory: `config.yaml`,
//! the cached lists and `last-good.json`.
//!
//! These are the files the Go program keeps, byte for byte: a machine that
//! changes program keeps its lists, its selection and its proxy settings, and
//! one that goes back finds files it reads. The Go types are in
//! `cli/internal/store/store.go`; their field order and their tags are the
//! format.

use std::collections::BTreeMap;
use std::fmt;
use std::fs::{self, DirBuilder, OpenOptions, Permissions};
use std::io::{self, Write as _};
use std::os::unix::fs::{DirBuilderExt as _, OpenOptionsExt as _, PermissionsExt as _};
use std::path::{Path, PathBuf};

use gocompat::text::{errno_text, hex};
use gocompat::time::Timestamp;
use gocompat::yaml::{self, Value};
use serde::{Deserialize, Serialize};
use sha2::{Digest as _, Sha256};

/// One list of lines: a provider's URL, or lines given by hand (`inline:`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Subscription {
    /// The URL is a credential: it is stored and never printed in full.
    pub url: String,
    pub title: String,
    pub interval_hours: i64,
    pub added_at: Timestamp,
}

/// What is connected, and reconnected at the next start.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Selection {
    pub subscription: String,
    pub selector: String,
    /// "tun" or "proxy".
    pub mode: String,
    /// tun only: refuse traffic outside the tunnel while no line is up.
    pub kill_switch: bool,
    /// An index or a label names a line, not a position: its id once resolved.
    pub entry_id: String,
    /// The list `entry_id`'s line came from: a line that is gone is looked
    /// for again in that list only, never in another provider's.
    pub entry_subscription: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Proxy {
    pub listen: String,
    pub port: i64,
    pub user: String,
    pub pass: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Config {
    pub subscriptions: Vec<Subscription>,
    pub selection: Option<Selection>,
    pub proxy: Proxy,
}

impl Config {
    /// A config no file has changed: no lists, the proxy on 127.0.0.1:1080.
    pub fn defaults() -> Config {
        Config {
            proxy: Proxy {
                listen: "127.0.0.1".to_string(),
                port: 1080,
                ..Proxy::default()
            },
            ..Config::default()
        }
    }

    /// The file's content over the defaults, as `yaml.Unmarshal` lays it.
    pub fn from_yaml(document: &[u8]) -> Result<Config, yaml::Error> {
        let mut config = Config::defaults();
        yaml::unmarshal(document, &mut config)?;
        if config.proxy.listen.is_empty() {
            config.proxy.listen = "127.0.0.1".to_string();
        }
        if config.proxy.port == 0 {
            config.proxy.port = 1080;
        }
        Ok(config)
    }

    /// The file's text, as `yaml.Marshal` writes it.
    pub fn to_yaml(&self) -> String {
        let mut root = vec![(
            "subscriptions",
            Value::Seq(
                self.subscriptions
                    .iter()
                    .map(Subscription::to_value)
                    .collect(),
            ),
        )];
        if let Some(selection) = &self.selection {
            root.push(("selection", selection.to_value()));
        }
        root.push(("proxy", self.proxy.to_value()));
        yaml::marshal(&Value::Map(root))
    }
}

/// A field with `omitempty` is left out when it holds Go's zero value.
fn omit_empty(fields: &mut Vec<(&'static str, Value)>, key: &'static str, text: &str) {
    if !text.is_empty() {
        fields.push((key, Value::Str(text.to_string())));
    }
}

impl Subscription {
    fn to_value(&self) -> Value {
        let mut fields = vec![("url", Value::Str(self.url.clone()))];
        omit_empty(&mut fields, "title", &self.title);
        fields.push(("interval_hours", Value::Int(self.interval_hours)));
        fields.push(("added_at", Value::Time(self.added_at)));
        Value::Map(fields)
    }
}

impl Selection {
    fn to_value(&self) -> Value {
        let mut fields = vec![
            ("subscription", Value::Str(self.subscription.clone())),
            ("selector", Value::Str(self.selector.clone())),
            ("mode", Value::Str(self.mode.clone())),
        ];
        if self.kill_switch {
            fields.push(("kill_switch", Value::Bool(true)));
        }
        omit_empty(&mut fields, "entry_id", &self.entry_id);
        omit_empty(&mut fields, "entry_subscription", &self.entry_subscription);
        Value::Map(fields)
    }
}

impl Proxy {
    fn to_value(&self) -> Value {
        let mut fields = vec![
            ("listen", Value::Str(self.listen.clone())),
            ("port", Value::Int(self.port)),
        ];
        omit_empty(&mut fields, "user", &self.user);
        omit_empty(&mut fields, "pass", &self.pass);
        Value::Map(fields)
    }
}

impl yaml::Record for Subscription {
    const TYPE: &'static str = "store.Subscription";
    const FIELDS: &'static [&'static str] = &["url", "title", "interval_hours", "added_at"];

    fn field(
        &mut self,
        decoder: &mut yaml::Decoder,
        index: usize,
        value: &yaml::Node,
    ) -> Result<(), yaml::Error> {
        let read = match index {
            0 => decoder.string(value, &mut self.url),
            1 => decoder.string(value, &mut self.title),
            2 => decoder.int(value, &mut self.interval_hours),
            _ => decoder.time(value, &mut self.added_at),
        };
        read.map(drop)
    }
}

impl yaml::Record for Selection {
    const TYPE: &'static str = "store.Selection";
    const FIELDS: &'static [&'static str] = &[
        "subscription",
        "selector",
        "mode",
        "kill_switch",
        "entry_id",
        "entry_subscription",
    ];

    fn field(
        &mut self,
        decoder: &mut yaml::Decoder,
        index: usize,
        value: &yaml::Node,
    ) -> Result<(), yaml::Error> {
        let read = match index {
            0 => decoder.string(value, &mut self.subscription),
            1 => decoder.string(value, &mut self.selector),
            2 => decoder.string(value, &mut self.mode),
            3 => decoder.boolean(value, &mut self.kill_switch),
            4 => decoder.string(value, &mut self.entry_id),
            _ => decoder.string(value, &mut self.entry_subscription),
        };
        read.map(drop)
    }
}

impl yaml::Record for Proxy {
    const TYPE: &'static str = "store.Proxy";
    const FIELDS: &'static [&'static str] = &["listen", "port", "user", "pass"];

    fn field(
        &mut self,
        decoder: &mut yaml::Decoder,
        index: usize,
        value: &yaml::Node,
    ) -> Result<(), yaml::Error> {
        let read = match index {
            0 => decoder.string(value, &mut self.listen),
            1 => decoder.int(value, &mut self.port),
            2 => decoder.string(value, &mut self.user),
            _ => decoder.string(value, &mut self.pass),
        };
        read.map(drop)
    }
}

impl yaml::Record for Config {
    const TYPE: &'static str = "store.Config";
    const FIELDS: &'static [&'static str] = &["subscriptions", "selection", "proxy"];

    fn field(
        &mut self,
        decoder: &mut yaml::Decoder,
        index: usize,
        value: &yaml::Node,
    ) -> Result<(), yaml::Error> {
        let read = match index {
            0 => decoder.sequence(value, &mut self.subscriptions),
            1 => decoder.pointer(value, &mut self.selection),
            _ => decoder.record(value, &mut self.proxy),
        };
        read.map(drop)
    }
}

/// Why a state file was not read or written.
#[derive(Debug)]
pub enum Error {
    /// The system refused: what was asked of which file, and its answer.
    Io {
        action: &'static str,
        path: PathBuf,
        source: io::Error,
    },
    Yaml(yaml::Error),
    Json(serde_json::Error),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            // as Go prints it: "open /var/lib/ghostlane/config.yaml: permission denied"
            Error::Io {
                action,
                path,
                source,
            } => write!(f, "{action} {}: {}", path.display(), errno_text(source)),
            Error::Yaml(err) => fmt::Display::fmt(err, f),
            Error::Json(err) => fmt::Display::fmt(err, f),
        }
    }
}

impl std::error::Error for Error {}

impl From<yaml::Error> for Error {
    fn from(err: yaml::Error) -> Error {
        Error::Yaml(err)
    }
}

impl From<serde_json::Error> for Error {
    fn from(err: serde_json::Error) -> Error {
        Error::Json(err)
    }
}

/// The file's content, or nothing when there is no file.
fn read_if_present(path: &Path) -> Result<Option<Vec<u8>>, Error> {
    match fs::read(path) {
        Ok(bytes) => Ok(Some(bytes)),
        Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(source) => Err(Error::Io {
            action: "open",
            path: path.to_path_buf(),
            source,
        }),
    }
}

/// `config.yaml`, or the defaults when there is none.
pub fn load(path: &Path) -> Result<Config, Error> {
    let Some(bytes) = read_if_present(path)? else {
        return Ok(Config::defaults());
    };
    Ok(Config::from_yaml(&bytes)?)
}

/// Writes `config.yaml`, readable by its owner only.
pub fn save(path: &Path, config: &Config) -> Result<(), Error> {
    write_atomic(path, config.to_yaml().as_bytes(), 0o600)
}

/// Writes a state file whole or not at all: beside it under `<name>.tmp`,
/// then renamed over it. The directory is made when it is missing, for its
/// owner only.
pub fn write_atomic(path: &Path, data: &[u8], mode: u32) -> Result<(), Error> {
    let failed = |action: &'static str, at: &Path| {
        let path = at.to_path_buf();
        move |source: io::Error| Error::Io {
            action,
            path,
            source,
        }
    };
    if let Some(dir) = path.parent() {
        DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(dir)
            .map_err(failed("mkdir", dir))?;
    }
    let mut name = path.as_os_str().to_owned();
    name.push(".tmp");
    let tmp = PathBuf::from(name);
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(mode)
        .open(&tmp)
        .map_err(failed("open", &tmp))?;
    file.write_all(data).map_err(failed("write", &tmp))?;
    drop(file);
    // the mode above is cut by the umask and is not applied to a file that was there
    fs::set_permissions(&tmp, Permissions::from_mode(mode)).map_err(failed("chmod", &tmp))?;
    fs::rename(&tmp, path).map_err(failed("rename", &tmp))
}

/// How much of the plan is used, as a list's host says it
/// (`subscription-userinfo`). The JSON keys are Go's field names.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "PascalCase")]
pub struct UserInfo {
    pub upload: i64,
    pub download: i64,
    pub total: i64,
    pub expire: i64,
}

/// What a list's host said beside the list. The JSON keys are Go's field
/// names, in the order of `links.Headers`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Headers {
    #[serde(rename = "Title")]
    pub title: String,
    #[serde(rename = "UpdateIntervalHours")]
    pub update_interval_hours: i64,
    #[serde(rename = "UserInfo")]
    pub user_info: Option<UserInfo>,
    #[serde(rename = "SupportURL")]
    pub support_url: String,
    #[serde(rename = "WebPageURL")]
    pub web_page_url: String,
    #[serde(rename = "Announce")]
    pub announce: String,
    /// "" for a list that was served, else why the host turned this device
    /// away ("limit", "no-device").
    #[serde(rename = "Refusal", skip_serializing_if = "String::is_empty")]
    pub refusal: String,
}

/// A fetched list as it is kept between fetches.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Cache {
    /// The body as it came. Nothing (`null`) for a list that was refused,
    /// which is not the same as an empty body.
    #[serde(with = "gocompat::json::bytes")]
    pub body: Option<Vec<u8>>,
    pub headers: Headers,
    pub fetched_at: Timestamp,
}

/// Where a list's cache is: `lists/<the first 8 bytes of the URL's sha256>.json`.
pub fn cache_path(state_dir: &Path, subscription_url: &str) -> PathBuf {
    let digest = Sha256::digest(subscription_url.as_bytes());
    state_dir
        .join("lists")
        .join(format!("{}.json", hex(&digest[..8])))
}

/// A list's cache, or nothing when there is none. A file that cannot be
/// read as one is removed and counts as none: it would otherwise stand in
/// the way of the next fetch for ever.
pub fn load_cache(state_dir: &Path, subscription_url: &str) -> Result<Option<Cache>, Error> {
    let path = cache_path(state_dir, subscription_url);
    let Some(bytes) = read_if_present(&path)? else {
        return Ok(None);
    };
    match serde_json::from_slice(&bytes) {
        Ok(cache) => Ok(Some(cache)),
        Err(_) => {
            let _ = fs::remove_file(&path);
            Ok(None)
        }
    }
}

pub fn save_cache(state_dir: &Path, subscription_url: &str, cache: &Cache) -> Result<(), Error> {
    write_atomic(
        &cache_path(state_dir, subscription_url),
        &gocompat::json::marshal(cache)?,
        0o600,
    )
}

fn last_good_path(state_dir: &Path) -> PathBuf {
    state_dir.join("last-good.json")
}

/// The line that last worked for each selection (`<list>|<selector>` to the
/// line's id), empty when there is no file.
pub fn load_last_good(state_dir: &Path) -> Result<BTreeMap<String, String>, Error> {
    let Some(bytes) = read_if_present(&last_good_path(state_dir))? else {
        return Ok(BTreeMap::new());
    };
    // a null leaves Go's value as it is: an empty map, and in a map the
    // empty string
    let read: Option<BTreeMap<String, Option<String>>> = serde_json::from_slice(&bytes)?;
    let pairs = read.unwrap_or_default().into_iter();
    Ok(pairs
        .map(|(key, id)| (key, id.unwrap_or_default()))
        .collect())
}

pub fn save_last_good(state_dir: &Path, last_good: &BTreeMap<String, String>) -> Result<(), Error> {
    write_atomic(
        &last_good_path(state_dir),
        &gocompat::json::marshal(last_good)?,
        0o600,
    )
}

/// Hides the credential part of a list URL: the token after `/sub/` (or
/// after `/sub/<partner>/` when the partner's id comes first), or the whole
/// path when the URL is not of that shape. Every query value but the list
/// variant (`c`) is hidden too: tokens travel there as well.
pub fn mask_url(url: &str) -> String {
    let Some(parts) = split_url(url) else {
        return "…".to_string();
    };
    let mut segments: Vec<&str> = parts.path.trim_matches('/').split('/').collect();
    // /sub/<token>[/unified|/olcrtc] is ours; /sub/<partner>/<token> is a
    // partner's: the token is the last segment that is not a known suffix
    let sub = segments
        .iter()
        .enumerate()
        .position(|(at, segment)| *segment == "sub" && at + 1 < segments.len());
    match sub {
        Some(at)
            if at + 2 < segments.len()
                && segments[at + 2] != "unified"
                && segments[at + 2] != "olcrtc" =>
        {
            segments[at + 2] = "…"
        }
        Some(at) => segments[at + 1] = "…",
        None => segments = vec!["…"],
    }
    let mut out = format!("{}://{}/{}", parts.scheme, parts.host, segments.join("/"));
    if !parts.query.is_empty() {
        let masked: Vec<String> = parts
            .query
            .split('&')
            .map(
                |pair| match pair.split_once('=').map_or(pair, |(key, _)| key) {
                    "c" => pair.to_string(),
                    key => format!("{key}=…"),
                },
            )
            .collect();
        out.push('?');
        out.push_str(&masked.join("&"));
    }
    out
}

/// The parts of a URL that the mask is made of.
struct UrlParts {
    scheme: String,
    host: String,
    /// The path with its `%` escapes resolved.
    path: String,
    /// The query as it is written.
    query: String,
}

/// Go's `url.Parse` for those parts: the same split, and nothing for a URL
/// Go refuses.
fn split_url(raw: &str) -> Option<UrlParts> {
    let (url, fragment) = raw.split_once('#').unwrap_or((raw, ""));
    if url.bytes().any(|byte| byte < b' ' || byte == 0x7f) {
        return None;
    }
    unescape(fragment, false)?;
    let (scheme, rest) = split_scheme(url)?;
    let (rest, query) = if rest.ends_with('?') && rest.matches('?').count() == 1 {
        (&rest[..rest.len() - 1], "")
    } else {
        rest.split_once('?').unwrap_or((rest, ""))
    };
    let mut parts = UrlParts {
        scheme: scheme.to_ascii_lowercase(),
        host: String::new(),
        path: String::new(),
        query: query.to_string(),
    };
    if !rest.starts_with('/') {
        if !scheme.is_empty() {
            // a path without a root after a scheme is opaque: no host, no path
            return Some(parts);
        }
        if rest
            .split('/')
            .next()
            .is_some_and(|segment| segment.contains(':'))
        {
            return None;
        }
    }
    let mut path = rest;
    if (!scheme.is_empty() || !rest.starts_with("///")) && rest.starts_with("//") {
        let authority = &rest[2..];
        let end = authority.find('/').unwrap_or(authority.len());
        path = &authority[end..];
        parts.host = split_host(&authority[..end])?;
    }
    parts.path = String::from_utf8_lossy(&unescape(path, false)?).into_owned();
    Some(parts)
}

/// The scheme and what follows its colon; no scheme when the text does not
/// start with one. A colon at the very start is refused.
fn split_scheme(url: &str) -> Option<(&str, &str)> {
    for (at, byte) in url.bytes().enumerate() {
        match byte {
            b'a'..=b'z' | b'A'..=b'Z' => {}
            b'0'..=b'9' | b'+' | b'-' | b'.' if at > 0 => {}
            b':' if at > 0 => return Some((&url[..at], &url[at + 1..])),
            b':' => return None,
            _ => break,
        }
    }
    Some(("", url))
}

/// The host of an authority (`user:pass@host:port`), port and all, with
/// the user left out; nothing where Go refuses the authority.
fn split_host(authority: &str) -> Option<String> {
    let host = match authority.rsplit_once('@') {
        Some((userinfo, host)) => {
            let allowed = |c: char| c.is_ascii_alphanumeric() || "-._:~!$&'()*+,;=%@".contains(c);
            if !userinfo.chars().all(allowed) {
                return None;
            }
            unescape(userinfo, false)?;
            host
        }
        None => authority,
    };
    let port_is_valid = |port: &str| {
        port.is_empty()
            || (port.starts_with(':') && port[1..].bytes().all(|byte| byte.is_ascii_digit()))
    };
    if host.starts_with('[') {
        let end = host.rfind(']')?;
        let address = &host[1..end];
        let address = address
            .split_once("%25")
            .map_or(address, |(address, _zone)| address);
        if !port_is_valid(&host[end + 1..]) || address.parse::<std::net::Ipv6Addr>().is_err() {
            return None;
        }
    } else if let Some(colon) = host.rfind(':') {
        // one colon, before the port: Go refuses a second one outside brackets
        if host[..colon].contains(':') || !port_is_valid(&host[colon..]) {
            return None;
        }
    }
    Some(String::from_utf8_lossy(&unescape(host, true)?).into_owned())
}

/// Resolves `%XX`; nothing for a `%` that is not followed by two hex
/// digits. In a host Go takes only escapes of bytes that are not ASCII
/// (and `%25`), and no ASCII character outside its list.
fn unescape(text: &str, host: bool) -> Option<Vec<u8>> {
    let bytes = text.as_bytes();
    let hex = |byte: u8| {
        char::from(byte)
            .to_digit(16)
            .and_then(|digit| u8::try_from(digit).ok())
    };
    let mut out = Vec::with_capacity(bytes.len());
    let mut at = 0;
    while at < bytes.len() {
        let byte = bytes[at];
        if byte == b'%' {
            let high = hex(*bytes.get(at + 1)?)?;
            let low = hex(*bytes.get(at + 2)?)?;
            if host && high < 8 && &bytes[at..at + 3] != b"%25" {
                return None;
            }
            out.push((high << 4) | low);
            at += 3;
            continue;
        }
        if host
            && byte.is_ascii()
            && !byte.is_ascii_alphanumeric()
            && !b"!$&'()*+,;=:[]<>\"-_.~".contains(&byte)
        {
            return None;
        }
        out.push(byte);
        at += 1;
    }
    Some(out)
}
