//! The port held to the app's vectors (`LinkVectorsTest`, sharedUI), which the
//! app's own test holds to its parsers: every case of the Go link tests, the
//! crypt1 blobs and their refusals, and the list cases with their fixtures.
//!
//! `vectors.sha256` beside this crate names every file the vectors are, with
//! its digest, and the app's test holds it to them too, so a stale vector
//! fails both sides.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

use gl_links::{crypt1, import, list, olcrtc, share};
use serde_json::Value;
use sha2::{Digest, Sha256};

const LINKS: &str = "sharedUI/src/commonTest/resources/vectors/links.json";
const CRYPT1: &str = "sharedUI/src/commonTest/resources/vectors/crypt1.json";
const LISTS: &str = "sharedUI/src/commonTest/resources/vectors/lists.json";

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn vector(name: &str) -> Value {
    let path = root().join(name);
    let text = fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn cases(v: &Value) -> &Vec<Value> {
    v["cases"].as_array().expect("cases")
}

fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key]
        .as_str()
        .unwrap_or_else(|| panic!("no text {key} in {v}"))
}

fn optional(v: &Value) -> Option<&str> {
    if v.is_null() {
        None
    } else {
        Some(
            v.as_str()
                .unwrap_or_else(|| panic!("neither text nor null: {v}")),
        )
    }
}

fn flag(v: &Value, key: &str) -> bool {
    v[key]
        .as_bool()
        .unwrap_or_else(|| panic!("no flag {key} in {v}"))
}

/// A refusal's words are a field of its own in the vector: the port's error
/// text has to be the same, so a changed refusal fails here.
fn refusal_text(v: &Value) -> Option<&str> {
    v["expect"]["refusal"].as_str()
}

