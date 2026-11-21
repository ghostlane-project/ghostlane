//! `gopkg.in/yaml.v3` as the Go CLI uses it: `yaml.Marshal` of a struct and
//! `yaml.Unmarshal` into one.
//!
//! Writing is yaml.v3's own emitter, step for step (`emit`), so a file comes
//! out with the same bytes. Reading takes the document apart with a YAML
//! parser and then decodes it by yaml.v3's rules: what a plain scalar is
//! (`resolve`), what each kind of Go field takes from it, what is an error
//! and what is passed over.

mod emit;
mod read;
mod resolve;

use std::borrow::Cow;
use std::collections::HashSet;
use std::fmt;

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;

pub use self::read::Node;
use self::resolve::{old_bool, resolve, Resolved};
use crate::text::quote;
use crate::time::Timestamp;

/// A document to write, in the shapes a Go struct takes.
#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int(i64),
    Str(String),
    Time(Timestamp),
    Seq(Vec<Value>),
    /// A struct: its fields that are written, in the struct's order.
    Map(Vec<(&'static str, Value)>),
}

/// `yaml.Marshal`: indented by four, strings quoted only where they would
/// read back as something else, an empty list as `[]`.
pub fn marshal(root: &Value) -> String {
    emit::marshal(root)
}

/// Why a document was not read. The text is yaml.v3's where a person would
/// act on it (which line, which value, into what).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Error(pub(crate) String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

/// A Go struct a mapping is read into.
pub trait Record {
    /// The Go type's name, as yaml.v3 prints it in an error ("store.Config").
    const TYPE: &'static str;
    /// The keys of the struct's fields.
    const FIELDS: &'static [&'static str];
    /// Reads `value` into the field `FIELDS[index]`, with the decoder's
    /// method for the field's Go type.
    fn field(&mut self, decoder: &mut Decoder, index: usize, value: &Node) -> Result<(), Error>;
}

/// `yaml.Unmarshal(document, out)`: the first document of the file over
/// what `out` already holds. A key the struct does not have is passed over,
/// a null leaves a field as it is, and a document that is empty changes
/// nothing.
pub fn unmarshal<T: Record>(document: &[u8], out: &mut T) -> Result<(), Error> {
    // yaml.v3 also reads UTF-16 behind a byte order mark; no state file is that
    let text = std::str::from_utf8(document)
        .map_err(|_| Error("yaml: the document is not UTF-8".to_string()))?;
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let mut decoder = Decoder {
        errors: Vec::new(),
        merged: None,
    };
    if let Some(root) = read::parse(text)? {
        decoder.record(&root, out)?;
    }
    if decoder.errors.is_empty() {
        Ok(())
    } else {
        Err(Error(format!(
            "yaml: unmarshal errors:\n  {}",
            decoder.errors.join("\n  ")
        )))
    }
}

/// yaml.v3's decoder. A value of the wrong kind is noted and the rest of the
/// document is still read (all of them are reported together, and the
/// document is refused); a value yaml.v3 gives up on ends the reading at
/// once. A method says `false` when it set nothing.
pub struct Decoder {
    errors: Vec<String>,
    /// While a `<<` merge is read: the keys already seen, which win.
    merged: Option<HashSet<String>>,
}

/// Reads the value of the field at an index of a struct's keys.
type Setter<'a> = dyn FnMut(&mut Decoder, usize, &Node) -> Result<(), Error> + 'a;

/// What a scalar is to the decoder.
enum Scalar<'a> {
    Null,
    Bool(bool),
    Int(i64),
    Uint,
    Float(f64),
    Time(Timestamp),
    /// A string: the scalar's own text, or what a `!!binary` holds.
    Text(Cow<'a, str>),
}

/// yaml.v3's `resolve(tag, value)` on a node, and its `!!binary`.
fn scalar<'a>(value: &'a str, tag: &str) -> Result<Scalar<'a>, Error> {
    if tag == "!!binary" {
        let bytes = STANDARD
            .decode(value.as_bytes())
            .map_err(|_| Error("yaml: !!binary value contains invalid base64 data".to_string()))?;
        // Go keeps any bytes in a string; here a string is text
        let text = String::from_utf8(bytes).map_err(|_| {
            Error("yaml: a !!binary value that is not UTF-8 text cannot be read here".to_string())
        })?;
        return Ok(Scalar::Text(Cow::Owned(text)));
    }
    // `!!str`, and every tag yaml.v3 does not resolve: the text as it is
    if !matches!(
        tag,
        "!!bool" | "!!int" | "!!float" | "!!null" | "!!timestamp"
    ) {
        return Ok(Scalar::Text(Cow::Borrowed(value)));
    }
    let found = resolve(value, tag == "!!timestamp");
    if found.tag() != tag {
        // a tag that the value does not bear out ends the reading, but for
        // an integer under `!!float`
        return match found {
            Resolved::Int(int) if tag == "!!float" => Ok(Scalar::Float(int as f64)),
            _ => Err(Error(format!(
                "yaml: cannot decode {} `{value}` as a {tag}",
                found.tag()
            ))),
        };
    }
    Ok(match found {
        Resolved::Null => Scalar::Null,
        Resolved::Bool(flag) => Scalar::Bool(flag),
        Resolved::Int(int) => Scalar::Int(int),
        Resolved::Uint => Scalar::Uint,
        Resolved::Float(float) => Scalar::Float(float),
        Resolved::Time(time) => Scalar::Time(time),
        Resolved::Str => Scalar::Text(Cow::Borrowed(value)),
    })
}

