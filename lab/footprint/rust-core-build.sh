#!/usr/bin/env bash
# rust-core-build.sh <dest-dir>
#
# Builds xray-rust, the Rust client core the footprint lab measures, from
# GitHub at a pinned commit of a named ref. The lab builds Ghostlane's fork
# (https://github.com/ghostlane-project/xray-rust, MPL-2.0) at its branches,
# and once the upstream release it measured before the fork
# (https://github.com/aimalygin/xray-rust v0.9.0) as the historical baseline.
# The pins come from the environment (the workflow's `env:`):
#
#   XRS_REPO       the repository to fetch from
#   XRS_REF        the branch or tag the build stands for
#   XRS_COMMIT     the commit built: fetched by its hash, so the build is the
#                  same whatever the ref says now
#   XRS_TOOLCHAIN  the channel that commit's rust-toolchain.toml asks for
#
# The build refuses a checkout whose rust-toolchain.toml asks for another
# channel. When the ref no longer names the commit (a branch moved on), it
# still builds the pinned commit, prints a notice, and the build record keeps
# both (`commit`, `ref_head_at_build`); moving a pin is a change to the
# workflow.
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
# working directory, applied with `git apply` to the checked-out commit
# before the build: lab-only code (lab/footprint/xray-rust-patches/), never
# a change to the pin. The build record names each patch and its sha256.
# Unset, the build is the commit as published.
#
# XRS_LINE_TABLES=1 (optional): the same build with line tables kept, for the
# heap profile (lab.py heap-profile, heaptrack on Linux):
# CARGO_PROFILE_RELEASE_DEBUG=line-tables-only and
# CARGO_PROFILE_RELEASE_STRIP=none over the workspace's [profile.release],
# so every allocation's backtrace has a file and a line, inlined frames
# included. Debug info is not loaded at run time and does not change the
# code; the build record names the two overrides.
#
# bash 3.2 safe (macOS runners).
set -euo pipefail

dest="${1:?usage: rust-core-build.sh <dest-dir>}"
repo="${XRS_REPO:?XRS_REPO is not set}"
ref="${XRS_REF:?XRS_REF is not set}"
commit="${XRS_COMMIT:?XRS_COMMIT is not set}"
want_chan="${XRS_TOOLCHAIN:?XRS_TOOLCHAIN is not set}"
patches="${XRS_PATCHES:-}"
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/xray-rust-build-$(basename "${dest}")"

if [ -n "${XRS_BUILD_LOG:-}" ]; then
  mkdir -p "$(dirname "${XRS_BUILD_LOG}")"
  exec > >(tee -a "${XRS_BUILD_LOG}") 2>&1
fi

rm -rf "${work}"
mkdir -p "${work}"
src="${work}/src"

echo "== fetch ${repo} at ${commit} (${ref}) =="
git init --quiet "${src}"
git -C "${src}" remote add origin "${repo}"
git -C "${src}" fetch --quiet --depth 1 --no-tags origin "${commit}"
git -C "${src}" -c advice.detachedHead=false checkout --quiet --detach FETCH_HEAD
got="$(git -C "${src}" rev-parse HEAD)"
if [ "${got}" != "${commit}" ]; then
  echo "fetched ${got}, but the lab pins ${commit}" >&2
  exit 1
fi
# What the ref names now: a branch's head, or a tag's commit (peeled).
heads="$(git -C "${src}" ls-remote origin "refs/heads/${ref}" "refs/tags/${ref}" "refs/tags/${ref}^{}" || true)"
ref_head="$(printf '%s\n' "${heads}" | awk '$2 ~ /\^\{\}$/ {print $1; exit}')"
if [ -z "${ref_head}" ]; then
  ref_head="$(printf '%s\n' "${heads}" | awk 'NF {print $1; exit}')"
fi
if [ -z "${ref_head}" ]; then
  echo "::warning::${repo} has no branch or tag named ${ref}; building the pinned ${commit}"
elif [ "${ref_head}" != "${commit}" ]; then
  echo "::notice::${ref} in ${repo} is now at ${ref_head}; this build is the pinned ${commit}"
else
  echo "${ref} names ${commit}"
fi

chan="$(sed -n 's/^channel *= *"\(.*\)" *$/\1/p' "${src}/rust-toolchain.toml")"
if [ "${chan}" != "${want_chan}" ]; then
  echo "rust-toolchain.toml at ${commit} asks for '${chan}', the workflow pins '${want_chan}'" >&2
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
  git -C "${src}" status --short
fi

echo "== toolchain ${chan} =="
rustup toolchain install "${chan}" --profile minimal --no-self-update
rustc_v="$(rustc "+${chan}" -V)"
host="$(rustc "+${chan}" -vV | sed -n 's/^host: //p')"
echo "${rustc_v} (${host})"

echo "== [profile.release] at ${commit} =="
profile="$(awk '/^\[profile\.release\]/{on=1; next} /^\[/{on=0} on && NF' "${src}/Cargo.toml" | tr '\n' ';' | sed 's/;$//')"
echo "${profile}"

overrides=""
if [ "${XRS_LINE_TABLES:-}" = "1" ]; then
  export CARGO_PROFILE_RELEASE_DEBUG=line-tables-only CARGO_PROFILE_RELEASE_STRIP=none
  overrides="CARGO_PROFILE_RELEASE_DEBUG=line-tables-only CARGO_PROFILE_RELEASE_STRIP=none"
  echo "== over it: ${overrides} =="
fi

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
B_REPO="${repo}" B_REF="${ref}" B_COMMIT="${commit}" B_REF_HEAD="${ref_head}" B_CHAN="${chan}" \
B_RUSTC="${rustc_v}" B_HOST="${host}" B_PROFILE="${profile}" B_SECONDS="${seconds}" B_BYTES="${bytes}" \
B_RUN="${GITHUB_RUN_ID:-local}" B_PATCHES="${patch_paths}" B_OVERRIDES="${overrides}" python3 -I -c '
import hashlib, json, os, sys
e = os.environ
patches = [{"file": f, "sha256": hashlib.sha256(open(f, "rb").read()).hexdigest()} for f in e["B_PATCHES"].split()]
overrides = e["B_OVERRIDES"].split()
json.dump({
    "repo": e["B_REPO"], "ref": e["B_REF"], "tag": e["B_REF"], "commit": e["B_COMMIT"],
    "ref_head_at_build": e["B_REF_HEAD"] or None,
    "toolchain": e["B_CHAN"], "rustc": e["B_RUSTC"], "host": e["B_HOST"],
    "cargo": " ".join(overrides + ["cargo build --locked --release -p xray-cli"]),
    "profile_release": e["B_PROFILE"],
    "profile_overrides": overrides,
    "build_seconds": int(e["B_SECONDS"]), "bytes": int(e["B_BYTES"]),
    "built_by_run": e["B_RUN"],
    "patches": patches,
}, open(sys.argv[1], "w"), indent=1)
' "${dest}/xray-rust.build.json"
cat "${dest}/xray-rust.build.json"
