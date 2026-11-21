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
    let mut reader = Reader {
        parser: Parser::new_from_str(text),
        anchors: HashMap::new(),
    };
    loop {
        match reader.next()? {
            (Event::StreamStart, _) => {}
            (Event::DocumentStart, _) => break,
            _ => return Ok(None),
        }
    }
    let (event, mark) = reader.next()?;
    let root = reader.node(event, mark, 0)?;
    // what follows the root is looked at, so that an error right behind it
    // is an error here as it is in yaml.v3
    reader.next()?;
    Ok(Some(root))
}

struct Reader<'a> {
    parser: Parser<std::str::Chars<'a>>,
    anchors: HashMap<usize, Node>,
}

impl Reader<'_> {
    fn next(&mut self) -> Result<(Event, Marker), Error> {
        self.parser
            .next_token()
            .map_err(|err| Error(format!("yaml: {err}")))
    }

    fn node(&mut self, event: Event, mark: Marker, depth: usize) -> Result<Node, Error> {
        if depth > MAX_DEPTH {
            return Err(Error(format!(
                "yaml: line {}: the document is nested too deep",
                mark.line()
            )));
        }
        let line = mark.line();
        let (anchor, node) = match event {
            Event::Alias(anchor) => {
                // an anchor is on record once its node is whole, so an alias
                // inside its own node finds nothing
                let node = self.anchors.get(&anchor).cloned();
                return node.ok_or_else(|| {
                    Error(format!(
                        "yaml: line {line}: an anchor's value contains itself"
                    ))
                });
            }
            Event::Scalar(value, style, anchor, tag) => {
                let tag = scalar_tag(&value, style, tag.as_ref());
                (anchor, Node::Scalar { value, tag, line })
            }
            Event::SequenceStart(anchor, _) => {
                let mut items = Vec::new();
                loop {
                    let (event, mark) = self.next()?;
                    if matches!(event, Event::SequenceEnd) {
                        break;
                    }
                    items.push(self.node(event, mark, depth + 1)?);
                }
                (anchor, Node::Sequence { items, line })
            }
            Event::MappingStart(anchor, _) => {
                let mut pairs = Vec::new();
                loop {
                    let (event, mark) = self.next()?;
                    if matches!(event, Event::MappingEnd) {
                        break;
                    }
                    let key = self.node(event, mark, depth + 1)?;
                    let (event, mark) = self.next()?;
                    let value = self.node(event, mark, depth + 1)?;
                    pairs.push((key, value));
                }
                (anchor, Node::Mapping { pairs, line })
            }
            other => return Err(Error(format!("yaml: line {line}: unexpected {other:?}"))),
        };
        if anchor != 0 {
            self.anchors.insert(anchor, node.clone());
        }
        Ok(node)
    }
}

/// The tag yaml.v3's parser gives a scalar.
fn scalar_tag(value: &str, style: TScalarStyle, tag: Option<&Tag>) -> String {
    if let Some(tag) = tag {
        let long = format!("{}{}", tag.handle, tag.suffix);
        // "!" alone says "not specific", which is no tag
        if !long.is_empty() && long != "!" {
            return match long.strip_prefix("tag:yaml.org,2002:") {
                Some(short) => format!("!!{short}"),
                None => long,
            };
        }
    }
    if style != TScalarStyle::Plain {
        "!!str".to_string()
    } else if value == "<<" {
        "!!merge".to_string()
    } else {
        resolve(value, true).tag().to_string()
    }
}
