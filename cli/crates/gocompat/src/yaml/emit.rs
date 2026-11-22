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
    let mut emitter = Emitter {
        out: String::new(),
        column: 0,
        indent: -1,
        whitespace: true,
        indention: true,
    };
    emitter.value(root, false);
    // the end of the document: a line that is open is closed
    emitter.write_indent();
    emitter.out
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
        self.out.push(c);
        self.column += 1;
    }

    fn put_break(&mut self) {
        self.out.push('\n');
        self.column = 0;
        self.indention = true;
    }

    /// A line break that is part of a value. Only `\n` is written as the
    /// writer's own break; the others go out as they are.
    fn write_break(&mut self, c: char) {
        if c == '\n' {
            self.put_break();
        } else {
            self.out.push(c);
            self.column = 0;
            self.indention = true;
        }
    }

    fn write_indent(&mut self) {
        let indent = usize::try_from(self.indent).unwrap_or(0);
        if !self.indention || self.column > indent || (self.column == indent && !self.whitespace) {
            self.put_break();
        }
        while self.column < indent {
            self.put(' ');
        }
        self.whitespace = true;
    }

    fn indicator(
        &mut self,
        text: &str,
        need_whitespace: bool,
        is_whitespace: bool,
        is_indention: bool,
    ) {
        if need_whitespace && !self.whitespace {
            self.put(' ');
        }
        for c in text.chars() {
            self.put(c);
        }
        self.whitespace = is_whitespace;
        self.indention = self.indention && is_indention;
    }

    /// Goes one level in and returns the indentation to come back to. What
    /// stands in a sequence item goes in by two, past the "- "; everything
    /// else goes to the next multiple of four.
    fn increase_indent(&mut self, flow: bool, in_sequence_item: bool) -> i32 {
        let before = self.indent;
        self.indent = if before < 0 {
            if flow {
                INDENT
            } else {
                0
            }
        } else if in_sequence_item {
            before + 2
        } else {
            INDENT * ((before + INDENT) / INDENT)
        };
        before
    }

    fn value(&mut self, value: &Value, in_sequence_item: bool) {
        match value {
            Value::Null => self.scalar("null", Style::Plain, in_sequence_item, false),
            Value::Bool(true) => self.scalar("true", Style::Plain, in_sequence_item, false),
            Value::Bool(false) => self.scalar("false", Style::Plain, in_sequence_item, false),
            Value::Int(int) => self.scalar(&int.to_string(), Style::Plain, in_sequence_item, false),
            Value::Time(time) => {
                self.scalar(&time.to_rfc3339(), Style::Plain, in_sequence_item, false)
            }
            Value::Str(text) => self.scalar(text, string_style(text), in_sequence_item, false),
            // an empty collection is written in flow style
            Value::Seq(items) if items.is_empty() => {
                self.indicator("[", true, true, false);
                self.indicator("]", false, false, false);
            }
            Value::Map(fields) if fields.is_empty() => {
                self.indicator("{", true, true, false);
                self.indicator("}", false, false, false);
            }
            Value::Seq(items) => {
                let before = self.increase_indent(false, in_sequence_item);
                for item in items {
                    self.write_indent();
                    self.indicator("-", true, false, true);
                    self.value(item, true);
                }
                self.indent = before;
            }
            Value::Map(fields) => {
                let before = self.increase_indent(false, in_sequence_item);
                for (key, field) in fields {
                    self.write_indent();
                    self.scalar(key, string_style(key), false, true);
                    self.indicator(":", false, false, false);
                    self.value(field, false);
                }
                self.indent = before;
            }
        }
    }

    fn scalar(&mut self, text: &str, requested: Style, in_sequence_item: bool, simple_key: bool) {
        let analysis = analyze(text);
        let mut style = requested;
        if simple_key && analysis.multiline {
            style = Style::DoubleQuoted;
        }
        if style == Style::Plain && (!analysis.plain_allowed || (text.is_empty() && simple_key)) {
            style = Style::SingleQuoted;
        }
        if style == Style::SingleQuoted && !analysis.single_quoted_allowed {
            style = Style::DoubleQuoted;
        }
        if style == Style::Literal && (!analysis.block_allowed || simple_key) {
            style = Style::DoubleQuoted;
        }
        let before = self.increase_indent(true, in_sequence_item);
        match style {
            Style::Plain => self.plain(text),
            Style::SingleQuoted => self.single_quoted(text),
            Style::DoubleQuoted => self.double_quoted(text),
            Style::Literal => self.literal(text),
        }
        self.indent = before;
    }

    fn plain(&mut self, text: &str) {
        if !text.is_empty() && !self.whitespace {
            self.put(' ');
        }
        let mut breaks = false;
        for c in text.chars() {
            if c == ' ' {
                self.put(c);
            } else if is_break(c) {
                if !breaks && c == '\n' {
                    self.put_break();
                }
                self.write_break(c);
                breaks = true;
            } else {
                if breaks {
                    self.write_indent();
                }
                self.put(c);
                self.indention = false;
                breaks = false;
            }
        }
        if !text.is_empty() {
            self.whitespace = false;
        }
        self.indention = false;
    }

    fn single_quoted(&mut self, text: &str) {
        self.indicator("'", true, false, false);
        let mut breaks = false;
        for c in text.chars() {
            if c == ' ' {
                self.put(c);
            } else if is_break(c) {
                if !breaks && c == '\n' {
                    self.put_break();
                }
                self.write_break(c);
                breaks = true;
            } else {
                if breaks {
                    self.write_indent();
                }
                if c == '\'' {
                    self.put('\'');
                }
                self.put(c);
                self.indention = false;
                breaks = false;
            }
        }
        self.indicator("'", false, false, false);
        self.whitespace = false;
        self.indention = false;
    }

    fn double_quoted(&mut self, text: &str) {
        self.indicator("\"", true, false, false);
        // yaml.v3 looks for a byte order mark at the start of the value at
        // every character, so a value that starts with one is escaped whole
        let escape_all = text.starts_with('\u{feff}');
        for c in text.chars() {
            if !(escape_all || !is_printable(c) || is_break(c) || c == '"' || c == '\\') {
                self.put(c);
                continue;
            }
            self.put('\\');
            match c {
                '\0' => self.put('0'),
                '\u{7}' => self.put('a'),
                '\u{8}' => self.put('b'),
                '\t' => self.put('t'),
                '\n' => self.put('n'),
                '\u{b}' => self.put('v'),
                '\u{c}' => self.put('f'),
                '\r' => self.put('r'),
                '\u{1b}' => self.put('e'),
                '"' => self.put('"'),
                '\\' => self.put('\\'),
                '\u{85}' => self.put('N'),
                '\u{a0}' => self.put('_'),
                '\u{2028}' => self.put('L'),
                '\u{2029}' => self.put('P'),
                _ => {
                    let code = u32::from(c);
                    let mut escape = String::new();
                    let _ = if code <= 0xff {
                        write!(escape, "x{code:02X}")
                    } else if code <= 0xffff {
                        write!(escape, "u{code:04X}")
                    } else {
                        write!(escape, "U{code:08X}")
                    };
                    for e in escape.chars() {
                        self.put(e);
                    }
                }
            }
        }
        self.indicator("\"", false, false, false);
        self.whitespace = false;
        self.indention = false;
    }

    fn literal(&mut self, text: &str) {
        self.indicator("|", true, false, false);
        // the hints: the indentation when the value starts with a space or
        // a break (always "4", whatever the depth), then the chomping
        if text
            .chars()
            .next()
            .is_some_and(|first| first == ' ' || is_break(first))
        {
            self.indicator("4", false, false, false);
        }
        let mut from_the_end = text.chars().rev();
        let chomping = match (from_the_end.next(), from_the_end.next()) {
            (None, _) => "-",
            (Some(last), _) if !is_break(last) => "-",
            (Some(_), None) => "+",
            (Some(_), Some(before)) if is_break(before) => "+",
            _ => "",
        };
        if !chomping.is_empty() {
            self.indicator(chomping, false, false, false);
        }
        self.whitespace = true;
        let mut breaks = true;
        for c in text.chars() {
            if is_break(c) {
                self.write_break(c);
                breaks = true;
            } else {
                if breaks {
                    self.write_indent();
                }
                self.put(c);
                self.indention = false;
                breaks = false;
            }
        }
    }
}

