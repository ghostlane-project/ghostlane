//! One-tap import links: `ghostlane://add?url=…` and `ghostlane://add/<raw
//! list URL>`, as the CLI's Go `import.go` reads them.

use crate::text::parse_query;

pub fn import_payload(s: &str) -> String {
    let s = s.trim();
    let lower = s.to_lowercase();
    let rest = match lower.strip_prefix("ghostlane://") {
        Some(_) => &s["ghostlane://".len()..],
        None => return s.to_string(),
    };
    let lower_rest = lower.strip_prefix("ghostlane://").unwrap_or("");
    for host in ["add", "import"] {
        if lower_rest.starts_with(&format!("{host}?")) {
            let q = parse_query(&rest[host.len() + 1..]);
            let v = q
                .iter()
                .rev()
                .find(|(k, _)| k == "url")
                .map(|(_, v)| v.as_str())
                .unwrap_or("");
            return if v.is_empty() {
                s.to_string()
            } else {
                v.to_string()
            };
        }
        if lower_rest.starts_with(&format!("{host}/")) {
            return rest[host.len() + 1..].to_string();
        }
    }
    s.to_string()
}

/// The country a label names, as the CLI's Go `country.go` reads it: a leading
/// flag is stripped first, then one uppercase two-letter token.
pub fn country_of(label: &str) -> String {
    let mut s = label.trim();
    while let Some(ch) = s.chars().next() {
        if ('\u{1F1E6}'..='\u{1F1FF}').contains(&ch) {
            s = s[ch.len_utf8()..].trim();
        } else {
            break;
        }
    }
    let first = s
        .split(' ')
        .next()
        .unwrap_or("")
        .split('|')
        .next()
        .unwrap_or("")
        .trim();
    if first.len() == 2 && first.bytes().all(|b| b.is_ascii_uppercase()) {
        first.to_string()
    } else {
        String::new()
    }
}
