//! Go's text, its JSON and its times: the bytes the Go program writes for
//! the same values, and what it reads.

use std::collections::BTreeMap;
use std::io;

use gocompat::json;
use gocompat::text::{errno_text, hex, quote};
use gocompat::time::Timestamp;
use serde::{Deserialize, Serialize};

fn text(bytes: serde_json::Result<Vec<u8>>) -> String {
    String::from_utf8(bytes.expect("a value that can be written")).expect("JSON is UTF-8")
}

#[test]
fn a_string_is_quoted_as_go_prints_it() {
    assert_eq!(quote("frobnicate"), "\"frobnicate\"");
    assert_eq!(quote("a\"b\\c\n\t"), "\"a\\\"b\\\\c\\n\\t\"");
    assert_eq!(quote("\u{1}\u{7f}"), "\"\\x01\\x7f\"");
    assert_eq!(quote("d\u{e9}j\u{e0} \u{b7}"), "\"d\u{e9}j\u{e0} \u{b7}\"");
    assert_eq!(quote("\u{200b}\u{feff}"), "\"\\u200b\\ufeff\"");
}

#[test]
fn a_system_error_reads_as_go_words_it() {
    assert_eq!(
        errno_text(&io::Error::from_raw_os_error(2)),
        "no such file or directory"
    );
    assert_eq!(
        errno_text(&io::Error::from_raw_os_error(13)),
        "permission denied"
    );
    assert_eq!(
        errno_text(&io::Error::from_raw_os_error(111)),
        "connection refused"
    );
}

#[test]
fn bytes_are_small_hex() {
    assert_eq!(hex(&[0x00, 0xff, 0x10, 0xab]), "00ff10ab");
    assert_eq!(hex(&[]), "");
}

#[test]
fn json_strings_carry_go_escapes() {
    let html = "<a href=\"x\">&</a>\u{2028}\u{2029}";
    assert_eq!(
        text(json::marshal(html)),
        "\"\\u003ca href=\\\"x\\\"\\u003e\\u0026\\u003c/a\\u003e\\u2028\\u2029\""
    );
    assert_eq!(
        text(json::marshal("\u{1}\t\n\u{8}\u{c}\u{7f}\\")),
        "\"\\u0001\\t\\n\\b\\f\u{7f}\\\\\""
    );
    assert_eq!(
        text(json::marshal("DE \u{b7} VP8 \u{2026}")),
        "\"DE \u{b7} VP8 \u{2026}\""
    );
}

#[test]
fn a_map_is_written_by_its_keys_and_a_line_ends_with_a_newline() {
    let mut map = BTreeMap::new();
    map.insert("b<".to_string(), 1);
    map.insert("a".to_string(), 2);
    assert_eq!(text(json::marshal(&map)), "{\"a\":2,\"b\\u003c\":1}");
    assert_eq!(text(json::encode_line(&map)), "{\"a\":2,\"b\\u003c\":1}\n");
}

#[test]
fn the_indented_form_is_gos() {
    let value = serde_json::json!({"a": [1, {"b": "<"}], "c": {}, "d": [], "e": null});
    let want = "{\n  \"a\": [\n    1,\n    {\n      \"b\": \"\\u003c\"\n    }\n  ],\n  \"c\": {},\n  \"d\": [],\n  \"e\": null\n}\n";
    assert_eq!(text(json::encode_indent(&value)), want);
}

#[derive(Debug, PartialEq, Serialize, Deserialize)]
struct Blob {
    #[serde(with = "gocompat::json::bytes")]
    body: Option<Vec<u8>>,
}

#[test]
fn a_byte_slice_is_base64_and_nil_is_null() {
    let full = Blob {
        body: Some("olcrtc://\u{2026}".as_bytes().to_vec()),
    };
    assert_eq!(
        text(json::marshal(&full)),
        "{\"body\":\"b2xjcnRjOi8v4oCm\"}"
    );
    assert_eq!(
        text(json::marshal(&Blob {
            body: Some(Vec::new())
        })),
        "{\"body\":\"\"}"
    );
    assert_eq!(text(json::marshal(&Blob { body: None })), "{\"body\":null}");
    let read: Blob =
        serde_json::from_str("{\"body\":\"b2xjcnRjOi8v4oCm\"}").expect("base64 in a string");
    assert_eq!(read, full);
    let read: Blob = serde_json::from_str("{\"body\":null}").expect("a null");
    assert_eq!(read, Blob { body: None });
    assert!(serde_json::from_str::<Blob>("{\"body\":\"not base64!\"}").is_err());
}

