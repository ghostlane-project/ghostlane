//! Text helpers the share and list parsers need: form encoding that treats a
//! `+` as a space, UTF-8-safe percent decoding, and a one-line sanitizer for
//! the words a list host sends.

/// Percent and `+` decoding, as the app's `urlDecode`: a `%XX` is a byte, a
/// run of escapes is decoded as UTF-8 together, and a stray `%` or a run that
/// is not valid UTF-8 yields replacement characters rather than a refusal.
pub fn url_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = String::new();
    let mut pending: Vec<u8> = Vec::new();
    let flush = |pending: &mut Vec<u8>, out: &mut String| {
        if !pending.is_empty() {
            out.push_str(&String::from_utf8_lossy(pending));
            pending.clear();
        }
    };
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hi = hex(bytes[i + 1]);
                let lo = hex(bytes[i + 2]);
                if let (Some(hi), Some(lo)) = (hi, lo) {
                    pending.push((hi << 4) | lo);
                    i += 3;
                    continue;
                }
                flush(&mut pending, &mut out);
                out.push('%');
                i += 1;
            }
            b'+' => {
                flush(&mut pending, &mut out);
                out.push(' ');
                i += 1;
            }
            b if b < 0x80 => {
                flush(&mut pending, &mut out);
                out.push(b as char);
                i += 1;
            }
            _ => {
                flush(&mut pending, &mut out);
                let ch = s[i..].chars().next().unwrap();
                out.push(ch);
                i += ch.len_utf8();
            }
        }
    }
    flush(&mut pending, &mut out);
    out
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// A query as the app's `parseQuery` reads it: `k=v` pairs, percent decoded,
/// the last of a repeated key wins, a pair with no `=` is not a pair.
pub fn parse_query(q: &str) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = Vec::new();
    for part in q.split('&') {
        let (k, v) = match part.split_once('=') {
            Some((k, v)) => (url_decode(k), url_decode(v)),
            None => continue,
        };
        if let Some(entry) = out.iter_mut().find(|(key, _)| *key == k) {
            entry.1 = v;
        } else {
            out.push((k, v));
        }
    }
    out
}

pub fn query_value<'a>(q: &'a [(String, String)], key: &str) -> &'a str {
    q.iter()
        .rev()
        .find(|(k, _)| k == key)
        .map(|(_, v)| v.as_str())
        .unwrap_or("")
}

/// Whether a link flag is set: `1` or `true`, exactly as the Go parser reads.
pub fn truthy(s: &str) -> bool {
    s == "1" || s == "true"
}

/// One line of at most `limit` characters: control and whitespace runs become
/// one space, direction-changing format characters are dropped, joiners stay.
pub fn one_line(s: &str, limit: usize) -> String {
    let mut out: Vec<char> = Vec::new();
    let mut gap = false;
    for ch in s.chars() {
        let cp = ch as u32;
        if (0x202A..=0x202E).contains(&cp)
            || (0x2066..=0x2069).contains(&cp)
            || matches!(cp, 0x200E | 0x200F | 0x061C | 0xFEFF)
        {
            continue;
        }
        if ch.is_whitespace() || ch.is_control() {
            gap = !out.is_empty();
            continue;
        }
        if gap {
            out.push(' ');
            gap = false;
        }
        if out.len() < limit {
            out.push(ch);
        }
    }
    while out.last() == Some(&' ') {
        out.pop();
    }
    out.into_iter().collect()
}

/// The device headers the CLI sends: printable ASCII, at most 64 characters,
/// trimmed at the edges, empty when nothing printable remains.
pub fn ascii_header(s: &str) -> String {
    let kept: String = s
        .bytes()
        .filter(|b| (b' '..=b'~').contains(b))
        .take(64)
        .map(|b| b as char)
        .collect();
    kept.trim().to_string()
}
