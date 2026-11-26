//! The port held to the app's builder, by the vectors the app's own test
//! (sharedUI `CoreInputVectorsTest`) holds to it: every case of every kind
//! the app writes is written here byte for byte as the app's reference
//! config for it, every listening plan as the app writes it, and every
//! refusal, pin and hello name in the app's words.
//!
//! `vectors.sha256` beside this crate names every file the vectors are, with
//! its digest, and the app's test holds it to them too: so a change to the
//! app's builder that moves a vector changes a file under cli/, and these
//! tests run again.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

use coreconfig::lines::{self, LINES};
use coreconfig::{
    build, build_plan, hello_name, hello_note, inbound, pin_host, reality_hello, refusal,
};
use coreconfig::{Own, Plan, SocksLogin, Spec};
use serde_json::Value;
use sha2::{Digest, Sha256};

const VECTORS: &str = "sharedUI/src/commonTest/resources/vectors/core-inputs.json";
const PLANS: &str = "sharedUI/src/commonTest/resources/vectors/core-plans";
const REFERENCES: &str = "sharedUI/src/jvmTest/resources/reference-configs";

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn vectors() -> Value {
    let path = root().join(VECTORS);
    let text = fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    serde_json::from_str(&text).expect("core-inputs.json is JSON")
}

fn list<'a>(v: &'a Value, key: &str) -> &'a Vec<Value> {
    v[key]
        .as_array()
        .unwrap_or_else(|| panic!("the vectors have no list {key}"))
}

fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key]
        .as_str()
        .unwrap_or_else(|| panic!("no text {key} in {v}"))
}

fn spec(v: &Value) -> Spec {
    serde_json::from_value(v.clone())
        .unwrap_or_else(|e| panic!("a spec the port does not read ({e}): {v}"))
}