#[test]
fn an_instant_is_written_as_go_writes_it() {
    assert_eq!(
        Timestamp::from_unix(1, 0).to_rfc3339(),
        "1970-01-01T00:00:01Z"
    );
    assert_eq!(
        Timestamp::from_unix(-1, 0).to_rfc3339(),
        "1969-12-31T23:59:59Z"
    );
    assert_eq!(
        Timestamp::from_unix(951_782_400, 0).to_rfc3339(),
        "2000-02-29T00:00:00Z"
    );
    assert_eq!(
        Timestamp::from_unix(1_795_266_000, 120_000_000).to_rfc3339(),
        "2026-11-21T13:00:00.12Z"
    );
    assert_eq!(
        Timestamp::from_unix(1_795_266_000, 1).to_rfc3339(),
        "2026-11-21T13:00:00.000000001Z"
    );
    assert_eq!(Timestamp::ZERO.to_rfc3339(), "0001-01-01T00:00:00Z");
    assert_eq!(Timestamp::default(), Timestamp::ZERO);
    assert!(Timestamp::ZERO.is_zero());
    assert!(!Timestamp::from_unix(0, 0).is_zero());
    assert_eq!(
        Timestamp::from_unix(5, 0).to_string(),
        "1970-01-01T00:00:05Z"
    );
}

#[test]
fn a_time_keeps_the_offset_it_was_written_with() {
    for same in [
        "2026-11-21T13:00:00.123456789+03:00",
        "2026-11-21T13:00:00-00:30",
        "1970-01-01T00:00:01Z",
        "0001-01-01T00:00:00Z",
    ] {
        let read = Timestamp::parse_rfc3339(same).unwrap_or_else(|| panic!("{same} is RFC 3339"));
        assert_eq!(read.to_rfc3339(), same);
    }
    let read = Timestamp::parse_rfc3339("2026-11-21T13:00:00.123456789+03:00").expect("RFC 3339");
    assert_eq!(
        (read.unix(), read.subsec_nanos(), read.offset_seconds()),
        (1_795_255_200, 123_456_789, 10_800)
    );
    // an offset of zero is written as Z, and the zeros at the end of a fraction are not written
    let read = Timestamp::parse_rfc3339("2026-11-21T13:00:00.100+00:00").expect("RFC 3339");
    assert_eq!(read.to_rfc3339(), "2026-11-21T13:00:00.1Z");
    assert_eq!(read, Timestamp::from_unix(1_795_266_000, 100_000_000));
}

#[test]
fn what_is_not_rfc_3339_is_not_a_time() {
    for wrong in [
        "",
        "2026-11-21",
        "2026-11-21 13:00:00Z",
        "2026-13-01T00:00:00Z",
        "2026-02-30T00:00:00Z",
        "2026-11-21T13:00:00",
        "2026-11-21T24:00:00Z",
        "2026-1-2T13:00:00Z",
        "2026-11-21T13:00:00Zjunk",
    ] {
        assert_eq!(Timestamp::parse_rfc3339(wrong), None, "{wrong}");
    }
}

#[test]
fn a_plain_yaml_scalar_is_a_time_in_yaml_v3s_four_layouts() {
    let at = |text: &str| {
        Timestamp::parse_yaml(text)
            .map(|time| (time.unix(), time.subsec_nanos(), time.offset_seconds()))
    };
    assert_eq!(at("2026-11-21"), Some((1_795_219_200, 0, 0)));
    assert_eq!(at("2026-1-2"), Some((1_767_312_000, 0, 0)));
    assert_eq!(at("2026-11-21 13:00:00"), Some((1_795_266_000, 0, 0)));
    assert_eq!(
        at("2026-1-2  0:4:5.5"),
        Some((1_767_312_245, 500_000_000, 0))
    );
    assert_eq!(at("2026-11-21t13:00:00Z"), Some((1_795_266_000, 0, 0)));
    assert_eq!(
        at("2026-11-21T13:00:00.5+01:00"),
        Some((1_795_262_400, 500_000_000, 3_600))
    );
    for not_a_time in [
        "20261-01-01",
        "202-01-01",
        "2026/01/01",
        "2026-11-21T13:00:00",
        "2026-11-21 13:00:00Z",
        "2026-11-21x",
        "2026",
    ] {
        assert_eq!(at(not_a_time), None, "{not_a_time}");
    }
}

#[test]
fn a_time_in_json_is_a_string_and_null_is_the_zero_time() {
    assert_eq!(
        text(json::marshal(&Timestamp::from_unix(5, 0))),
        "\"1970-01-01T00:00:05Z\""
    );
    let read: Timestamp = serde_json::from_str("\"1970-01-01T03:00:05+03:00\"").expect("a time");
    assert_eq!(read.unix(), 5);
    let read: Timestamp = serde_json::from_str("null").expect("a null");
    assert_eq!(read, Timestamp::ZERO);
    assert!(serde_json::from_str::<Timestamp>("\"yesterday\"").is_err());
    assert!(serde_json::from_str::<Timestamp>("5").is_err());
}
