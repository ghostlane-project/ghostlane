//! What a plain scalar is, as yaml.v3's `resolve` decides it.
//!
//! The writer asks so that a string that would read back as something else
//! goes out in quotes; the reader asks to know what a value is.

use crate::time::Timestamp;

/// What a plain scalar reads as. A string carries nothing here: its text is
/// the scalar's own.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum Resolved {
    Null,
    Bool(bool),
    Int(i64),
    /// An integer above what an `int64` holds.
    Uint,
    Float(f64),
    Time(Timestamp),
    Str,
}

impl Resolved {
    /// yaml.v3's short tag for it.
    pub(crate) fn tag(&self) -> &'static str {
        match self {
            Resolved::Null => "!!null",
            Resolved::Bool(_) => "!!bool",
            Resolved::Int(_) | Resolved::Uint => "!!int",
            Resolved::Float(_) => "!!float",
            Resolved::Time(_) => "!!timestamp",
            Resolved::Str => "!!str",
        }
    }
}

/// yaml.v3's `resolve` for a scalar with no tag of its own. `timestamps`
/// is off where yaml.v3 does not try one: under a tag other than
/// `!!timestamp`.
pub(crate) fn resolve(text: &str, timestamps: bool) -> Resolved {
    // the first byte says what the scalar may be; anything else is a string
    let numeric = match text.as_bytes().first() {
        None
        | Some(b'y' | b'Y' | b'n' | b'N' | b't' | b'T' | b'f' | b'F' | b'o' | b'O' | b'~' | b'.') => {
            false
        }
        Some(b'+' | b'-' | b'0'..=b'9') => true,
        Some(_) => return Resolved::Str,
    };
    match text {
        "true" | "True" | "TRUE" => return Resolved::Bool(true),
        "false" | "False" | "FALSE" => return Resolved::Bool(false),
        "" | "~" | "null" | "Null" | "NULL" => return Resolved::Null,
        ".nan" | ".NaN" | ".NAN" => return Resolved::Float(f64::NAN),
        ".inf" | ".Inf" | ".INF" | "+.inf" | "+.Inf" | "+.INF" => {
            return Resolved::Float(f64::INFINITY)
        }
        "-.inf" | "-.Inf" | "-.INF" => return Resolved::Float(f64::NEG_INFINITY),
        _ => {}
    }
    if text.starts_with('.') {
        return parse_float(text).map_or(Resolved::Str, Resolved::Float);
    }
    if !numeric {
        return Resolved::Str;
    }
    if timestamps {
        if let Some(time) = Timestamp::parse_yaml(text) {
            return Resolved::Time(time);
        }
    }
    let plain = text.replace('_', "");
    if let Some(int) = parse_int(&plain, 0) {
        return Resolved::Int(int);
    }
    if parse_uint(&plain, 0).is_some() {
        return Resolved::Uint;
    }
    if yaml_style_float(&plain) {
        if let Some(float) = parse_float(&plain) {
            return Resolved::Float(float);
        }
    }
    // reached only by what Go's own prefixes did not take, such as "0b-1"
    for (prefix, negative_prefix, base) in [("0b", "-0b", 2), ("0o", "-0o", 8)] {
        if let Some(digits) = plain.strip_prefix(prefix) {
            if let Some(int) = parse_int(digits, base) {
                return Resolved::Int(int);
            }
            if parse_uint(digits, base).is_some() {
                return Resolved::Uint;
            }
        } else if let Some(digits) = plain.strip_prefix(negative_prefix) {
            if let Some(int) = parse_int(&format!("-{digits}"), base) {
                return Resolved::Int(int);
            }
        }
    }
    Resolved::Str
}

/// The words YAML 1.1 read as booleans. yaml.v3 does not read them so, and
/// still quotes them on the way out, for the parsers that do.
pub(crate) fn old_bool(text: &str) -> Option<bool> {
    match text {
        "y" | "Y" | "yes" | "Yes" | "YES" | "on" | "On" | "ON" => Some(true),
        "n" | "N" | "no" | "No" | "NO" | "off" | "Off" | "OFF" => Some(false),
        _ => None,
    }
}

/// A sexagesimal number of YAML 1.1 (`1:30:00`), quoted on the way out like
/// the old booleans: `^[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+(?:\.[0-9_]*)?$`.
pub(crate) fn is_base60_float(text: &str) -> bool {
    let bytes = text.as_bytes();
    let digit_or_underscore = |at: usize| {
        bytes
            .get(at)
            .is_some_and(|byte| byte.is_ascii_digit() || *byte == b'_')
    };
    let mut at = usize::from(matches!(bytes.first(), Some(b'+' | b'-')));
    if !bytes.get(at).is_some_and(u8::is_ascii_digit) {
        return false;
    }
    while digit_or_underscore(at) {
        at += 1;
    }
    let mut groups = 0;
    while bytes.get(at) == Some(&b':') {
        // one digit, or two when the first is at most 5
        let digits = bytes[at + 1..]
            .iter()
            .take_while(|byte| byte.is_ascii_digit())
            .count();
        if !(digits == 1 || (digits == 2 && bytes[at + 1] <= b'5')) {
            return false;
        }
        at += 1 + digits;
        groups += 1;
    }
    if groups == 0 {
        return false;
    }
    if bytes.get(at) == Some(&b'.') {
        at += 1;
        while digit_or_underscore(at) {
            at += 1;
        }
    }
    at == bytes.len()
}

