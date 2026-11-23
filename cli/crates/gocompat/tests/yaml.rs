//! yaml.v3's bytes and yaml.v3's reading: what `yaml.Marshal` writes for a
//! value and what `yaml.Unmarshal` makes of a document, as the Go program
//! depends on them.

use gocompat::time::Timestamp;
use gocompat::yaml::{self, Decoder, Error, Node, Record, Value};

fn scalar(text: &str) -> String {
    yaml::marshal(&Value::Map(vec![("k", Value::Str(text.to_string()))]))
}

#[test]
fn a_string_is_plain_unless_it_would_read_back_as_something_else() {
    let cases = [
        ("abc", "k: abc\n"),
        (
            "https://s/sub/a/b?c=olcbox",
            "k: https://s/sub/a/b?c=olcbox\n",
        ),
        ("127.0.0.1", "k: 127.0.0.1\n"),
        ("::1", "k: ::1\n"),
        ("it's", "k: it's\n"),
        ("DE \u{b7} VP8", "k: DE \u{b7} VP8\n"),
        ("d\u{e9}j\u{e0}", "k: d\u{e9}j\u{e0}\n"),
        ("a#b", "k: a#b\n"),
        ("tun", "k: tun\n"),
        ("<<", "k: <<\n"),
        // what would be a null, a boolean, a number or a date goes in double quotes
        ("", "k: \"\"\n"),
        ("~", "k: \"~\"\n"),
        ("null", "k: \"null\"\n"),
        ("true", "k: \"true\"\n"),
        ("False", "k: \"False\"\n"),
        ("1", "k: \"1\"\n"),
        ("-7", "k: \"-7\"\n"),
        ("+1", "k: \"+1\"\n"),
        ("1.5", "k: \"1.5\"\n"),
        (".5", "k: \".5\"\n"),
        ("1e3", "k: \"1e3\"\n"),
        ("0x10", "k: \"0x10\"\n"),
        ("0o17", "k: \"0o17\"\n"),
        ("0b11", "k: \"0b11\"\n"),
        ("1_000", "k: \"1_000\"\n"),
        (".inf", "k: \".inf\"\n"),
        (".NaN", "k: \".NaN\"\n"),
        ("2026-01-02", "k: \"2026-01-02\"\n"),
        ("2026-01-02T03:04:05Z", "k: \"2026-01-02T03:04:05Z\"\n"),
        // and what YAML 1.1 read as one
        ("yes", "k: \"yes\"\n"),
        ("on", "k: \"on\"\n"),
        ("N", "k: \"N\"\n"),
        ("1:30", "k: \"1:30\"\n"),
        // an indicator where it would be one goes in single quotes
        ("a: b", "k: 'a: b'\n"),
        ("- a", "k: '- a'\n"),
        ("#x", "k: '#x'\n"),
        ("a #b", "k: 'a #b'\n"),
        (" a", "k: ' a'\n"),
        ("a ", "k: 'a '\n"),
        ("'a", "k: '''a'\n"),
        ("[x]", "k: '[x]'\n"),
        ("*x", "k: '*x'\n"),
        ("---", "k: '---'\n"),
        // what single quotes cannot hold goes in double quotes, escaped
        ("a\tb", "k: \"a\\tb\"\n"),
        ("a \nb", "k: \"a \\nb\"\n"),
        ("\u{1b}[2J", "k: \"\\e[2J\"\n"),
        (
            "\u{1f1e9}\u{1f1ea} DE",
            "k: \"\\U0001F1E9\\U0001F1EA DE\"\n",
        ),
        ("say \"hi\" \\ \u{1}", "k: \"say \\\"hi\\\" \\\\ \\x01\"\n"),
        // more than one line is a block
        ("a\nb", "k: |-\n    a\n    b\n"),
        ("a\nb\n", "k: |\n    a\n    b\n"),
        ("a\n\n", "k: |+\n    a\n\n"),
        ("a\n\nb", "k: |-\n    a\n\n    b\n"),
        (" a\nb", "k: |4-\n     a\n    b\n"),
    ];
    for (text, want) in cases {
        assert_eq!(scalar(text), want, "{text:?}");
    }
}

