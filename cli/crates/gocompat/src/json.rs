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
    let mut out = Vec::new();
    let mut serializer =
        serde_json::Serializer::with_formatter(&mut out, GoStrings(CompactFormatter));
    value.serialize(&mut serializer)?;
    Ok(out)
}

/// `json.NewEncoder(w).Encode`: `marshal` and a newline.
pub fn encode_line<T: Serialize + ?Sized>(value: &T) -> serde_json::Result<Vec<u8>> {
    let mut out = marshal(value)?;
    out.push(b'\n');
    Ok(out)
}

/// An encoder after `SetIndent("", "  ")`: two spaces a level, and a newline.
pub fn encode_indent<T: Serialize + ?Sized>(value: &T) -> serde_json::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut serializer = serde_json::Serializer::with_formatter(
        &mut out,
        GoStrings(PrettyFormatter::with_indent(b"  ")),
    );
    value.serialize(&mut serializer)?;
    out.push(b'\n');
    Ok(out)
}

/// The inner formatter's structure, with Go's escapes inside strings.
struct GoStrings<F>(F);

impl<F: Formatter> Formatter for GoStrings<F> {
    fn begin_array<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.begin_array(writer)
    }

    fn end_array<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.end_array(writer)
    }

    fn begin_array_value<W: ?Sized + io::Write>(
        &mut self,
        writer: &mut W,
        first: bool,
    ) -> io::Result<()> {
        self.0.begin_array_value(writer, first)
    }

    fn end_array_value<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.end_array_value(writer)
    }

    fn begin_object<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.begin_object(writer)
    }

    fn end_object<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.end_object(writer)
    }

    fn begin_object_key<W: ?Sized + io::Write>(
        &mut self,
        writer: &mut W,
        first: bool,
    ) -> io::Result<()> {
        self.0.begin_object_key(writer, first)
    }

    fn end_object_key<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.end_object_key(writer)
    }

    fn begin_object_value<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.begin_object_value(writer)
    }

    fn end_object_value<W: ?Sized + io::Write>(&mut self, writer: &mut W) -> io::Result<()> {
        self.0.end_object_value(writer)
    }

    fn write_string_fragment<W: ?Sized + io::Write>(
        &mut self,
        writer: &mut W,
        fragment: &str,
    ) -> io::Result<()> {
        let mut rest = fragment;
        while let Some(at) = rest.find(['<', '>', '&', '\u{2028}', '\u{2029}']) {
            writer.write_all(rest[..at].as_bytes())?;
            let (escape, width) = match rest.as_bytes()[at] {
                b'<' => ("\\u003c", 1),
                b'>' => ("\\u003e", 1),
                b'&' => ("\\u0026", 1),
                _ if rest[at..].starts_with('\u{2028}') => ("\\u2028", 3),
                _ => ("\\u2029", 3),
            };
            writer.write_all(escape.as_bytes())?;
            rest = &rest[at + width..];
        }
        writer.write_all(rest.as_bytes())
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
        match value {
            None => serializer.serialize_none(),
            Some(bytes) => serializer.serialize_str(&STANDARD.encode(bytes)),
        }
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(
        deserializer: D,
    ) -> Result<Option<Vec<u8>>, D::Error> {
        match Option::<String>::deserialize(deserializer)? {
            None => Ok(None),
            Some(text) => STANDARD
                .decode(text.as_bytes())
                .map(Some)
                .map_err(D::Error::custom),
        }
    }
}
