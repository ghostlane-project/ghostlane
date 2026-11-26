//! What a daemon log may say. A list URL carries a token, a room's `#` part
//! carries its key and a VLESS line carries a UUID: none of them is a log.

/// Hide keys, VLESS UUIDs, list URLs and credentials in one line.
pub fn scrub(text: &str) -> String {
    let text = hide_hex(text);
    let text = hide_uuid(&text);
    let text = hide_urls(&text);
    let text = hide_sub_paths(&text);
    hide_credentials(&text)
}

fn is_hex(byte: u8) -> bool {
    byte.is_ascii_hexdigit()
}

fn hide_hex(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut at = 0;
    while at < bytes.len() {
        if !is_hex(bytes[at]) {
            out.push(bytes[at] as char);
            at += 1;
            continue;
        }
        let start = at;
        while at < bytes.len() && is_hex(bytes[at]) {
            at += 1;
        }
        let mut run = start;
        while run + 64 <= at {
            out.push_str("<key>");
            run += 64;
        }
        for byte in &bytes[run..at] {
            out.push(*byte as char);
        }
    }
    out
}

fn hide_uuid(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut at = 0;
    while at < bytes.len() {
        if let Some(end) = uuid_at(bytes, at) {
            out.push_str("<uuid>");
            at = end;
            continue;
        }
        let ch = text[at..].chars().next().expect("a byte at the cursor");
        out.push(ch);
        at += ch.len_utf8();
    }
    out
}

fn uuid_at(bytes: &[u8], at: usize) -> Option<usize> {
    let hex = |from: usize, count: usize| {
        bytes
            .get(from..from + count)
            .is_some_and(|part| part.iter().all(|b| is_hex(*b)))
    };
    if !hex(at, 8)
        || bytes.get(at + 8) != Some(&b'-')
        || !hex(at + 9, 4)
        || bytes.get(at + 13) != Some(&b'-')
        || !hex(at + 14, 4)
        || bytes.get(at + 18) != Some(&b'-')
        || !hex(at + 19, 4)
        || bytes.get(at + 23) != Some(&b'-')
        || !hex(at + 24, 12)
    {
        return None;
    }
    Some(at + 36)
}

fn hide_urls(text: &str) -> String {
    replace_matches(text, |bytes, at| {
        let rest = bytes.get(at..)?;
        let after = if rest.starts_with(b"https://") {
            at + 8
        } else if rest.starts_with(b"http://") {
            at + 7
        } else {
            return None;
        };
        let mut end = after;
        while end < bytes.len() && !b" \t\n\r\"'<>".contains(&bytes[end]) {
            end += 1;
        }
        if end == after {
            return None;
        }
        let url = std::str::from_utf8(&bytes[at..end]).ok()?;
        Some((end, crate::store::mask_url(url)))
    })
}

fn hide_sub_paths(text: &str) -> String {
    replace_matches(text, |bytes, at| {
        if !bytes[at..].starts_with(b"/sub/") {
            return None;
        }
        let mut end = at + 5;
        while end < bytes.len() && !b"/ \t\n\r?#\"'".contains(&bytes[end]) {
            end += 1;
        }
        if end == at + 5 {
            return None;
        }
        if bytes.get(end) == Some(&b'/') {
            let mut second = end + 1;
            while second < bytes.len() && !b"/ \t\n\r?#\"'".contains(&bytes[second]) {
                second += 1;
            }
            if second > end + 1 {
                end = second;
            }
        }
        let path = std::str::from_utf8(&bytes[at..end]).ok()?;
        let masked = crate::store::mask_url(&format!("x://h{path}"));
        Some((end, masked.trim_start_matches("x://h").to_string()))
    })
}

fn hide_credentials(text: &str) -> String {
    replace_matches(text, |bytes, at| {
        let rest = bytes.get(at..)?;
        let names: [&[u8]; 4] = [b"token=", b"key=", b"password=", b"pass="];
        let name = names.iter().find(|name| {
            rest.len() >= name.len() && rest[..name.len()].eq_ignore_ascii_case(name)
        })?;
        let mut end = at + name.len();
        while end < bytes.len() && !b"&\t\n\r \"'".contains(&bytes[end]) {
            end += 1;
        }
        if end == at + name.len() {
            return None;
        }
        let word = String::from_utf8_lossy(name).to_ascii_lowercase();
        Some((end, format!("{word}=…")))
    })
}

/// Walk a string byte by byte; a match replaces its span and moves the cursor.
fn replace_matches(text: &str, find: impl Fn(&[u8], usize) -> Option<(usize, String)>) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut at = 0;
    while at < bytes.len() {
        if let Some((end, replacement)) = find(bytes, at) {
            out.push_str(&replacement);
            at = end;
            continue;
        }
        let ch = text[at..].chars().next().expect("a byte at the cursor");
        out.push(ch);
        at += ch.len_utf8();
    }
    out
}