/// yaml.v3's gate before it tries a float:
/// `^[-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?$`.
fn yaml_style_float(text: &str) -> bool {
    let bytes = text.as_bytes();
    let digits_from = |at: usize| {
        bytes[at..]
            .iter()
            .take_while(|byte| byte.is_ascii_digit())
            .count()
    };
    let mut at = usize::from(matches!(bytes.first(), Some(b'+' | b'-')));
    if bytes.get(at) == Some(&b'.') {
        let digits = digits_from(at + 1);
        if digits == 0 {
            return false;
        }
        at += 1 + digits;
    } else {
        let digits = digits_from(at);
        if digits == 0 {
            return false;
        }
        at += digits;
        if bytes.get(at) == Some(&b'.') {
            at += 1 + digits_from(at + 1);
        }
    }
    if matches!(bytes.get(at), Some(b'e' | b'E')) {
        at += 1;
        if matches!(bytes.get(at), Some(b'+' | b'-')) {
            at += 1;
        }
        let digits = digits_from(at);
        if digits == 0 {
            return false;
        }
        at += digits;
    }
    at == bytes.len()
}

/// Go's `strconv.ParseFloat` for a decimal number: Go's syntax (an
/// underscore may stand between digits), and a number too large for a
/// float64 is an error, not an infinity.
fn parse_float(text: &str) -> Option<f64> {
    let bytes = text.as_bytes();
    let mut at = usize::from(matches!(bytes.first(), Some(b'+' | b'-')));
    let (mut digits, mut dot, mut underscores) = (false, false, false);
    while let Some(byte) = bytes.get(at).copied() {
        match byte {
            b'_' => underscores = true,
            b'.' if !dot => dot = true,
            b'0'..=b'9' => digits = true,
            _ => break,
        }
        at += 1;
    }
    if !digits {
        return None;
    }
    if matches!(bytes.get(at), Some(b'e' | b'E')) {
        at += 1;
        if matches!(bytes.get(at), Some(b'+' | b'-')) {
            at += 1;
        }
        if !bytes.get(at).is_some_and(u8::is_ascii_digit) {
            return None;
        }
        while let Some(byte) = bytes
            .get(at)
            .filter(|byte| byte.is_ascii_digit() || **byte == b'_')
        {
            underscores |= *byte == b'_';
            at += 1;
        }
    }
    if at != bytes.len() || (underscores && !underscores_between_digits(text)) {
        return None;
    }
    let value: f64 = text.replace('_', "").parse().ok()?;
    value.is_finite().then_some(value)
}

/// Go's `underscoreOK` for a decimal number: an underscore follows a digit
/// and is followed by one.
fn underscores_between_digits(text: &str) -> bool {
    let digits = text.strip_prefix(['+', '-']).unwrap_or(text);
    let mut last = '^';
    for c in digits.chars() {
        if c.is_ascii_digit() {
            last = '0';
        } else if c == '_' {
            if last != '0' {
                return false;
            }
            last = '_';
        } else {
            if last == '_' {
                return false;
            }
            last = '!';
        }
    }
    last != '_'
}

/// Go's `strconv.ParseUint(text, base, 64)`, with base 0 taking the base
/// from the prefix: `0x`, `0o`, `0b`, or a bare leading zero for octal.
fn parse_uint(text: &str, base: u32) -> Option<u64> {
    let bytes = text.as_bytes();
    let (base, digits) = match (base, *bytes.first()?) {
        (0, b'0') => match bytes.get(1).map(u8::to_ascii_lowercase) {
            Some(b'b') if bytes.len() >= 3 => (2, &bytes[2..]),
            Some(b'o') if bytes.len() >= 3 => (8, &bytes[2..]),
            Some(b'x') if bytes.len() >= 3 => (16, &bytes[2..]),
            _ => (8, &bytes[1..]),
        },
        (0, _) => (10, bytes),
        _ => (base, bytes),
    };
    let mut value: u64 = 0;
    for &byte in digits {
        let digit = match byte {
            b'0'..=b'9' => byte - b'0',
            b'a'..=b'z' => byte - b'a' + 10,
            b'A'..=b'Z' => byte - b'A' + 10,
            _ => return None,
        };
        if u32::from(digit) >= base {
            return None;
        }
        value = value
            .checked_mul(u64::from(base))?
            .checked_add(u64::from(digit))?;
    }
    Some(value)
}

/// Go's `strconv.ParseInt(text, base, 64)`.
fn parse_int(text: &str, base: u32) -> Option<i64> {
    let (negative, digits) = match *text.as_bytes().first()? {
        b'+' => (false, &text[1..]),
        b'-' => (true, &text[1..]),
        _ => (false, text),
    };
    let magnitude = parse_uint(digits, base)?;
    if negative {
        0_i64.checked_sub_unsigned(magnitude)
    } else {
        i64::try_from(magnitude).ok()
    }
}
