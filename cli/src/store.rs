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
        todo!()
    }

    /// The file's content over the defaults, as `yaml.Unmarshal` lays it.
    pub fn from_yaml(document: &[u8]) -> Result<Config, yaml::Error> {
        todo!()
    }

    /// The file's text, as `yaml.Marshal` writes it.
    pub fn to_yaml(&self) -> String {
        todo!()
    }
}

/// A field with `omitempty` is left out when it holds Go's zero value.
fn omit_empty(fields: &mut Vec<(&'static str, Value)>, key: &'static str, text: &str) {
    todo!()
}

impl Subscription {
    fn to_value(&self) -> Value {
        todo!()
    }
}

impl Selection {
    fn to_value(&self) -> Value {
        todo!()
    }
}

impl Proxy {
    fn to_value(&self) -> Value {
        todo!()
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
        todo!()
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
        todo!()
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
        todo!()
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
        todo!()
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
        todo!()
    }
}

impl std::error::Error for Error {}

impl From<yaml::Error> for Error {
    fn from(err: yaml::Error) -> Error {
        todo!()
    }
}

impl From<serde_json::Error> for Error {
    fn from(err: serde_json::Error) -> Error {
        todo!()
    }
}

/// The file's content, or nothing when there is no file.
fn read_if_present(path: &Path) -> Result<Option<Vec<u8>>, Error> {
    todo!()
}

/// `config.yaml`, or the defaults when there is none.
pub fn load(path: &Path) -> Result<Config, Error> {
    todo!()
}

/// Writes `config.yaml`, readable by its owner only.
pub fn save(path: &Path, config: &Config) -> Result<(), Error> {
    todo!()
}

/// Writes a state file whole or not at all: beside it under `<name>.tmp`,
/// then renamed over it. The directory is made when it is missing, for its
/// owner only.
pub fn write_atomic(path: &Path, data: &[u8], mode: u32) -> Result<(), Error> {
    todo!()
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
    todo!()
}

/// A list's cache, or nothing when there is none. A file that cannot be
/// read as one is removed and counts as none: it would otherwise stand in
/// the way of the next fetch for ever.
pub fn load_cache(state_dir: &Path, subscription_url: &str) -> Result<Option<Cache>, Error> {
    todo!()
}

pub fn save_cache(state_dir: &Path, subscription_url: &str, cache: &Cache) -> Result<(), Error> {
    todo!()
}

fn last_good_path(state_dir: &Path) -> PathBuf {
    todo!()
}

/// The line that last worked for each selection (`<list>|<selector>` to the
/// line's id), empty when there is no file.
pub fn load_last_good(state_dir: &Path) -> Result<BTreeMap<String, String>, Error> {
    todo!()
}

pub fn save_last_good(state_dir: &Path, last_good: &BTreeMap<String, String>) -> Result<(), Error> {
    todo!()
}

/// Hides the credential part of a list URL: the token after `/sub/` (or
/// after `/sub/<partner>/` when the partner's id comes first), or the whole
/// path when the URL is not of that shape. Every query value but the list
/// variant (`c`) is hidden too: tokens travel there as well.
pub fn mask_url(url: &str) -> String {
    todo!()
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
    todo!()
}

/// The scheme and what follows its colon; no scheme when the text does not
/// start with one. A colon at the very start is refused.
fn split_scheme(url: &str) -> Option<(&str, &str)> {
    todo!()
}

/// The host of an authority (`user:pass@host:port`), port and all, with
/// the user left out; nothing where Go refuses the authority.
fn split_host(authority: &str) -> Option<String> {
    todo!()
}

/// Resolves `%XX`; nothing for a `%` that is not followed by two hex
/// digits. In a host Go takes only escapes of bytes that are not ASCII
/// (and `%25`), and no ASCII character outside its list.
fn unescape(text: &str, host: bool) -> Option<Vec<u8>> {
    todo!()
}
