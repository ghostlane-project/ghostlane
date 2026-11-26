//! The vectors the app's `LinkVectorsTest` holds to its own parsers, held
//! here to the CLI's Rust port. The port is not written yet, so every case
//! fails: this is the red commit of C2.

use std::fs;
use std::path::Path;

use sha2::{Digest, Sha256};

const LINKS: &str = "sharedUI/src/commonTest/resources/vectors/links.json";
const CRYPT1: &str = "sharedUI/src/commonTest/resources/vectors/crypt1.json";
const LISTS: &str = "sharedUI/src/commonTest/resources/vectors/lists.json";

fn root() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn vector(name: &str) -> serde_json::Value {
    let path = root().join(name);
    let text = fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("{name}: {e}"))
}

#[test]
fn every_link_case_is_parsed_as_the_vector_says() {
    let v = vector(LINKS);
    for case in v["cases"].as_array().expect("cases") {
        let name = case["name"].as_str().unwrap_or("?");
        panic!("{name}: the port does not parse links yet");
    }
}

#[test]
fn every_crypt1_case_is_decrypted_as_the_vector_says() {
    let v = vector(CRYPT1);
    for case in v["cases"].as_array().expect("cases") {
        let name = case["name"].as_str().unwrap_or("?");
        panic!("{name}: the port does not decrypt crypt1 yet");
    }
}

#[test]
fn every_list_case_is_parsed_as_the_vector_says() {
    let v = vector(LISTS);
    for case in v["cases"].as_array().expect("cases") {
        let name = case["name"].as_str().unwrap_or("?");
        panic!("{name}: the port does not parse lists yet");
    }
}

#[test]
fn the_digests_name_every_vector_file_as_it_is() {
    let listing: String = [LINKS, CRYPT1, LISTS]
        .iter()
        .map(|f| {
            let bytes = fs::read(root().join(f)).expect("a vector file");
            let digest = Sha256::digest(bytes);
            let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
            format!("{hex}  {f}\n")
        })
        .collect();
    let held = fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("vectors.sha256"))
        .expect("vectors.sha256")
        .replace("\r\n", "\n");
    assert_eq!(held, listing, "vectors.sha256 is not the vectors as they are");
}