#[test]
fn a_struct_is_laid_out_by_fours_and_a_list_item_by_two() {
    let value = Value::Map(vec![
        (
            "list",
            Value::Seq(vec![
                Value::Map(vec![
                    ("a", Value::Int(1)),
                    (
                        "b",
                        Value::Seq(vec![
                            Value::Str("x".to_string()),
                            Value::Str("y".to_string()),
                        ]),
                    ),
                    ("c", Value::Str("one\ntwo".to_string())),
                ]),
                Value::Str("s".to_string()),
            ]),
        ),
        ("empty", Value::Seq(Vec::new())),
        ("none", Value::Map(Vec::new())),
        (
            "nested",
            Value::Map(vec![(
                "deep",
                Value::Map(vec![("flag", Value::Bool(false))]),
            )]),
        ),
        ("when", Value::Time(Timestamp::from_unix(1, 0))),
        ("nil", Value::Null),
        ("text", Value::Str("one\ntwo\n".to_string())),
    ]);
    let want = "\
list:
    - a: 1
      b:
        - x
        - \"y\"
      c: |-
        one
        two
    - s
empty: []
none: {}
nested:
    deep:
        flag: false
when: 1970-01-01T00:00:01Z
nil: null
text: |
    one
    two
";
    assert_eq!(yaml::marshal(&value), want);
}

#[derive(Debug, Default, PartialEq)]
struct Item {
    name: String,
    size: i64,
}

impl Record for Item {
    const TYPE: &'static str = "main.Item";
    const FIELDS: &'static [&'static str] = &["name", "size"];

    fn field(&mut self, decoder: &mut Decoder, index: usize, value: &Node) -> Result<(), Error> {
        let read = match index {
            0 => decoder.string(value, &mut self.name),
            _ => decoder.int(value, &mut self.size),
        };
        read.map(drop)
    }
}

#[derive(Debug, Default, PartialEq)]
struct Doc {
    name: String,
    count: i64,
    on: bool,
    at: Timestamp,
    items: Vec<Item>,
    child: Option<Item>,
}

impl Record for Doc {
    const TYPE: &'static str = "main.Doc";
    const FIELDS: &'static [&'static str] = &["name", "count", "on", "at", "items", "child"];

    fn field(&mut self, decoder: &mut Decoder, index: usize, value: &Node) -> Result<(), Error> {
        let read = match index {
            0 => decoder.string(value, &mut self.name),
            1 => decoder.int(value, &mut self.count),
            2 => decoder.boolean(value, &mut self.on),
            3 => decoder.time(value, &mut self.at),
            4 => decoder.sequence(value, &mut self.items),
            _ => decoder.pointer(value, &mut self.child),
        };
        read.map(drop)
    }
}

fn item(name: &str, size: i64) -> Item {
    Item {
        name: name.to_string(),
        size,
    }
}

fn read(text: &str) -> Result<Doc, String> {
    read_over(text, Doc::default())
}

fn read_over(text: &str, mut doc: Doc) -> Result<Doc, String> {
    match yaml::unmarshal(text.as_bytes(), &mut doc) {
        Ok(()) => Ok(doc),
        Err(err) => Err(err.to_string()),
    }
}

#[test]
fn a_document_written_by_hand_is_read() {
    let text = "\
# a comment
name: hello   # and one behind a value
count: 0x10
on: yes
at: 2026-11-21 13:00:00
items:
  - {name: a, size: 1}
  - name: \"b\"
    size: 2.0
child:
  name: c
unknown: [1, 2, {deep: true}]
";
    let want = Doc {
        name: "hello".to_string(),
        count: 16,
        on: true,
        at: Timestamp::from_unix(1_795_266_000, 0),
        items: vec![item("a", 1), item("b", 2)],
        child: Some(item("c", 0)),
    };
    assert_eq!(read(text), Ok(want));
}

