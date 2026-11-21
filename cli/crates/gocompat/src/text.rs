//! Go's ways of printing a string, bytes and a system error.

use std::fmt::Write as _;
use std::io;

/// A string as Go's `%q` prints it: in double quotes, with Go's escapes.
///
/// Go leaves a character as it is when its Unicode tables call it printable.
/// Those tables are not here: a control character, a space other than U+0020
/// and the common invisible characters are escaped, the rest is left.
pub fn quote(text: &str) -> String {
    todo!()
}

fn printable(c: char) -> bool {
    todo!()
}

/// Go's `hex.EncodeToString`: two small hex digits a byte.
pub fn hex(bytes: &[u8]) -> String {
    todo!()
}

/// The text of a system error as Go prints it: the C library's sentence with
/// a small first letter and no number after it ("no such file or directory").
pub fn errno_text(err: &io::Error) -> String {
    todo!()
}