fn plan(v: &Value) -> Plan {
    serde_json::from_value(v.clone())
        .unwrap_or_else(|e| panic!("a plan the port does not read ({e}): {v}"))
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

/// Where `written` and `reference` part, when they do: the first byte that
/// differs and a stretch of each around it.
fn difference(reference: &[u8], written: &str) -> Option<String> {
    let written = written.as_bytes();
    if reference == written {
        return None;
    }
    let at = reference
        .iter()
        .zip(written)
        .position(|(a, b)| a != b)
        .unwrap_or(reference.len().min(written.len()));
    let around = |b: &[u8]| {
        String::from_utf8_lossy(&b[at.saturating_sub(60)..(at + 60).min(b.len())]).into_owned()
    };
    Some(format!(
        "they part at byte {at} (the reference has {} bytes, the port wrote {}):\n  reference: {}\n  port:      {}",
        reference.len(),
        written.len(),
        around(reference),
        around(written)
    ))
}

fn read(path: &Path) -> Vec<u8> {
    fs::read(path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

fn login(v: &Value) -> SocksLogin {
    serde_json::from_value(v.clone()).expect("a login")
}

#[test]
fn the_kinds_are_the_apps() {
    let v = vectors();
    let theirs: Vec<(String, Vec<String>)> = list(&v, "kinds")
        .iter()
        .map(|k| {
            let shapes = k["shapes"]
                .as_array()
                .expect("shapes")
                .iter()
                .map(|s| s.as_str().unwrap().to_string())
                .collect();
            (text(k, "name").to_string(), shapes)
        })
        .collect();
    let ours: Vec<(String, Vec<String>)> = LINES
        .iter()
        .map(|l| {
            (
                l.name().to_string(),
                l.shapes().iter().map(|s| s.to_string()).collect(),
            )
        })
        .collect();
    assert_eq!(
        ours, theirs,
        "the port's kinds and their shapes are the app's, in its order"
    );
}

#[test]
fn every_shape_of_every_kind_has_a_case() {
    let v = vectors();
    let covered: BTreeSet<(String, String)> = list(&v, "cases")
        .iter()
        .map(|c| (text(c, "kind").to_string(), text(c, "shape").to_string()))
        .collect();
    let missing: Vec<String> = LINES
        .iter()
        .flat_map(|l| {
            l.shapes()
                .iter()
                .map(move |s| (l.name().to_string(), s.to_string()))
        })
        .filter(|k| !covered.contains(k))
        .map(|(kind, shape)| format!("{kind} {shape}"))
        .collect();
    assert!(missing.is_empty(), "shapes with no case: {missing:?}");
}

#[test]
fn every_case_is_its_reference_config_byte_for_byte() {
    let v = vectors();
    let login = login(&v["login"]);
    let port = v["socksPort"].as_i64().expect("socksPort") as i32;
    let mut failures = Vec::new();
    for case in list(&v, "cases") {
        let name = text(case, "name");
        let spec = spec(&case["spec"]);
        let kind = lines::of(&spec).map(|l| l.name());
        let shape = lines::of(&spec).and_then(|l| l.shape(&spec));
        if kind != Some(text(case, "kind")) || shape != Some(text(case, "shape")) {
            failures.push(format!("{name}: the port reads it as {kind:?} {shape:?}"));
            continue;
        }
        if let Some(why) = refusal(&spec) {
            failures.push(format!("{name}: the port refuses it: {why}"));
            continue;
        }
        if hello_note(&spec).as_deref() != optional(&case["helloNote"]) {
            failures.push(format!("{name}: the hello note is {:?}", hello_note(&spec)));
        }
        let reference = read(
            &root()
                .join(REFERENCES)
                .join(format!("{}.json", text(case, "reference"))),
        );
        match build(&spec, &login, port, false) {
            Ok(json) => {
                if let Some(d) = difference(&reference, &json) {
                    failures.push(format!("{name}: {d}"));
                }
            }
            Err(e) => failures.push(format!("{name}: {e}")),
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_plan_is_the_apps_config_byte_for_byte() {
    let v = vectors();
    let cases = list(&v, "cases");
    let mut failures = Vec::new();
    for vector in list(&v, "plans") {
        let name = text(vector, "name");
        let case = cases
            .iter()
            .find(|c| c["name"] == vector["case"])
            .expect("the plan's case");
        let spec = spec(&case["spec"]);
        let plan = plan(&vector["plan"]);
        let verbose = vector["verboseLogs"].as_bool().expect("verboseLogs");
        let expected = read(&root().join(PLANS).join(format!("{name}.json")));
        match build_plan(&spec, &plan, verbose) {
            Ok(json) => {
                if let Some(d) = difference(&expected, &json) {
                    failures.push(format!("{name}: {d}"));
                }
            }
            Err(e) => failures.push(format!("{name}: {e}")),
        }
        // A plan of the tun alone is build's config, byte for byte.
        if let (Own::Tunnel { port, login }, None) = (&plan.own, &plan.lan) {
            match build(&spec, login, *port, verbose) {
                Ok(json) => {
                    if let Some(d) = difference(&expected, &json) {
                        failures.push(format!("{name} by build: {d}"));
                    }
                }
                Err(e) => failures.push(format!("{name} by build: {e}")),
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_plan_file_is_a_plans() {
    let v = vectors();
    let named: BTreeSet<String> = list(&v, "plans")
        .iter()
        .map(|p| format!("{}.json", text(p, "name")))
        .collect();
    let files: BTreeSet<String> = fs::read_dir(root().join(PLANS))
        .expect("core-plans")
        .map(|e| {
            e.expect("an entry")
                .file_name()
                .to_string_lossy()
                .into_owned()
        })
        .collect();
    assert_eq!(files, named);
}

#[test]
fn every_plan_is_refused_in_the_apps_words() {
    let v = vectors();
    let mut failures = Vec::new();
    for vector in list(&v, "planRefusals") {
        let got = inbound::refusal(&plan(&vector["plan"]));
        if got != optional(&vector["refusal"]) {
            failures.push(format!("{}: the port says {got:?}", text(vector, "name")));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_line_is_refused_in_the_apps_words() {
    let v = vectors();
    let mut failures = Vec::new();
    for vector in list(&v, "refusals") {
        let got = refusal(&spec(&vector["spec"]));
        if got.as_deref() != optional(&vector["refusal"]) {
            failures.push(format!("{}: the port says {got:?}", text(vector, "name")));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_pin_is_the_apps() {
    let v = vectors();
    let mut failures = Vec::new();
    for vector in list(&v, "pins") {
        let got = pin_host(&spec(&vector["spec"]), text(vector, "address"));
        if got != spec(&vector["pinned"]) {
            failures.push(format!(
                "{}: the port pins it as {got:?}",
                text(vector, "name")
            ));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_hello_is_named_as_the_app_names_it() {
    let v = vectors();
    let mut failures = Vec::new();
    for vector in list(&v, "hellos") {
        let fp = optional(&vector["fp"]);
        let (name, reality) = (hello_name(fp), reality_hello(fp));
        if name != text(vector, "name") || reality != text(vector, "reality") {
            failures.push(format!(
                "{fp:?}: the port names it {name} and {reality} under Reality"
            ));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_config_not_written_is_refused_in_the_apps_words() {
    let v = vectors();
    let mut failures = Vec::new();
    for vector in list(&v, "buildErrors") {
        let spec = spec(&vector["spec"]);
        let result = if vector["plan"].is_null() {
            let port = vector["socksPort"].as_i64().expect("socksPort") as i32;
            build(&spec, &login(&vector["login"]), port, false)
        } else {
            build_plan(&spec, &plan(&vector["plan"]), false)
        };
        match result {
            Ok(_) => failures.push(format!("{}: the port wrote a config", text(vector, "name"))),
            Err(e) if e.to_string() != text(vector, "message") => {
                failures.push(format!("{}: the port says {e}", text(vector, "name")))
            }
            Err(_) => {}
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

/// A file's bytes with its line ends read as LF, as the app's test reads
/// them, so that a checkout that writes CRLF lists the same digests.
fn lf(bytes: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::with_capacity(bytes.len());
    for (i, b) in bytes.iter().enumerate() {
        if !(*b == b'\r' && bytes.get(i + 1) == Some(&b'\n')) {
            out.push(*b);
        }
    }
    out
}

/// The listing `vectors.sha256` has to be: every file the vectors are, by
/// its path from the repository's root, in `sha256sum`'s format and order.
fn listing(v: &Value) -> String {
    let mut files: BTreeSet<String> = BTreeSet::new();
    files.insert(VECTORS.to_string());
    for p in list(v, "plans") {
        files.insert(format!("{PLANS}/{}.json", text(p, "name")));
    }
    for c in list(v, "cases") {
        files.insert(format!("{REFERENCES}/{}.json", text(c, "reference")));
    }
    files
        .iter()
        .map(|f| {
            let digest = Sha256::digest(lf(read(&root().join(f))));
            let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
            format!("{hex}  {f}\n")
        })
        .collect()
}

#[test]
fn the_digests_name_every_vector_file_as_it_is() {
    let v = vectors();
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("vectors.sha256");
    let held = fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("{}: {e}", path.display()))
        .replace("\r\n", "\n");
    let wanted = listing(&v);
    assert!(
        held == wanted,
        "cli/crates/gl-coreconfig/vectors.sha256 is not the vectors as they are; it should read:\n{wanted}"
    );
}
