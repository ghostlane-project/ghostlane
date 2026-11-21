//! Go's `encoding/json` output.
//!
//! The same structure as any JSON writer's, with Go's choices where a writer
//! has one: `<`, `>`, `&`, U+2028 and U+2029 are written as `\u` escapes, a
//! `[]byte` is base64 in a string and `null` when it is nil.

use std::io;

use serde::ser::Serialize;
use serde_json::ser::{CompactFormatter, Formatter, PrettyFormatter};

/// `json.Marshal`: one line, no spaces, no newline after it.
pub fn marshal<T: Serialize + ?Sized>(value: &T) -> serde_json::Result<Vec<u8>> {
    todo!()
}

/// `json.NewEncoder(w).Encode`: `marshal` and a newline.
pub fn encode_line<T: Serialize + ?Sized>(value: &T) -> serde_json::Result<Vec<u8>> {
    todo!()
}

/// An encoder after `SetIndent("", "  ")`: two spaces a level, and a newline.
pub fn encode_indent<T: Serialize + ?Sized>(value: &T) -> serde_json::Result<Vec<u8>> {
    todo!()
}

/// The inner formatter's structure, with Go's escapes inside strings.
struct GoStrings<F>(F);

impl<F: Formatter> Formatter for GoStrings<F> {
    fn begin_array<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn end_array<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn begin_array_value<W: ?Sized + io::Write>(
        &mut self,
        writer: &mut W,
        first: bool,
    ) -> io::Result<()> {
        todo!()
    }

    fn end_array_value<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn begin_object<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn end_object<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn begin_object_key<W: ?Sized + io::Write>(
        &mut self,
        writer: &mut W,
        first: bool,
    ) -> io::Result<()> {
        todo!()
    }

    fn end_object_key<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn begin_object_value<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn end_object_value<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        todo!()
    }

    fn write_string_fragment<W: ?Sized + io::Write>(
        &mut self,
        writer: &mut W,
        fragment: &str,
    ) -> io::Result<()> {
        todo!()
    }
}

/// A Go `[]byte` field, for `#[serde(with = "gocompat::json::bytes")]`: nil
/// is `null`, anything else (an empty slice too) is base64 in a string.
pub mod bytes {
    use base64::engine::general_purpose::STANDARD;
    use base64::Engine as _;
    use serde::de::{Deserialize, Deserializer, Error as _};
    use serde::ser::Serializer;

    pub fn serialize<S: Serializer>(
        value: &Option<Vec<u8>>,
        serializer: S,
    ) -> Result<S::Ok, S::Error> {
        todo!()
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(
        deserializer: D,
    ) -> Result<Option<Vec<u8>>, D::Error> {
        todo!()
    }
}
