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
    todo!()
}

/// Why a document was not read. The text is yaml.v3's where a person would
/// act on it (which line, which value, into what).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Error(pub(crate) String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        todo!()
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
    todo!()
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
    todo!()
}

impl Decoder {
    /// A Go `string`: any scalar, as its text.
    pub fn string(&mut self, node: &Node, out: &mut String) -> Result<bool, Error> {
        todo!()
    }

    /// A Go `int`: an integer, or a float cut to one.
    pub fn int(&mut self, node: &Node, out: &mut i64) -> Result<bool, Error> {
        todo!()
    }

    /// A Go `bool`: true or false, or one of YAML 1.1's words for them.
    pub fn boolean(&mut self, node: &Node, out: &mut bool) -> Result<bool, Error> {
        todo!()
    }

    /// A Go `time.Time`: a YAML timestamp, or any other scalar that is RFC
    /// 3339 text (which `time.Time` reads itself; what it cannot read ends
    /// the reading).
    pub fn time(&mut self, node: &Node, out: &mut Timestamp) -> Result<bool, Error> {
        todo!()
    }

    /// A Go struct: a mapping, key by key.
    pub fn record<T: Record>(&mut self, node: &Node, out: &mut T) -> Result<bool, Error> {
        todo!()
    }

    /// A Go pointer to a struct: a null makes it nil; anything else is read
    /// into the struct it points to, a new one when it was nil.
    pub fn pointer<T: Record + Default>(
        &mut self,
        node: &Node,
        out: &mut Option<T>,
    ) -> Result<bool, Error> {
        todo!()
    }

    /// A Go slice of structs: a sequence, without the items that set
    /// nothing; a null makes it nil.
    pub fn sequence<T: Record + Default>(
        &mut self,
        node: &Node,
        out: &mut Vec<T>,
    ) -> Result<bool, Error> {
        todo!()
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
        todo!()
    }

    /// yaml.v3 refuses a mapping that names a key twice, whatever it is
    /// read into.
    fn unique_keys(&mut self, pairs: &[(Node, Node)]) -> bool {
        todo!()
    }

    /// A mapping or a sequence where the field takes a scalar.
    fn not_a_scalar(&mut self, node: &Node, into: &str) -> bool {
        todo!()
    }

    /// yaml.v3's "cannot unmarshal" line.
    fn mismatch(&mut self, node: &Node, into: &str) {
        todo!()
    }
}

fn is_merge(key: &Node) -> bool {
    todo!()
}

fn merge_wants_maps() -> Error {
    todo!()
}

/// What yaml.v3 compares two keys by: the kind of node and a scalar's text.
fn identity(node: &Node) -> (u8, &str) {
    todo!()
}

fn line_of(node: &Node) -> usize {
    todo!()
}
