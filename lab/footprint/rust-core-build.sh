#!/usr/bin/env bash
# rust-core-build.sh <dest-dir>
#
# Builds xray-rust (https://github.com/aimalygin/xray-rust, MPL-2.0), the Rust
# client core the footprint lab sets beside the Go cores, from GitHub at the
# pinned release tag. The pins come from the environment (the workflow's
# `env:`), and the build refuses a checkout that disagrees with them:
#
#   XRAY_RUST_TAG        the newest release when the lab was written (v0.9.0,
#                        published 2026-11-03, read through the GitHub API)
#   XRAY_RUST_COMMIT     the commit that tag names
#   XRAY_RUST_TOOLCHAIN  the channel the tag's rust-toolchain.toml asks for
#
# The recipe is the project's own CLI build (README "Quick start": `cargo
# build --locked -p xray-cli`) with --release, which selects the workspace's
# [profile.release]: codegen-units 1, thin LTO, panic unwind, symbols
# stripped. The project's mobile scripts (scripts/build-apple-xcframework.sh,
# scripts/build-android-libs.sh) build the xray-ffi static library with that
# same profile; there is no separate mobile cargo profile. What a phone gets
# on top is a runtime profile (TunRuntimeProfile, the transport memory class
# and the Tokio worker count), which lab.py sets when it runs the binary.
#
# <dest-dir> receives xray-rust and xray-rust.build.json only after the build
# succeeded, so a cache of <dest-dir> never holds half a build. The build log
# goes to stdout, and to $XRS_BUILD_LOG as well when that is set.
#
# XRS_PATCHES (optional): patch files, space-separated, relative to the
# working directory, applied with `git apply` to the checked-out tag before
# the build. The lab uses it only for a setting that xray-rust holds as a
# constant (lab/footprint/xray-rust-patches/); the pin itself never moves,
# and the build record names each patch and its sha256. Unset, the build is
# the tag as published.
#
# bash 3.2 safe (macOS runners).
set -euo pipefail

dest="${1:?usage: rust-core-build.sh <dest-dir>}"
tag="${XRAY_RUST_TAG:?XRAY_RUST_TAG is not set}"
commit="${XRAY_RUST_COMMIT:?XRAY_RUST_COMMIT is not set}"
want_chan="${XRAY_RUST_TOOLCHAIN:?XRAY_RUST_TOOLCHAIN is not set}"
repo="${XRAY_RUST_REPO:-https://github.com/aimalygin/xray-rust.git}"
patches="${XRS_PATCHES:-}"
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/xray-rust-build-$(basename "${dest}")"

if [ -n "${XRS_BUILD_LOG:-}" ]; then
  mkdir -p "$(dirname "${XRS_BUILD_LOG}")"
  exec > >(tee -a "${XRS_BUILD_LOG}") 2>&1
fi

rm -rf "${work}"
mkdir -p "${work}"
src="${work}/src"

echo "== clone ${repo} at ${tag} =="
git clone --quiet --depth 1 --branch "${tag}" "${repo}" "${src}"
got="$(git -C "${src}" rev-parse HEAD)"
if [ "${got}" != "${commit}" ]; then
  echo "tag ${tag} is at ${got}, but the lab pins ${commit}; re-read the release before moving the pin" >&2
  exit 1
fi

chan="$(sed -n 's/^channel *= *"\(.*\)" *$/\1/p' "${src}/rust-toolchain.toml")"
if [ "${chan}" != "${want_chan}" ]; then
  echo "rust-toolchain.toml at ${tag} asks for '${chan}', the workflow pins '${want_chan}'" >&2
  exit 1
fi

patch_paths=""
for pf in ${patches}; do
  test -f "${pf}" || { echo "XRS_PATCHES names ${pf}, which is not a file" >&2; exit 1; }
  abs="$(cd "$(dirname "${pf}")" && pwd)/$(basename "${pf}")"
  echo "== git apply ${pf} =="
  git -C "${src}" apply --verbose "${abs}"
  patch_paths="${patch_paths} ${pf}"
done
if [ -n "${patch_paths}" ]; then
  git -C "${src}" --no-pager diff --stat
fi

echo "== toolchain ${chan} =="
rustup toolchain install "${chan}" --profile minimal --no-self-update
rustc_v="$(rustc "+${chan}" -V)"
host="$(rustc "+${chan}" -vV | sed -n 's/^host: //p')"
echo "${rustc_v} (${host})"

echo "== [profile.release] at ${tag} =="
profile="$(awk '/^\[profile\.release\]/{on=1; next} /^\[/{on=0} on && NF' "${src}/Cargo.toml" | tr '\n' ';' | sed 's/;$//')"
echo "${profile}"

echo "== cargo build --locked --release -p xray-cli =="
start="$(date +%s)"
(
  cd "${src}"
  CARGO_TERM_COLOR=never CARGO_INCREMENTAL=0 CARGO_NET_RETRY=5 \
    cargo "+${chan}" build --locked --release -p xray-cli
)
seconds=$(( $(date +%s) - start ))
bin="${src}/target/release/xray-rust"
test -x "${bin}"
bytes="$(wc -c < "${bin}" | tr -d ' ')"
echo "built ${bin}: ${bytes} bytes in ${seconds} s"

mkdir -p "${dest}"
install -m 0755 "${bin}" "${dest}/xray-rust"
# Values go through the environment: the profile text carries quotes.
B_REPO="${repo}" B_TAG="${tag}" B_COMMIT="${commit}" B_CHAN="${chan}" B_RUSTC="${rustc_v}" \
B_HOST="${host}" B_PROFILE="${profile}" B_SECONDS="${seconds}" B_BYTES="${bytes}" \
B_RUN="${GITHUB_RUN_ID:-local}" B_PATCHES="${patch_paths}" python3 -I -c '
import hashlib, json, os, sys
e = os.environ
patches = [{"file": f, "sha256": hashlib.sha256(open(f, "rb").read()).hexdigest()} for f in e["B_PATCHES"].split()]
json.dump({
    "repo": e["B_REPO"], "tag": e["B_TAG"], "commit": e["B_COMMIT"],
    "toolchain": e["B_CHAN"], "rustc": e["B_RUSTC"], "host": e["B_HOST"],
    "cargo": "cargo build --locked --release -p xray-cli",
    "profile_release": e["B_PROFILE"],
    "build_seconds": int(e["B_SECONDS"]), "bytes": int(e["B_BYTES"]),
    "built_by_run": e["B_RUN"],
    "patches": patches,
}, open(sys.argv[1], "w"), indent=1)
' "${dest}/xray-rust.build.json"
cat "${dest}/xray-rust.build.json"
