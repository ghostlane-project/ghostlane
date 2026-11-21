//! Go's ways of printing a string, bytes and a system error.

use std::fmt::Write as _;
use std::io;

/// A string as Go's `%q` prints it: in double quotes, with Go's escapes.
///
/// Go leaves a character as it is when its Unicode tables call it printable.
/// Those tables are not here: a control character, a space other than U+0020
/// and the common invisible characters are escaped, the rest is left.
pub fn quote(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{7}' => out.push_str("\\a"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{b}' => out.push_str("\\v"),
            c if printable(c) => out.push(c),
            c => {
                let code = u32::from(c);
                let _ = if code < 0x20 || code == 0x7f {
                    write!(out, "\\x{code:02x}")
                } else if code < 0x1_0000 {
                    write!(out, "\\u{code:04x}")
                } else {
                    write!(out, "\\U{code:08x}")
                };
            }
        }
    }
    out.push('"');
    out
}

fn printable(c: char) -> bool {
    if c == ' ' {
        return true;
    }
    let invisible = matches!(
        c,
        '\u{ad}' | '\u{200b}'..='\u{200f}' | '\u{2028}'..='\u{202e}' | '\u{2060}'..='\u{206f}' | '\u{feff}' | '\u{fff9}'..='\u{fffb}'
    );
    !(c.is_control() || c.is_whitespace() || invisible)
}

/// Go's `hex.EncodeToString`: two small hex digits a byte.
pub fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

/// The text of a system error as Go prints it: the C library's sentence with
/// a small first letter and no number after it ("no such file or directory").
pub fn errno_text(err: &io::Error) -> String {
    let text = err.to_string();
    let sentence = match text.rfind(" (os error ") {
        Some(at) if err.raw_os_error().is_some() => &text[..at],
        _ => text.as_str(),
    };
    let mut chars = sentence.chars();
    match chars.next() {
        Some(first) => first.to_lowercase().chain(chars).collect(),
        None => String::new(),
    }
}