impl Decoder {
    /// A Go `string`: any scalar, as its text.
    pub fn string(&mut self, node: &Node, out: &mut String) -> Result<bool, Error> {
        let Node::Scalar { value, tag, .. } = node else {
            return Ok(self.not_a_scalar(node, "string"));
        };
        match scalar(value, tag)? {
            Scalar::Null => return Ok(false),
            Scalar::Text(text) => *out = text.into_owned(),
            _ => out.clone_from(value),
        }
        Ok(true)
    }

    /// A Go `int`: an integer, or a float cut to one.
    pub fn int(&mut self, node: &Node, out: &mut i64) -> Result<bool, Error> {
        let Node::Scalar { value, tag, .. } = node else {
            return Ok(self.not_a_scalar(node, "int"));
        };
        match scalar(value, tag)? {
            Scalar::Null => return Ok(false),
            Scalar::Int(int) => *out = int,
            Scalar::Float(float) if float <= i64::MAX as f64 => *out = float as i64,
            _ => {
                self.mismatch(node, "int");
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// A Go `bool`: true or false, or one of YAML 1.1's words for them.
    pub fn boolean(&mut self, node: &Node, out: &mut bool) -> Result<bool, Error> {
        let Node::Scalar { value, tag, .. } = node else {
            return Ok(self.not_a_scalar(node, "bool"));
        };
        let read = match scalar(value, tag)? {
            Scalar::Null => return Ok(false),
            Scalar::Bool(flag) => Some(flag),
            Scalar::Text(text) => old_bool(&text),
            _ => None,
        };
        match read {
            Some(flag) => *out = flag,
            None => {
                self.mismatch(node, "bool");
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// A Go `time.Time`: a YAML timestamp, or any other scalar that is RFC
    /// 3339 text (which `time.Time` reads itself; what it cannot read ends
    /// the reading).
    pub fn time(&mut self, node: &Node, out: &mut Timestamp) -> Result<bool, Error> {
        match node {
            Node::Scalar { value, tag, .. } => {
                let text = match scalar(value, tag)? {
                    Scalar::Null => return Ok(false),
                    Scalar::Time(time) => {
                        *out = time;
                        return Ok(true);
                    }
                    Scalar::Text(text) if tag == "!!binary" => text,
                    _ => Cow::Borrowed(value.as_str()),
                };
                let parsed = Timestamp::parse_rfc3339(&text);
                *out = parsed.ok_or_else(|| {
                    Error(format!(
                        "parsing time {} as \"2006-01-02T15:04:05Z07:00\"",
                        quote(&text)
                    ))
                })?;
                Ok(true)
            }
            // to yaml.v3 a time is a struct with no field to set
            Node::Mapping { pairs, .. } => {
                if !self.unique_keys(pairs) {
                    return Ok(false);
                }
                self.fields(pairs, &[], &mut |_, _, _| Ok(()))?;
                Ok(true)
            }
            Node::Sequence { .. } => {
                self.mismatch(node, "time.Time");
                Ok(false)
            }
        }
    }

    /// A Go struct: a mapping, key by key.
    pub fn record<T: Record>(&mut self, node: &Node, out: &mut T) -> Result<bool, Error> {
        match node {
            Node::Scalar { value, tag, .. } => {
                if !matches!(scalar(value, tag)?, Scalar::Null) {
                    self.mismatch(node, T::TYPE);
                }
                Ok(false)
            }
            Node::Sequence { .. } => {
                self.mismatch(node, T::TYPE);
                Ok(false)
            }
            Node::Mapping { pairs, .. } => {
                if !self.unique_keys(pairs) {
                    return Ok(false);
                }
                self.fields(pairs, T::FIELDS, &mut |decoder, index, value| {
                    out.field(decoder, index, value)
                })?;
                Ok(true)
            }
        }
    }

    /// A Go pointer to a struct: a null makes it nil; anything else is read
    /// into the struct it points to, a new one when it was nil.
    pub fn pointer<T: Record + Default>(
        &mut self,
        node: &Node,
        out: &mut Option<T>,
    ) -> Result<bool, Error> {
        if let Node::Scalar { value, tag, .. } = node {
            if tag == "!!null" {
                scalar(value, tag)?;
                *out = None;
                return Ok(true);
            }
        }
        self.record(node, out.get_or_insert_with(T::default))
    }

    /// A Go slice of structs: a sequence, without the items that set
    /// nothing; a null makes it nil.
    pub fn sequence<T: Record + Default>(
        &mut self,
        node: &Node,
        out: &mut Vec<T>,
    ) -> Result<bool, Error> {
        let into = format!("[]{}", T::TYPE);
        match node {
            Node::Sequence { items, .. } => {
                let mut list = Vec::with_capacity(items.len());
                for item in items {
                    let mut element = T::default();
                    if self.record(item, &mut element)? {
                        list.push(element);
                    }
                }
                *out = list;
                Ok(true)
            }
            Node::Scalar { value, tag, .. } => {
                if matches!(scalar(value, tag)?, Scalar::Null) {
                    out.clear();
                    return Ok(true);
                }
                self.mismatch(node, &into);
                Ok(false)
            }
            Node::Mapping { .. } => Ok(self.not_a_scalar(node, &into)),
        }
    }

    /// yaml.v3's `mappingStruct`: each key that names a field is read into
    /// it; a `<<` key is a merge, read after the mapping's own keys, which
    /// win over it.
    fn fields(
        &mut self,
        pairs: &[(Node, Node)],
        names: &[&str],
        set: &mut Setter<'_>,
    ) -> Result<(), Error> {
        let mut seen = self.merged.take();
        let mut merge = None;
        for (key, value) in pairs {
            if is_merge(key) {
                merge = Some(value);
                continue;
            }
            let mut name = String::new();
            if !self.string(key, &mut name)? {
                continue;
            }
            if let Some(seen) = seen.as_mut() {
                if !seen.insert(name.clone()) {
                    continue;
                }
            }
            if let Some(index) = names.iter().position(|field| *field == name) {
                set(self, index, value)?;
            }
        }
        self.merged = seen;
        let Some(merge) = merge else {
            return Ok(());
        };
        let outermost = self.merged.is_none();
        if outermost {
            let own = pairs.iter().filter_map(|(key, _)| match key {
                Node::Scalar { value, tag, .. } if tag == "!!str" => Some(value.clone()),
                _ => None,
            });
            self.merged = Some(own.collect());
        }
        let sources = match merge {
            Node::Mapping { .. } => std::slice::from_ref(merge),
            Node::Sequence { items, .. } => items.as_slice(),
            Node::Scalar { .. } => return Err(merge_wants_maps()),
        };
        for source in sources {
            let Node::Mapping { pairs, .. } = source else {
                return Err(merge_wants_maps());
            };
            if self.unique_keys(pairs) {
                self.fields(pairs, names, set)?;
            }
        }
        if outermost {
            self.merged = None;
        }
        Ok(())
    }

    /// yaml.v3 refuses a mapping that names a key twice, whatever it is
    /// read into.
    fn unique_keys(&mut self, pairs: &[(Node, Node)]) -> bool {
        let before = self.errors.len();
        for (index, (first, _)) in pairs.iter().enumerate() {
            for (second, _) in &pairs[index + 1..] {
                if identity(first) == identity(second) {
                    let text = identity(second).1;
                    self.errors.push(format!(
                        "line {}: mapping key {} already defined at line {}",
                        line_of(second),
                        quote(text),
                        line_of(first)
                    ));
                }
            }
        }
        self.errors.len() == before
    }

    /// A mapping or a sequence where the field takes a scalar.
    fn not_a_scalar(&mut self, node: &Node, into: &str) -> bool {
        if let Node::Mapping { pairs, .. } = node {
            if !self.unique_keys(pairs) {
                return false;
            }
        }
        self.mismatch(node, into);
        false
    }

    /// yaml.v3's "cannot unmarshal" line.
    fn mismatch(&mut self, node: &Node, into: &str) {
        let what = match node {
            Node::Scalar { value, tag, .. } if value.len() > 10 => {
                let mut cut = 7;
                while !value.is_char_boundary(cut) {
                    cut -= 1;
                }
                format!("{tag} `{}...`", &value[..cut])
            }
            Node::Scalar { value, tag, .. } => format!("{tag} `{value}`"),
            Node::Mapping { .. } => "!!map".to_string(),
            Node::Sequence { .. } => "!!seq".to_string(),
        };
        self.errors.push(format!(
            "line {}: cannot unmarshal {what} into {into}",
            line_of(node)
        ));
    }
}

fn is_merge(key: &Node) -> bool {
    matches!(key, Node::Scalar { value, tag, .. } if value == "<<" && tag == "!!merge")
}

fn merge_wants_maps() -> Error {
    Error("yaml: map merge requires map or sequence of maps as the value".to_string())
}

/// What yaml.v3 compares two keys by: the kind of node and a scalar's text.
fn identity(node: &Node) -> (u8, &str) {
    match node {
        Node::Scalar { value, .. } => (0, value),
        Node::Mapping { .. } => (1, ""),
        Node::Sequence { .. } => (2, ""),
    }
}

fn line_of(node: &Node) -> usize {
    match node {
        Node::Scalar { line, .. } | Node::Mapping { line, .. } | Node::Sequence { line, .. } => {
            *line
        }
    }
}