/// The style yaml.v3's encoder asks for a string: a block for text of more
/// than one line, quotes for text that would read back as something else
/// (a number, a boolean, a null, a date) or that YAML 1.1 read as one.
fn string_style(text: &str) -> Style {
    if text.contains('\n') {
        Style::Literal
    } else if matches!(resolve(text, true), Resolved::Str)
        && !is_base60_float(text)
        && old_bool(text).is_none()
    {
        Style::Plain
    } else {
        Style::DoubleQuoted
    }
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
    if text.is_empty() {
        return Analysis {
            multiline: false,
            plain_allowed: true,
            single_quoted_allowed: true,
            block_allowed: false,
        };
    }
    let mut indicators = text.starts_with("---") || text.starts_with("...");
    let (mut line_breaks, mut special, mut tabs) = (false, false, false);
    let (mut leading_space, mut leading_break, mut trailing_space, mut trailing_break) =
        (false, false, false, false);
    let (mut break_space, mut space_break) = (false, false);
    let (mut previous_space, mut previous_break) = (false, false);
    let mut preceded_by_whitespace = true;
    let mut chars = text.chars().peekable();
    let mut first = true;
    while let Some(c) = chars.next() {
        let next = chars.peek().copied();
        let last = next.is_none();
        let followed_by_whitespace = matches!(next, None | Some(' ' | '\t'));
        if first {
            indicators |= match c {
                '#' | ',' | '[' | ']' | '{' | '}' | '&' | '*' | '!' | '|' | '>' | '\'' | '"'
                | '%' | '@' | '`' => true,
                '?' | ':' | '-' => followed_by_whitespace,
                _ => false,
            };
        } else {
            indicators |= match c {
                ':' => followed_by_whitespace,
                '#' => preceded_by_whitespace,
                _ => false,
            };
        }
        if c == '\t' {
            tabs = true;
        } else if !is_printable(c) {
            special = true;
        }
        if c == ' ' {
            leading_space |= first;
            trailing_space |= last;
            break_space |= previous_break;
            previous_space = true;
            previous_break = false;
        } else if is_break(c) {
            line_breaks = true;
            leading_break |= first;
            trailing_break |= last;
            space_break |= previous_space;
            previous_space = false;
            previous_break = true;
        } else {
            previous_space = false;
            previous_break = false;
        }
        preceded_by_whitespace = matches!(c, ' ' | '\t' | '\0') || is_break(c);
        first = false;
    }
    let edges = leading_space || leading_break || trailing_space || trailing_break;
    let unquotable = break_space || space_break || tabs || special;
    Analysis {
        multiline: line_breaks,
        plain_allowed: !(edges || unquotable || line_breaks || indicators),
        single_quoted_allowed: !unquotable,
        block_allowed: !(trailing_space || space_break || special),
    }
}

/// What yaml.v3 writes as it is. A character outside the Basic Multilingual
/// Plane is not among them, so an emoji goes out as a `\U` escape in double
/// quotes.
fn is_printable(c: char) -> bool {
    matches!(c, '\n' | '\u{20}'..='\u{7e}' | '\u{a0}'..='\u{d7ff}' | '\u{e000}'..='\u{fefe}' | '\u{ff00}'..='\u{fffd}')
}

fn is_break(c: char) -> bool {
    matches!(c, '\r' | '\n' | '\u{85}' | '\u{2028}' | '\u{2029}')
}
