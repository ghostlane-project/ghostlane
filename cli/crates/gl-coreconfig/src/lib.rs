//! `core.json` for a line, as the app writes it: the port of the app's
//! builder (sharedUI `net/core`) for the CLI. Not written yet: the vectors
//! the app's test writes say what it is to write.

pub mod inbound;
pub mod json;
pub mod lines;
pub mod spec;
pub mod text;

use std::fmt;

pub use spec::{Lan, Own, Plan, SocksLogin, Spec, Tls, Transport};

/// Why no config is written.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BuildError {
    NotPorted,
}

impl fmt::Display for BuildError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("the builder is not ported yet")
    }
}

impl std::error::Error for BuildError {}

pub fn refusal(_spec: &Spec) -> Option<String> {
    None
}

pub fn pin_host(spec: &Spec, _address: &str) -> Spec {
    spec.clone()
}

pub fn hello_name(_fp: Option<&str>) -> String {
    String::new()
}

pub fn reality_hello(_fp: Option<&str>) -> String {
    String::new()
}

pub fn hello_note(_spec: &Spec) -> Option<String> {
    None
}

pub fn build(
    _spec: &Spec,
    _login: &SocksLogin,
    _socks_port: i32,
    _verbose_logs: bool,
) -> Result<String, BuildError> {
    Err(BuildError::NotPorted)
}

pub fn build_plan(_spec: &Spec, _plan: &Plan, _verbose_logs: bool) -> Result<String, BuildError> {
    Err(BuildError::NotPorted)
}
