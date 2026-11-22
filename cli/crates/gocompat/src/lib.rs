//! What the Go ghostlane CLI writes, byte for byte.
//!
//! The Rust CLI takes over a machine whose state the Go program wrote, and a
//! person may go back to the Go program after it: `config.yaml`, the cached
//! lists, `last-good.json` and the lines on the control socket have to be the
//! same bytes from either. Go's libraries decide those bytes (`gopkg.in/yaml.v3`,
//! `encoding/json`, `time`), so the parts of them that reach a file or the
//! socket are written out here, with their quirks.

pub mod json;
pub mod text;
pub mod time;
pub mod yaml;
