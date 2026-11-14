#!/usr/bin/env bash
# Builds xray-rust's CLI (`xray-rust`, crate xray-cli) for this machine from
# the pin in scripts/rust-core-pins.sh, the project's own CLI build (its
# README: `cargo build --locked -p xray-cli`) with --release, the profile the
# iOS library is built with.
#
# <output-dir> receives xray-rust and xray-rust.build.json (commit, rustc,
# size, build time). The checkout and Cargo's target directory live in a
# temporary directory removed at exit.
#
# Usage: build-rust-core-cli.sh <output-dir>
# Needs rustup, git, python3.
set -euo pipefail

OUT="${1:?usage: build-rust-core-cli.sh <output-dir>}"
OUT="$(mkdir -p "${OUT}" && cd "${OUT}" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/rust-core-pins.sh
. "${HERE}/rust-core-pins.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/rust-core-cli.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

src="$(bash "${HERE}/rust-core-checkout.sh" "${work}/src" | tail -n 1)"
rustup toolchain install "${XRAY_RUST_TOOLCHAIN}" --profile minimal --no-self-update
export CARGO_TARGET_DIR="${work}/target"
export CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5

start="$(date +%s)"
(cd "${src}" && cargo "+${XRAY_RUST_TOOLCHAIN}" build --locked --release -p xray-cli)
seconds=$(( $(date +%s) - start ))
install -m 0755 "${CARGO_TARGET_DIR}/release/xray-rust" "${OUT}/xray-rust"

B_COMMIT="$(git -C "${src}" rev-parse HEAD)" B_RUSTC="$(rustc "+${XRAY_RUST_TOOLCHAIN}" -V)" \
B_SECONDS="${seconds}" B_BYTES="$(wc -c < "${OUT}/xray-rust" | tr -d ' ')" B_RUN="${GITHUB_RUN_ID:-local}" \
B_TLS="${SHAPED_RUSTLS_REPO}" \
python3 -I -c '
import json, os, sys
e = os.environ
json.dump({"commit": e["B_COMMIT"], "rustc": e["B_RUSTC"], "rustls_repo": e["B_TLS"],
           "cargo": "cargo build --locked --release -p xray-cli",
           "bytes": int(e["B_BYTES"]), "build_seconds": int(e["B_SECONDS"]),
           "built_by_run": e["B_RUN"]}, open(sys.argv[1], "w"), indent=1)
' "${OUT}/xray-rust.build.json"
cat "${OUT}/xray-rust.build.json"
