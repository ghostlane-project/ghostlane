//! A YAML document as a tree of nodes, the way yaml.v3 hands one to its
//! decoder: every scalar with the tag yaml.v3 gives it, every alias
//! replaced by what it names.

use std::collections::HashMap;

use yaml_rust2::parser::{Event, Parser, Tag};
use yaml_rust2::scanner::{Marker, TScalarStyle};

use super::resolve::resolve;
use super::Error;

/// Deeper than any state file, and shallow enough for the stack.
const MAX_DEPTH: usize = 128;

#[derive(Clone, Debug)]
pub enum Node {
    Scalar {
        value: String,
        /// yaml.v3's short tag: the one written, `!!str` for a quoted or
        /// block scalar, else what the plain text resolves to.
        tag: String,
        line: usize,
    },
    Mapping {
        pairs: Vec<(Node, Node)>,
        line: usize,
    },
    Sequence {
        items: Vec<Node>,
        line: usize,
    },
}

/// The first document of `text`, or nothing when there is none (an empty
/// file, or comments only). yaml.v3 reads no further than that either.
pub(crate) fn parse(text: &str) -> Result<Option<Node>, Error> {
    todo!()
}

struct Reader<'a> {
    parser: Parser<std::str::Chars<'a>>,
    anchors: HashMap<usize, Node>,
}

impl Reader<'_> {
    fn next(&mut self) -> Result<(Event, Marker), Error> {
        todo!()
    }

    fn node(&mut self, event: Event, mark: Marker, depth: usize) -> Result<Node, Error> {
        todo!()
    }
}

/// The tag yaml.v3's parser gives a scalar.
fn scalar_tag(value: &str, style: TScalarStyle, tag: Option<&Tag>) -> String {
    todo!()
}
