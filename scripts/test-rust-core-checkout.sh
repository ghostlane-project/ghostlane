#!/usr/bin/env bash
# What rust-core-checkout.sh refuses, offline: the tree checks (--check-tree)
# on stand-in trees, and the directories it will not replace. No network: every
# case here is refused before the fetch.
# Needs bash, awk, sed, grep, git; Linux or macOS; under a second.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="${root}/scripts/rust-core-checkout.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
# shellcheck source=scripts/rust-core-pins.sh
. "${root}/scripts/rust-core-pins.sh"

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; failures=$((failures + 1)); }
said() { grep -qF -- "$1" "${work}/out"; }

ours="https://github.com/${SHAPED_RUSTLS_REPO}"
theirs="https://github.com/aimalygin/shaped-rustls"
rev="db373dbd4c1f0e7c8b9a6d5e4f3a2b1c0d9e8f7a"

# tree <dir> <channel> <rustls repo in Cargo.toml> <rustls repo in Cargo.lock> [extra lock source]
tree() {
    local dir="$1"
    rm -rf "${dir}"
    mkdir -p "${dir}"
    printf '[toolchain]\nchannel = "%s"\nprofile = "minimal"\n' "$2" > "${dir}/rust-toolchain.toml"
    cat > "${dir}/Cargo.toml" <<EOF
[workspace]
members = ["crates/xray-ffi"]

[workspace.dependencies]
rustls = { version = "0.23", default-features = false }

[patch.crates-io]
quinn = { path = "vendor/quinn" }
rustls = { git = "$3", rev = "${rev}" }

[profile.release]
lto = "thin"
EOF
    cat > "${dir}/Cargo.lock" <<EOF
version = 4

[[package]]
name = "quinn"
version = "0.11.9"

[[package]]
name = "rustls"
version = "0.23.40"
source = "git+$4?rev=${rev}#${rev}"
dependencies = [
 "ring",
]

[[package]]
name = "rustls-pki-types"
version = "1.12.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "0000000000000000000000000000000000000000000000000000000000000000"
EOF
    if [ -n "${5:-}" ]; then
        printf '\n[[package]]\nname = "other"\nversion = "0.1.0"\nsource = "%s"\n' "$5" >> "${dir}/Cargo.lock"
    fi
}
check() { bash "${script}" --check-tree "$1" > "${work}/out" 2>&1; }

# 1. The pin's shape: rustls from our fork in both files.
tree "${work}/t1" "${XRAY_RUST_TOOLCHAIN}" "${ours}" "${ours}"
if check "${work}/t1"; then pass "a tree taking rustls from ${SHAPED_RUSTLS_REPO} passes"
else fail "a tree taking rustls from ${SHAPED_RUSTLS_REPO} passes"; cat "${work}/out"; fi

# 2. Upstream's shape (4a61b057): rustls from the upstream author's repository.
tree "${work}/t2" "${XRAY_RUST_TOOLCHAIN}" "${theirs}" "${theirs}"
if ! check "${work}/t2" && said "does not take rustls from ${ours}"; then
    pass "a tree taking rustls from aimalygin/shaped-rustls is refused"
else fail "a tree taking rustls from aimalygin/shaped-rustls is refused"; cat "${work}/out"; fi

# 3. Cargo.toml moved, Cargo.lock not.
tree "${work}/t3" "${XRAY_RUST_TOOLCHAIN}" "${ours}" "${theirs}"
if ! check "${work}/t3" && said "Cargo.lock" && said "not ${ours}"; then
    pass "a Cargo.lock still recording the upstream rustls is refused"
else fail "a Cargo.lock still recording the upstream rustls is refused"; cat "${work}/out"; fi

# 4. A repository merely named like ours (a prefix) is not ours.
tree "${work}/t4" "${XRAY_RUST_TOOLCHAIN}" "${ours}-mirror" "${ours}-mirror"
if ! check "${work}/t4"; then pass "a repository whose name only starts like ours is refused"
else fail "a repository whose name only starts like ours is refused"; cat "${work}/out"; fi

# 5. Another git source anywhere in the lock file.
tree "${work}/t5" "${XRAY_RUST_TOOLCHAIN}" "${ours}" "${ours}" "git+https://github.com/someone/other?rev=1#1"
if ! check "${work}/t5" && said "git sources outside ghostlane-project"; then
    pass "a git source outside ghostlane-project is refused"
else fail "a git source outside ghostlane-project is refused"; cat "${work}/out"; fi

# 6. Another toolchain.
tree "${work}/t6" "1.95.0" "${ours}" "${ours}"
if ! check "${work}/t6" && said "asks for '1.95.0'"; then pass "another toolchain channel is refused"
else fail "another toolchain channel is refused"; cat "${work}/out"; fi

# 7. Directories it must never replace; each refused before any fetch, and a
#    directory of something else keeps its contents.
mkdir -p "${work}/home/.config" "${work}/other"
echo keep > "${work}/other/file"
git -C "${work}/other" init --quiet
git -C "${work}/other" remote add origin https://github.com/someone/else.git
if ! bash "${script}" "" > "${work}/out" 2>&1 && said "usage:"; then pass "refuses an empty directory name"
else fail "refuses an empty directory name"; cat "${work}/out"; fi
for dir in / . .. "${work}/home" "${work}/other" "${work}/other/file"; do
    if ! HOME="${work}/home" bash "${script}" "${dir}" > "${work}/out" 2>&1 \
        && said "will not replace" && [ "$(cat "${work}/other/file")" = keep ]; then
        pass "refuses to replace '${dir}'"
    else fail "refuses to replace '${dir}'"; cat "${work}/out"; fi
done

if [ "${failures}" -ne 0 ]; then
    echo "${failures} case(s) failed"
    exit 1
fi
echo "all cases passed"
