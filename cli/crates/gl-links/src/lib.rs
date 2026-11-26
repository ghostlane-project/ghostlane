//! Links, lists and crypt1 for the CLI, as the Go CLI and the app's Kotlin
//! parsers read them. The vectors under `sharedUI/src/commonTest/resources/
//! vectors/` hold this crate to both: `LinkVectorsTest` writes them from the
//! app's own code, `tests/vectors.rs` reads them here. `links.json` covers the
//! share links and import links, `crypt1.json` the encrypted blobs, and
//! `lists.json` the subscription bodies, headers, entries and selections.

pub mod crypt1;
pub mod import;
pub mod list;
pub mod olcrtc;
pub mod share;
pub mod text;

pub use crypt1::{decrypt, decrypt_link, is_link, parse_key, LINK_PREFIX};
pub use import::{country_of, import_payload};
pub use list::{
    crypt1_decryptor, decode_body, decode_body_with, device_headers, entries, group_by_country,
    parse_headers, refusal_text, select, Entry, Group, Headers, Kind, UserInfo,
};
pub use olcrtc::{normalize_provider, normalize_transport, parse_olcrtc, OlcrtcLine};
pub use share::{parse_hy2, parse_vless, Hy2Line, Transport, VlessLine};
