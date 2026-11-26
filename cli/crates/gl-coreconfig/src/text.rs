//! Kotlin's reading of text where the app's builder leans on it: what is
//! blank, what `trim()` takes off, and `XrayConfig.isIpLiteral`.

/// Kotlin's `Char.isWhitespace()` on the JVM: Java's `isWhitespace` or
/// `isSpaceChar`. That is Unicode's space, line and paragraph separators
/// (no-break spaces among them), tab, line feed, the vertical tab, form
/// feed, carriage return and the four separators U+001C to U+001F; not
/// U+0085, which Rust's `char::is_whitespace` counts.
pub fn is_kotlin_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n' | '\u{b}' | '\u{c}' | '\r' | '\u{1c}'..='\u{1f}'
    ) || (c != '\u{85}' && c.is_whitespace())
}

/// `isBlank()`: empty, or only whitespace.
pub fn is_blank(text: &str) -> bool {
    text.chars().all(is_kotlin_whitespace)
}

/// `trim()`: whitespace off both ends.
pub fn trim(text: &str) -> &str {
    text.trim_matches(is_kotlin_whitespace)
}

/// `ifBlank { other }`.
pub fn if_blank(text: &str, other: &str) -> String {
    if is_blank(text) {
        other.to_string()
    } else {
        text.to_string()
    }
}

/// `toIntOrNull()`: an optional `+` or `-`, then decimal digits, within an
/// `Int`. (Kotlin also reads the digits of other scripts here; a server's
/// address written in them is read as a name.)
pub fn to_int_or_null(text: &str) -> Option<i32> {
    let digits = text.strip_prefix(['+', '-']).unwrap_or(text);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok()
}

/// `XrayConfig.isIpLiteral`: anything with a colon, or four dot-separated
/// numbers from 0 to 255 (`+1` and `01` among them, as Kotlin reads a number).
pub fn is_ip_literal(host: &str) -> bool {
    if host.contains(':') {
        return true;
    }
    let parts: Vec<&str> = host.split('.').collect();
    parts.len() == 4
        && parts
            .iter()
            .all(|p| matches!(to_int_or_null(p), Some(0..=255)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_is_kotlins() {
        assert!(is_blank(""));
        assert!(is_blank(" \t\n\u{a0}\u{2007}\u{202f}\u{3000}\u{1f}"));
        assert!(!is_blank("\u{85}"));
        assert!(!is_blank(" a "));
        assert_eq!(trim("\u{a0} x \u{1c}"), "x");
        assert_eq!(trim("\u{85}x"), "\u{85}x");
    }

    #[test]
    fn numbers_are_read_as_kotlin_reads_them() {
        assert_eq!(to_int_or_null("+1"), Some(1));
        assert_eq!(to_int_or_null("-0"), Some(0));
        assert_eq!(to_int_or_null("007"), Some(7));
        assert_eq!(to_int_or_null("+"), None);
        assert_eq!(to_int_or_null(""), None);
        assert_eq!(to_int_or_null("1 "), None);
        assert_eq!(to_int_or_null("2147483648"), None);
        assert_eq!(to_int_or_null("-2147483648"), Some(i32::MIN));
    }

    #[test]
    fn an_ip_literal_is_what_the_app_calls_one() {
        for host in [
            "127.0.0.1",
            "::1",
            "[fd00::1]",
            "fe80::1%eth0",
            "+1.2.3.4",
            "01.2.3.4",
            "-0.0.0.0",
        ] {
            assert!(is_ip_literal(host), "{host}");
        }
        for host in [
            "example.com",
            "256.1.1.1",
            "1.2.3",
            "1.2.3.4.5",
            "1.2.3.",
            " 1.2.3.4",
            "",
        ] {
            assert!(!is_ip_literal(host), "{host}");
        }
    }
}
