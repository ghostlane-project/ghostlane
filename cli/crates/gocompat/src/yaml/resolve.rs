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
        todo!()
    }
}

/// yaml.v3's `resolve` for a scalar with no tag of its own. `timestamps`
/// is off where yaml.v3 does not try one: under a tag other than
/// `!!timestamp`.
pub(crate) fn resolve(text: &str, timestamps: bool) -> Resolved {
    todo!()
}

/// The words YAML 1.1 read as booleans. yaml.v3 does not read them so, and
/// still quotes them on the way out, for the parsers that do.
pub(crate) fn old_bool(text: &str) -> Option<bool> {
    todo!()
}

/// A sexagesimal number of YAML 1.1 (`1:30:00`), quoted on the way out like
/// the old booleans: `^[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+(?:\.[0-9_]*)?$`.
pub(crate) fn is_base60_float(text: &str) -> bool {
    todo!()
}

/// yaml.v3's gate before it tries a float:
/// `^[-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?$`.
fn yaml_style_float(text: &str) -> bool {
    todo!()
}

/// Go's `strconv.ParseFloat` for a decimal number: Go's syntax (an
/// underscore may stand between digits), and a number too large for a
/// float64 is an error, not an infinity.
fn parse_float(text: &str) -> Option<f64> {
    todo!()
}

/// Go's `underscoreOK` for a decimal number: an underscore follows a digit
/// and is followed by one.
fn underscores_between_digits(text: &str) -> bool {
    todo!()
}

/// Go's `strconv.ParseUint(text, base, 64)`, with base 0 taking the base
/// from the prefix: `0x`, `0o`, `0b`, or a bare leading zero for octal.
fn parse_uint(text: &str, base: u32) -> Option<u64> {
    todo!()
}

/// Go's `strconv.ParseInt(text, base, 64)`.
fn parse_int(text: &str, base: u32) -> Option<i64> {
    todo!()
}
