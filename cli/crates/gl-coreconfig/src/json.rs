//! JSON as the app writes core.json: kotlinx.serialization's `toString()`
//! of what `buildJsonObject` built. Keys in the order they were put (a key
//! put again keeps its place), no space anywhere, and in a string `"`, `\`
//! and the control characters escaped, the short forms where JSON has them
//! and `\u00xx` in lower case for the rest; nothing else, so text outside
//! ASCII is written as it is.

use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Json {
    Bool(bool),
    Int(i64),
    Str(String),
    Array(Vec<Json>),
    Object(Object),
}

/// An object whose keys keep the order they were put in.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Object(Vec<(String, Json)>);

impl Object {
    pub fn new() -> Self {
        Object(Vec::new())
    }

    /// `put`, as a Kotlin map takes it: a new key goes last, a key already
    /// there has its value replaced where it stands.
    pub fn put(&mut self, key: &str, value: impl Into<Json>) {
        let value = value.into();
        match self.0.iter_mut().find(|(k, _)| k == key) {
            Some(entry) => entry.1 = value,
            None => self.0.push((key.to_string(), value)),
        }
    }

    pub fn get(&self, key: &str) -> Option<&Json> {
        self.0.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }

    pub fn keys(&self) -> impl Iterator<Item = &str> {
        self.0.iter().map(|(k, _)| k.as_str())
    }
}

impl From<bool> for Json {
    fn from(v: bool) -> Self {
        Json::Bool(v)
    }
}

impl From<i32> for Json {
    fn from(v: i32) -> Self {
        Json::Int(v.into())
    }
}

impl From<i64> for Json {
    fn from(v: i64) -> Self {
        Json::Int(v)
    }
}

impl From<&str> for Json {
    fn from(v: &str) -> Self {
        Json::Str(v.to_string())
    }
}

impl From<&String> for Json {
    fn from(v: &String) -> Self {
        Json::Str(v.clone())
    }
}

impl From<String> for Json {
    fn from(v: String) -> Self {
        Json::Str(v)
    }
}

impl From<Object> for Json {
    fn from(v: Object) -> Self {
        Json::Object(v)
    }
}

impl From<Vec<Json>> for Json {
    fn from(v: Vec<Json>) -> Self {
        Json::Array(v)
    }
}

/// An array of strings.
pub fn strings<S: AsRef<str>>(values: &[S]) -> Json {
    Json::Array(values.iter().map(|v| Json::from(v.as_ref())).collect())
}

fn quoted(text: &str, out: &mut String) {
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

impl Json {
    fn write(&self, out: &mut String) {
        match self {
            Json::Bool(v) => out.push_str(if *v { "true" } else { "false" }),
            Json::Int(v) => out.push_str(&v.to_string()),
            Json::Str(v) => quoted(v, out),
            Json::Array(items) => {
                out.push('[');
                for (i, item) in items.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    item.write(out);
                }
                out.push(']');
            }
            Json::Object(Object(entries)) => {
                out.push('{');
                for (i, (key, value)) in entries.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    quoted(key, out);
                    out.push(':');
                    value.write(out);
                }
                out.push('}');
            }
        }
    }
}

impl fmt::Display for Json {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut out = String::new();
        self.write(&mut out);
        f.write_str(&out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_keep_their_order_and_a_key_put_again_its_place() {
        let mut o = Object::new();
        o.put("b", 1);
        o.put("a", true);
        o.put("b", "two");
        assert_eq!(Json::Object(o).to_string(), r#"{"b":"two","a":true}"#);
    }

    #[test]
    fn strings_are_escaped_as_kotlinx_escapes_them() {
        let text = "q\"b\\n\nr\rt\tb\u{8}f\u{c}c\u{1}\u{1f}d\u{7f}/é\u{2028}😀";
        assert_eq!(
            Json::from(text).to_string(),
            "\"q\\\"b\\\\n\\nr\\rt\\tb\\bf\\fc\\u0001\\u001fd\u{7f}/é\u{2028}😀\""
        );
    }

    #[test]
    fn nothing_is_spaced() {
        let mut o = Object::new();
        o.put("list", strings(&["a", "b"]));
        o.put("empty", Json::Array(vec![]));
        o.put("inner", Object::new());
        o.put("n", -5);
        assert_eq!(
            Json::Object(o).to_string(),
            r#"{"list":["a","b"],"empty":[],"inner":{},"n":-5}"#
        );
    }
}
