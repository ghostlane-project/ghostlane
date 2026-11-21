//! yaml.v3's writer, for the documents a state file is.
//!
//! This follows yaml.v3's emitter step by step, state by state, for block
//! mappings, block sequences and scalars: where it breaks a line, how deep
//! it indents, when it quotes and how. The comments name what is yaml.v3's
//! own doing where a reader would expect something else.

use std::fmt::Write as _;

use super::resolve::{is_base60_float, old_bool, resolve, Resolved};
use super::Value;

/// yaml.v3 indents by four.
const INDENT: i32 = 4;

/// `yaml.Marshal`.
pub(crate) fn marshal(root: &Value) -> String {
    todo!()
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Style {
    Plain,
    SingleQuoted,
    DoubleQuoted,
    Literal,
}

struct Emitter {
    out: String,
    /// Characters written on the current line.
    column: usize,
    /// The indentation of the collection being written, -1 before the first.
    indent: i32,
    /// The last thing written was white space (or nothing was written yet).
    whitespace: bool,
    /// Nothing but indentation was written on the current line.
    indention: bool,
}

impl Emitter {
    fn put(&mut self, c: char) {
        todo!()
    }

    fn put_break(&mut self) {
        todo!()
    }

    /// A line break that is part of a value. Only `\n` is written as the
    /// writer's own break; the others go out as they are.
    fn write_break(&mut self, c: char) {
        todo!()
    }

    fn write_indent(&mut self) {
        todo!()
    }

    fn indicator(
        &mut self,
        text: &str,
        need_whitespace: bool,
        is_whitespace: bool,
        is_indention: bool,
    ) {
        todo!()
    }

    /// Goes one level in and returns the indentation to come back to. What
    /// stands in a sequence item goes in by two, past the "- "; everything
    /// else goes to the next multiple of four.
    fn increase_indent(&mut self, flow: bool, in_sequence_item: bool) -> i32 {
        todo!()
    }

    fn value(&mut self, value: &Value, in_sequence_item: bool) {
        todo!()
    }

    fn scalar(&mut self, text: &str, requested: Style, in_sequence_item: bool, simple_key: bool) {
        todo!()
    }

    fn plain(&mut self, text: &str) {
        todo!()
    }

    fn single_quoted(&mut self, text: &str) {
        todo!()
    }

    fn double_quoted(&mut self, text: &str) {
        todo!()
    }

    fn literal(&mut self, text: &str) {
        todo!()
    }
}

/// The style yaml.v3's encoder asks for a string: a block for text of more
/// than one line, quotes for text that would read back as something else
/// (a number, a boolean, a null, a date) or that YAML 1.1 read as one.
fn string_style(text: &str) -> Style {
    todo!()
}

/// What the emitter finds out about a scalar before it picks the style.
struct Analysis {
    multiline: bool,
    /// Plain in a block context (nothing here is written in a flow one).
    plain_allowed: bool,
    single_quoted_allowed: bool,
    block_allowed: bool,
}

fn analyze(text: &str) -> Analysis {
    todo!()
}

/// What yaml.v3 writes as it is. A character outside the Basic Multilingual
/// Plane is not among them, so an emoji goes out as a `\U` escape in double
/// quotes.
fn is_printable(c: char) -> bool {
    todo!()
}

fn is_break(c: char) -> bool {
    todo!()
}