#[test]
fn a_null_leaves_a_value_as_it_was_and_empties_a_list_or_a_pointer() {
    let before = Doc {
        name: "keep".to_string(),
        count: 7,
        on: true,
        items: vec![item("x", 1)],
        child: Some(item("y", 2)),
        ..Doc::default()
    };
    let after = read_over(
        "name: ~\ncount:\non: null\nat: Null\nitems: ~\nchild: null\n",
        before,
    );
    let want = Doc {
        name: "keep".to_string(),
        count: 7,
        on: true,
        ..Doc::default()
    };
    assert_eq!(after, Ok(want));
}

#[test]
fn a_document_with_nothing_in_it_changes_nothing() {
    for nothing in ["", "\n", "# only a comment\n", "---\n", "~\n"] {
        let before = Doc {
            name: "keep".to_string(),
            ..Doc::default()
        };
        let want = Doc {
            name: "keep".to_string(),
            ..Doc::default()
        };
        assert_eq!(read_over(nothing, before), Ok(want), "{nothing:?}");
    }
}

#[test]
fn only_the_first_document_is_read() {
    assert_eq!(
        read("name: a\n---\nname: b\n").map(|doc| doc.name),
        Ok("a".to_string())
    );
}

#[test]
fn values_of_the_wrong_kind_are_all_named_with_their_lines() {
    let text = "name: [1]\ncount: many\non: 3\nitems: 5\nchild: [x]\n";
    let want = "yaml: unmarshal errors:\n  \
        line 1: cannot unmarshal !!seq into string\n  \
        line 2: cannot unmarshal !!str `many` into int\n  \
        line 3: cannot unmarshal !!int `3` into bool\n  \
        line 4: cannot unmarshal !!int `5` into []main.Item\n  \
        line 5: cannot unmarshal !!seq into main.Item";
    assert_eq!(read(text), Err(want.to_string()));
    let long = "yaml: unmarshal errors:\n  line 1: cannot unmarshal !!str `abcdefg...` into int";
    assert_eq!(read("count: abcdefghijklm\n"), Err(long.to_string()));
    let quoted = "yaml: unmarshal errors:\n  line 1: cannot unmarshal !!str `5` into int";
    assert_eq!(read("count: \"5\"\n"), Err(quoted.to_string()));
    let list = "yaml: unmarshal errors:\n  line 1: cannot unmarshal !!seq into main.Doc";
    assert_eq!(read("- a\n- b\n"), Err(list.to_string()));
    let word = "yaml: unmarshal errors:\n  line 1: cannot unmarshal !!str `hello` into main.Doc";
    assert_eq!(read("hello\n"), Err(word.to_string()));
}

#[test]
fn a_key_given_twice_is_refused() {
    let want = "yaml: unmarshal errors:\n  line 2: mapping key \"name\" already defined at line 1";
    assert_eq!(read("name: a\nname: b\n"), Err(want.to_string()));
}

#[test]
fn a_merge_fills_what_the_mapping_does_not_say_itself() {
    let text = "\
base: &base
  name: from-base
  size: 1
child:
  <<: *base
  size: 2
items:
  - <<: [*base, {size: 9}]
  - *base
";
    let doc = read(text).expect("anchors and merges are YAML");
    assert_eq!(doc.child, Some(item("from-base", 2)));
    assert_eq!(doc.items, vec![item("from-base", 1), item("from-base", 1)]);
    let not_a_map = read("child:\n  <<: 5\n");
    assert_eq!(
        not_a_map,
        Err("yaml: map merge requires map or sequence of maps as the value".to_string())
    );
}