#[test]
fn every_link_case_is_parsed_or_refused_as_the_vector_says() {
    let v = vector(LINKS);
    let mut failures = Vec::new();
    for case in cases(&v) {
        let name = text(case, "name");
        let group = text(case, "group");
        if !matches!(group, "vless" | "hy2" | "olcrtc" | "import") {
            continue;
        }
        let line = text(case, "line");
        let want = &case["expect"];
        if let Some(why) = refusal_text(case) {
            let got = match group {
                "vless" => share::parse_vless(line).err().map(|e| e.text()),
                "hy2" => share::parse_hy2(line).err().map(|e| e.text()),
                "olcrtc" => olcrtc::parse_olcrtc(line).err().map(|e| e.text()),
                other => panic!("{name}: no refusal parser for {other}"),
            };
            match got {
                None => failures.push(format!("{name}: the port accepted a refused line")),
                Some(got) => {
                    // The vector holds the stable part of the refusal: the
                    // prefix before any transport name the parser appends.
                    if !got.starts_with(why) {
                        failures.push(format!(
                            "{name}: the port says {got}, the vector says {why}"
                        ));
                    }
                }
            }
            continue;
        }
        let got = match group {
            "vless" => serde_json::to_value(
                share::parse_vless(line).unwrap_or_else(|e| panic!("{name}: {e}")),
            )
            .unwrap(),
            "hy2" => serde_json::to_value(
                share::parse_hy2(line).unwrap_or_else(|e| panic!("{name}: {e}")),
            )
            .unwrap(),
            "olcrtc" => serde_json::to_value(
                olcrtc::parse_olcrtc(line).unwrap_or_else(|e| panic!("{name}: {e}")),
            )
            .unwrap(),
            "import" => serde_json::json!({ "payload": import::import_payload(line) }),
            _ => continue,
        };
        for (key, expected) in want.as_object().expect("an object of expected fields") {
            if got.get(key) != Some(expected) {
                failures.push(format!(
                    "{name}: {key} is {} in the port and {expected} in the vector",
                    got.get(key)
                        .map(|v| v.to_string())
                        .unwrap_or_else(|| "missing".to_string())
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn normalizers_and_countries_are_the_vectors() {
    let v = vector(LINKS);
    let mut failures = Vec::new();
    for case in cases(&v) {
        match text(case, "group") {
            "normalizers" => {
                let expect = &case["expect"];
                for pair in expect["cases"].as_array().expect("cases") {
                    let input = pair[0].as_str().expect("a text pair");
                    let want = optional(&pair[1]);
                    let got = if text(expect, "function") == "normalizeProvider" {
                        olcrtc::normalize_provider(input)
                    } else {
                        olcrtc::normalize_transport(input)
                    };
                    if got != want {
                        failures.push(format!(
                            "{}: {input}: got {got:?}, want {want:?}",
                            text(case, "name")
                        ));
                    }
                }
            }
            "country" => {
                for pair in case["expect"]["cases"].as_array().expect("cases") {
                    let label = pair[0].as_str().expect("a text pair");
                    let want = pair[1].as_str().expect("a text pair");
                    let got = import::country_of(label);
                    if got != want {
                        failures.push(format!("{label}: country {got}, want {want}"));
                    }
                }
            }
            _ => {}
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_crypt1_case_is_decrypted_or_refused_as_the_vector_says() {
    let v = vector(CRYPT1);
    let mut failures = Vec::new();
    for case in cases(&v) {
        let name = text(case, "name");
        let master = crypt1::parse_key(text(case, "master"))
            .unwrap_or_else(|| panic!("{name}: the master key is not a key"));
        let got = if text(case, "group") == "link" {
            let link = format!("{}{}", crypt1::LINK_PREFIX, text(case, "blob"));
            let link = link.as_str();
            if !crypt1::is_link(link) {
                failures.push(format!("{name}: not named a crypt link"));
                continue;
            }
            crypt1::decrypt_link(&master, link)
        } else {
            crypt1::decrypt(&master, text(case, "blob"))
        };
        if flag(case, "accept") {
            let want = text(case, "plain").as_bytes();
            match got {
                Some(plain) if plain == want => {}
                Some(plain) => failures.push(format!(
                    "{name}: decrypted {:?}, want {:?}",
                    String::from_utf8_lossy(&plain),
                    text(case, "plain")
                )),
                None => failures.push(format!("{name}: did not decrypt")),
            }
        } else if got.is_some() {
            failures.push(format!("{name}: a refused blob decrypted"));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

fn fixture(name: &str) -> String {
    let path = root().join("cli/internal/links/testdata").join(name);
    fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

#[test]
fn every_body_case_is_decoded_as_the_vector_says() {
    let v = vector(LISTS);
    let mut failures = Vec::new();
    for case in cases(&v) {
        if text(case, "group") != "decode" {
            continue;
        }
        let name = text(case, "name");
        let expect = &case["expect"];
        let body = expect["body"].as_str().unwrap_or("").as_bytes();
        let got = if let Some(plain) = expect["decryptorPayload"].as_str() {
            list::decode_body_with(
                body,
                Some(&|blob: &str| {
                    if blob == plain {
                        expect["plain"]
                            .as_str()
                            .map(|value| value.as_bytes().to_vec())
                    } else {
                        None
                    }
                }),
            )
        } else {
            list::decode_body(body)
        };
        let want: Vec<String> = expect["lines"]
            .as_array()
            .expect("lines")
            .iter()
            .map(|v| v.as_str().unwrap_or("").to_string())
            .collect();
        if got != want {
            failures.push(format!("{name}: decoded {got:?}, want {want:?}"));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_header_case_is_parsed_as_the_vector_says() {
    let v = vector(LISTS);
    let mut failures = Vec::new();
    for case in cases(&v) {
        if text(case, "group") != "headers" {
            continue;
        }
        let name = text(case, "name");
        let hdrs: Vec<(&str, &str)> = case["headers"]
            .as_object()
            .expect("headers")
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str().unwrap_or("")))
            .collect();
        let got = list::parse_headers(&hdrs);
        let want = &case["expect"];
        if let Some(title) = want["title"].as_str() {
            if got.title != title {
                failures.push(format!("{name}: title {:?}, want {title}", got.title));
            }
        }
        if let Some(refusal) = want["refusal"].as_str() {
            if got.refusal != refusal {
                failures.push(format!("{name}: refusal {:?}, want {refusal}", got.refusal));
            }
        }
        if let Some(n) = want["updateIntervalHours"].as_i64() {
            if got.update_interval_hours as i64 != n {
                failures.push(format!(
                    "{name}: interval {}, want {n}",
                    got.update_interval_hours
                ));
            }
        }
        if let Some(ui) = want["userInfo"].as_object() {
            let info = got
                .user_info
                .as_ref()
                .unwrap_or_else(|| panic!("{name}: no user info"));
            for (key, expected) in ui {
                let value = match key.as_str() {
                    "upload" => info.upload,
                    "download" => info.download,
                    "total" => info.total,
                    "expire" => info.expire,
                    _ => continue,
                };
                if value != expected.as_i64().unwrap() {
                    failures.push(format!("{name}: {key} {value}, want {expected}"));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn device_fields_and_refusal_words_are_the_vectors() {
    let v = vector(LISTS);
    let mut failures = Vec::new();
    for case in cases(&v) {
        match text(case, "group") {
            "ascii" => {
                let expect = &case["expect"];
                let got = list::device_headers("", "", "", expect["input"].as_str().unwrap_or(""))
                    .into_iter()
                    .next()
                    .map(|(_, v)| v)
                    .unwrap_or_default();
                if got != expect["want"].as_str().unwrap_or("") {
                    failures.push(format!("{}: ascii header {got:?}", text(case, "name")));
                }
            }
            "refusal-text" => {
                if case["skipPort"].as_bool() == Some(true) {
                    continue;
                }
                let expect = &case["expect"];
                let got = list::refusal_text(text(expect, "kind"), text(expect, "announce"));
                if let Some(want) = expect["want"].as_str() {
                    if got != want {
                        failures.push(format!(
                            "{}: words {got:?}, want {want}",
                            text(case, "name")
                        ));
                    }
                }
                if let Some(part) = expect["wantContains"].as_str() {
                    if !got.contains(part) {
                        failures.push(format!("{}: words {got:?} lack {part}", text(case, "name")));
                    }
                }
                if let Some(n) = expect["runeCount"].as_i64() {
                    if got.chars().count() as i64 != n {
                        failures.push(format!(
                            "{}: {} characters, want {n}",
                            text(case, "name"),
                            got.chars().count()
                        ));
                    }
                }
            }
            _ => {}
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_fixture_is_parsed_as_the_vector_says() {
    let v = vector(LISTS);
    let mut failures = Vec::new();
    for fixture_vector in v["fixtures"].as_array().expect("fixtures") {
        let name = text(fixture_vector, "name");
        let url = text(fixture_vector, "url");
        let body = fixture(name);
        let lines = list::decode_body(body.as_bytes());
        let got = list::entries(url, &lines);
        let want = fixture_vector["entries"].as_array().expect("entries");
        if got.len() != want.len() {
            failures.push(format!(
                "{name}: {} entries, want {}",
                got.len(),
                want.len()
            ));
            continue;
        }
        for (entry, expected) in got.iter().zip(want) {
            for (key, value) in expected.as_object().expect("an entry") {
                let actual = match key.as_str() {
                    "raw" => serde_json::Value::String(entry.raw.clone()),
                    "label" => serde_json::Value::String(entry.label.clone()),
                    "country" => serde_json::Value::String(entry.country.clone()),
                    "kind" => serde_json::Value::String(entry.kind.name().to_string()),
                    "id" => serde_json::Value::String(entry.id.clone()),
                    "connectable" => serde_json::Value::Bool(entry.connectable()),
                    _ => continue,
                };
                if actual != *value {
                    failures.push(format!("{name}: {} is {actual}, want {value}", entry.label));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_entry_and_selection_case_is_as_the_vector_says() {
    let v = vector(LISTS);
    let mut failures = Vec::new();
    for case in cases(&v) {
        let name = text(case, "name");
        let expect = &case["expect"];
        let fixture_name = expect["fixture"]
            .as_str()
            .or_else(|| expect["entries"].as_str());
        let entries = if let Some(f) = fixture_name {
            let fv = v["fixtures"]
                .as_array()
                .expect("fixtures")
                .iter()
                .find(|f2| text(f2, "name") == f)
                .unwrap_or_else(|| panic!("{name}: no fixture {f}"));
            let url = text(fv, "url");
            let body = fixture(f);
            list::entries(url, &list::decode_body(body.as_bytes()))
        } else if let Some(lines) = expect["lines"].as_array() {
            let lines: Vec<String> = lines
                .iter()
                .map(|l| l.as_str().unwrap_or("").to_string())
                .collect();
            list::entries("u", &lines)
        } else {
            continue;
        };
        match text(case, "group") {
            "entries" => {
                if let Some(n) = expect["count"].as_i64() {
                    if entries.len() as i64 != n {
                        failures.push(format!("{name}: {} entries, want {n}", entries.len()));
                    }
                }
                if let Some(n) = expect["groups"].as_i64() {
                    let groups = list::group_by_country(&entries);
                    if groups.len() as i64 != n {
                        failures.push(format!("{name}: {} groups, want {n}", groups.len()));
                    }
                    if let Some(country) = expect["firstCountry"].as_str() {
                        if groups.first().map(|g| g.country.as_str()) != Some(country) {
                            failures.push(format!(
                                "{name}: first group {:?}",
                                groups.first().map(|g| &g.country)
                            ));
                        }
                    }
                }
                if let Some(ids) = expect["uniqueIDs"].as_bool() {
                    if ids {
                        let uniq: BTreeSet<&str> = entries.iter().map(|e| e.id.as_str()).collect();
                        if uniq.len() != entries.len() {
                            failures.push(format!("{name}: repeated ids"));
                        }
                    }
                }
                if let Some(kinds) = expect["kinds"].as_array() {
                    let got: Vec<&str> = entries.iter().map(|e| e.kind.name()).collect();
                    let want: Vec<&str> = kinds.iter().map(|k| k.as_str().unwrap_or("")).collect();
                    if got != want {
                        failures.push(format!("{name}: kinds {got:?}, want {want:?}"));
                    }
                }
                if let Some(flags) = expect["allConnectable"].as_bool() {
                    if flags && entries.iter().any(|e| !e.connectable()) {
                        failures.push(format!("{name}: a line is not connectable"));
                    }
                }
                for (key, value) in [("hy2", list::Kind::Hysteria2), ("vless", list::Kind::Vless)] {
                    if let Some(n) = expect[key].as_i64() {
                        let got = entries.iter().filter(|e| e.kind == value).count();
                        if got as i64 != n {
                            failures.push(format!("{name}: {key} {got}, want {n}"));
                        }
                    }
                }
                if let Some(connectable) = expect["connectable"].as_bool() {
                    if !connectable && entries.first().map(|e| e.connectable()) != Some(false) {
                        failures.push(format!("{name}: an unsupported line connected"));
                    }
                }
            }
            "select" => {
                if expect["selector"].as_str().is_none() {
                    continue;
                }
                let selector = text(expect, "selector");
                let got = list::select(&entries, selector);
                if let Some(err_part) = expect["errorContains"].as_str() {
                    match got {
                        Err(e) if !e.contains(err_part) => {
                            failures.push(format!("{name}: error {e} lacks {err_part}"))
                        }
                        Err(_) => {}
                        Ok(_) => failures.push(format!("{name}: an unknown selector matched")),
                    }
                    continue;
                }
                if expect["error"].as_bool() == Some(true) {
                    if got.is_ok() {
                        failures.push(format!("{name}: a bad selector matched"));
                    }
                    continue;
                }
                let got = got.unwrap_or_else(|e| panic!("{name}: {e}"));
                if let Some(n) = expect["wantLabels"].as_i64() {
                    if got.len() as i64 != n {
                        failures.push(format!("{name}: {} entries, want {n}", got.len()));
                    }
                }
                if let Some(n) = expect["count"].as_i64() {
                    if got.len() as i64 != n {
                        failures.push(format!("{name}: {} entries, want {n}", got.len()));
                    }
                }
                if let Some(provider) = expect["wantProvider"].as_str() {
                    let line = &got[0].raw;
                    if !line.contains(provider) {
                        failures.push(format!("{name}: {}", line));
                    }
                }
                if let Some(i) = expect["wantIndex"].as_i64() {
                    if got[0].id != entries[i as usize].id {
                        failures.push(format!("{name}: the index chose the wrong entry"));
                    }
                }
                if expect["distinctIDs"].as_bool() == Some(true) && entries[0].id == entries[1].id {
                    failures.push(format!("{name}: two labels share an id"));
                }
            }
            _ => {}
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn fetch_device_fields_are_the_vectors() {
    let v = vector(LISTS);
    let mut failures = Vec::new();
    for case in cases(&v) {
        if text(case, "group") != "fetch" {
            continue;
        }
        let name = text(case, "name");
        let expect = &case["expect"];
        let d = &case["device"];
        let headers = list::device_headers(
            d["ID"].as_str().unwrap_or(""),
            d["OS"].as_str().unwrap_or(""),
            d["OSVersion"].as_str().unwrap_or(""),
            d["Model"].as_str().unwrap_or(""),
        );
        let get = |key: &str| {
            headers
                .iter()
                .find(|(k, _)| k.eq_ignore_ascii_case(key))
                .map(|(_, v)| v.as_str())
        };
        for key in ["x-hwid", "x-device-os", "x-ver-os", "x-device-model"] {
            if let Some(want) = expect[key].as_str() {
                if get(key) != Some(want) {
                    failures.push(format!("{name}: {key} {:?}, want {want}", get(key)));
                }
            }
        }
        if expect["no-hwid"].as_bool() == Some(true) && get("x-hwid").is_some() {
            failures.push(format!("{name}: no id still sent x-hwid"));
        }
        if let Some(names) = expect["no-device-headers"].as_array() {
            for n in names {
                if get(n.as_str().unwrap_or("")).is_some() {
                    failures.push(format!(
                        "{name}: an empty device sent {}",
                        n.as_str().unwrap_or("")
                    ));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

/// The digests are in sha256sum's order, not the order of the files above.
fn listing() -> String {
    let mut files: BTreeSet<String> = BTreeSet::new();
    files.insert(LINKS.to_string());
    files.insert(CRYPT1.to_string());
    files.insert(LISTS.to_string());
    files
        .iter()
        .map(|f| {
            let bytes = fs::read(root().join(f)).unwrap_or_else(|e| panic!("{f}: {e}"));
            let digest = Sha256::digest(bytes);
            let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
            format!("{hex}  {f}\n")
        })
        .collect()
}

#[test]
fn the_digests_name_every_vector_file_as_it_is() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("vectors.sha256");
    let held = fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("{}: {e}", path.display()))
        .replace("\r\n", "\n");
    let wanted = listing();
    assert!(
        held == wanted,
        "cli/crates/gl-links/vectors.sha256 is not the vectors as they are; it should read:\n{wanted}"
    );
}