#[test]
fn a_tag_says_what_a_scalar_is() {
    let doc =
        read("name: !!str 123\ncount: !!int \"12\"\nat: !!timestamp 2026-01-02\n").expect("tags");
    assert_eq!(
        (doc.name.as_str(), doc.count, doc.at.unix()),
        ("123", 12, 1_767_312_000)
    );
    assert_eq!(
        read("name: !!binary aGVsbG8=\n").map(|doc| doc.name),
        Ok("hello".to_string())
    );
    assert_eq!(
        read("name: !mine 5\n").map(|doc| doc.name),
        Ok("5".to_string())
    );
    assert_eq!(read("count: !!float 3\n").map(|doc| doc.count), Ok(3));
    assert_eq!(
        read("count: !!int abc\n"),
        Err("yaml: cannot decode !!str `abc` as a !!int".to_string())
    );
    assert_eq!(
        read("name: !!binary '*'\n"),
        Err("yaml: !!binary value contains invalid base64 data".to_string())
    );
}

#[test]
fn a_scalar_is_a_string_whatever_it_looks_like() {
    let doc = read("name: 2026-01-02\n").expect("a date as a name");
    assert_eq!(doc.name, "2026-01-02");
    for (text, want) in [
        ("name: 12\n", "12"),
        ("name: true\n", "true"),
        ("name: \"a\\tb\"\n", "a\tb"),
        ("name: 'it''s'\n", "it's"),
        ("name: |\n  one\n  two\n", "one\ntwo\n"),
        ("name: >-\n  one\n  two\n", "one two"),
    ] {
        assert_eq!(
            read(text).map(|doc| doc.name),
            Ok(want.to_string()),
            "{text:?}"
        );
    }
}

#[test]
fn a_time_is_a_yaml_timestamp_or_rfc_3339_in_quotes() {
    let at = |text: &str| read(text).map(|doc| doc.at.to_rfc3339());
    assert_eq!(
        at("at: 2026-11-21T13:00:00.5+03:00\n"),
        Ok("2026-11-21T13:00:00.5+03:00".to_string())
    );
    assert_eq!(
        at("at: \"2026-11-21T13:00:00+03:00\"\n"),
        Ok("2026-11-21T13:00:00+03:00".to_string())
    );
    assert_eq!(
        at("at: 2026-11-21\n"),
        Ok("2026-11-21T00:00:00Z".to_string())
    );
    let refused = at("at: nope\n").expect_err("no time");
    assert!(refused.starts_with("parsing time \"nope\""), "{refused}");
    assert_eq!(
        at("at: [1]\n"),
        Err("yaml: unmarshal errors:\n  line 1: cannot unmarshal !!seq into time.Time".to_string())
    );
}

#[test]
fn a_byte_order_mark_and_windows_line_ends_are_read() {
    let doc = read("\u{feff}name: x\r\ncount: 1\r\n").expect("a file from another system");
    assert_eq!((doc.name.as_str(), doc.count), ("x", 1));
    let not_text = yaml::unmarshal(&[b'n', b':', b' ', 0xff], &mut Doc::default());
    assert!(not_text.is_err());
}

#[test]
fn what_is_not_yaml_is_an_error_that_says_yaml() {
    for broken in ["name: [1, 2\n", "name: \"open\n", "a: b: c\n"] {
        let refused = read(broken).expect_err(broken);
        assert!(refused.starts_with("yaml: "), "{broken:?}: {refused}");
    }
}

#[test]
fn what_is_written_is_read_back() {
    let texts = [
        "plain",
        "",
        "1",
        "yes",
        "a: b",
        " lead",
        "tab\there",
        "two\nlines",
        "two\nlines\n",
        "\u{1f1e9}\u{1f1ea} DE \u{b7} VP8",
        "::1",
        "'q'",
        "\"q\"",
        "# not a comment",
        "back\\slash",
        "~",
    ];
    for text in texts {
        let written = yaml::marshal(&Value::Map(vec![("name", Value::Str(text.to_string()))]));
        assert_eq!(
            read(&written).map(|doc| doc.name),
            Ok(text.to_string()),
            "{written:?}"
        );
    }
}
