#!/usr/bin/env bash
# What fetch-rust-core-ios.sh does with what it is given, offline: the script
# itself, copied beside a pins file of the test's own whose URL is a file://
# path, against a stand-in XrayRust-ios.zip (no build, no network, no Xcode).
# Needs bash, curl, unzip, python3; Linux or macOS; a second or two.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; failures=$((failures + 1)); }
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# make_zip <out.zip> [slice to leave out] [file to leave out of every slice]
make_zip() {
    local out="$1" skip_slice="${2:-}" skip_file="${3:-}" tree slice f
    tree="$(mktemp -d "${work}/tree.XXXXXX")"
    for slice in ios-arm64 ios-arm64_x86_64-simulator; do
        [ "${slice}" = "${skip_slice}" ] && continue
        mkdir -p "${tree}/XrayRust.xcframework/${slice}/Headers/XrayRust"
        printf '!<arch>\n%s\n' "${slice}" > "${tree}/XrayRust.xcframework/${slice}/libxray_ffi.a"
        for f in xray_ffi.h module.modulemap; do
            [ "${f}" = "${skip_file}" ] && continue
            echo "/* ${f} */" > "${tree}/XrayRust.xcframework/${slice}/Headers/XrayRust/${f}"
        done
    done
    echo '<plist/>' > "${tree}/XrayRust.xcframework/Info.plist"
    echo '{"commit": "test"}' > "${tree}/rust-core-ios.build.json"
    python3 -I - "${tree}" "${out}" <<'PY'
import os, sys, zipfile
tree, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for top, _, files in os.walk(tree):
        for name in files:
            path = os.path.join(top, name)
            z.write(path, os.path.relpath(path, tree))
PY
}

# setup <sha256 to pin>: a scripts/ directory holding the real fetch script and
# a pins file naming the release under ${work}/release.
setup() {
    mkdir -p "${work}/scripts" "${work}/release"
    command cp -f "${root}/scripts/fetch-rust-core-ios.sh" "${work}/scripts/"
    cat > "${work}/scripts/rust-core-pins.sh" <<EOF
XRAY_RUST_COMMIT=0123456789abcdef0123456789abcdef01234567
RUST_CORE_IOS_BUILD=1
RUST_CORE_IOS_TAG=rust-core-ios-0123456789ab-b1
RUST_CORE_IOS_ASSET=XrayRust-ios.zip
RUST_CORE_IOS_URL="file://${work}/release/XrayRust-ios.zip"
RUST_CORE_IOS_SHA256="$1"
EOF
}

# fetch: runs the copy into ${work}/dest with the cache in ${work}/cache;
# its output is in ${work}/out.
fetch() {
    RUST_CORE_CACHE_DIR="${work}/cache" bash "${work}/scripts/fetch-rust-core-ios.sh" "${work}/dest" > "${work}/out" 2>&1
}
said() { grep -qF -- "$1" "${work}/out"; }
present() { test -f "${work}/dest/XrayRust.xcframework/ios-arm64/libxray_ffi.a"; }
stamp() { cat "${work}/dest/XrayRust.xcframework.tag" 2>/dev/null || true; }
# built_here <stamp>: the destination as build-rust-core-ios.sh leaves it, a
# framework and its build record, stamped <stamp>.
built_here() {
    rm -rf "${work}/dest"
    mkdir -p "${work}/dest"
    unzip -q "${work}/good.zip" -d "${work}/dest"
    echo local > "${work}/dest/XrayRust.xcframework/ios-arm64/libxray_ffi.a"
    printf '%s\n' "$1" > "${work}/dest/XrayRust.xcframework.tag"
}
local_build() { test "$(cat "${work}/dest/XrayRust.xcframework/ios-arm64/libxray_ffi.a" 2>/dev/null)" = local; }

make_zip "${work}/good.zip"
good="$(sha256_of "${work}/good.zip")"
make_zip "${work}/other.zip" "" "" && echo extra >> "${work}/other.zip"
cached="${work}/cache/rust-core-ios-0123456789ab-b1/XrayRust-ios.zip"
local_stamp="rust-core-ios-0123456789ab-b1 local 0123456789abcdef0123456789abcdef01234567"

# 1. Nothing published yet: no sha256 pinned, nothing downloaded.
setup ""
command cp -f "${work}/good.zip" "${work}/release/XrayRust-ios.zip"
if ! fetch && said "pins no sha256" && ! present && [ ! -e "${work}/cache" ]; then
    pass "an empty pin refuses before any download"
else fail "an empty pin refuses before any download"; cat "${work}/out"; fi

# 2. A pin that is not a sha256.
setup "abc"
if ! fetch && said "not 64 hex digits"; then pass "a malformed pin is refused"
else fail "a malformed pin is refused"; cat "${work}/out"; fi

# 3. The release holds other bytes than the pin's: refused, nothing unpacked
#    or cached.
setup "${good}"
command cp -f "${work}/other.zip" "${work}/release/XrayRust-ios.zip"
if ! fetch && said "refusing it" && ! present && [ ! -e "${cached}" ]; then
    pass "a download with another sha256 is refused, not unpacked, not cached"
else fail "a download with another sha256 is refused, not unpacked, not cached"; cat "${work}/out"; fi

# 4. The pinned bytes: unpacked, stamped with tag and sha256, cached, and the
#    build record kept beside the framework.
command cp -f "${work}/good.zip" "${work}/release/XrayRust-ios.zip"
if fetch && present && [ "$(stamp)" = "rust-core-ios-0123456789ab-b1 ${good}" ] \
    && [ -f "${cached}" ] && [ -f "${work}/dest/XrayRust.xcframework.build.json" ] \
    && [ -f "${work}/dest/XrayRust.xcframework/ios-arm64_x86_64-simulator/Headers/XrayRust/module.modulemap" ]; then
    pass "the pinned asset is unpacked, stamped and cached"
else fail "the pinned asset is unpacked, stamped and cached"; cat "${work}/out"; fi

# 5. Second run: nothing to do.
if fetch && said "already present"; then pass "a stamped framework is kept"
else fail "a stamped framework is kept"; cat "${work}/out"; fi

# 6. The destination swept and the release gone: restored from the cache.
rm -rf "${work}/dest" "${work}/release/XrayRust-ios.zip"
if fetch && said "from ${work}/cache" && present; then pass "the cache restores a swept destination offline"
else fail "the cache restores a swept destination offline"; cat "${work}/out"; fi

# 7. A cache entry with other bytes is not trusted: refused, then downloaded.
rm -rf "${work}/dest"
command cp -f "${work}/other.zip" "${cached}"
command cp -f "${work}/good.zip" "${work}/release/XrayRust-ios.zip"
if fetch && said "refusing it" && said "fetching" && present && cmp -s "${cached}" "${work}/good.zip"; then
    pass "a tampered cache entry is refused and replaced by the download"
else fail "a tampered cache entry is refused and replaced by the download"; cat "${work}/out"; fi

# 8. A new pin: the stamped framework is not trusted. The new asset lacks the
#    simulator slice, so it is refused, and the old framework is gone too,
#    with its build record and its stamp.
make_zip "${work}/noslice.zip" ios-arm64_x86_64-simulator
setup "$(sha256_of "${work}/noslice.zip")"
command cp -f "${work}/noslice.zip" "${work}/release/XrayRust-ios.zip"
rm -rf "${work}/cache"
if ! fetch && said "replacing" && said "has no ios-arm64_x86_64-simulator slice" && ! present \
    && [ ! -e "${work}/dest/XrayRust.xcframework.build.json" ] && [ ! -e "${work}/dest/XrayRust.xcframework.tag" ]; then
    pass "a pin bump replaces the framework and its record, and an asset short of a slice is refused"
else fail "a pin bump replaces the framework and its record, and an asset short of a slice is refused"; cat "${work}/out"; fi

# 9. An asset without the module map: refused.
make_zip "${work}/nomap.zip" "" module.modulemap
setup "$(sha256_of "${work}/nomap.zip")"
command cp -f "${work}/nomap.zip" "${work}/release/XrayRust-ios.zip"
rm -rf "${work}/cache" "${work}/dest"
if ! fetch && said "carries no Headers/XrayRust/module.modulemap" && ! present; then
    pass "an asset without the module map is refused"
else fail "an asset without the module map is refused"; cat "${work}/out"; fi

# 10. The pins the app ships: the tag and URL are derived from the commit and
#     the build number, and the sha256 is empty or 64 hex digits.
if (
    unset XRAY_RUST_REPO XRAY_RUST_COMMIT XRAY_RUST_TOOLCHAIN SHAPED_RUSTLS_REPO RUST_CORE_IOS_BUILD
    # shellcheck source=scripts/rust-core-pins.sh
    . "${root}/scripts/rust-core-pins.sh"
    [ "${RUST_CORE_IOS_TAG}" = "rust-core-ios-${XRAY_RUST_COMMIT:0:12}-b${RUST_CORE_IOS_BUILD}" ] &&
    grep -qE '^[1-9][0-9]*$' <<< "${RUST_CORE_IOS_BUILD}" &&
    [ "${RUST_CORE_IOS_URL}" = "https://github.com/ghostlane-project/ghostlane/releases/download/${RUST_CORE_IOS_TAG}/XrayRust-ios.zip" ] &&
    { [ -z "${RUST_CORE_IOS_SHA256}" ] || grep -qE '^[0-9a-f]{64}$' <<< "${RUST_CORE_IOS_SHA256}"; } &&
    grep -qE '^[0-9a-f]{40}$' <<< "${XRAY_RUST_COMMIT}" &&
    [ "${SHAPED_RUSTLS_REPO}" = "ghostlane-project/shaped-rustls" ]
); then pass "rust-core-pins.sh derives its tag and URL from a full commit and a build number"
else fail "rust-core-pins.sh derives its tag and URL from a full commit and a build number"; fi

# 11. A packaging fix at the same commit gets a tag of its own.
if (
    unset XRAY_RUST_COMMIT
    export RUST_CORE_IOS_BUILD=2
    # shellcheck source=scripts/rust-core-pins.sh
    . "${root}/scripts/rust-core-pins.sh"
    [ "${RUST_CORE_IOS_TAG}" = "rust-core-ios-${XRAY_RUST_COMMIT:0:12}-b2" ]
); then pass "a new build number names a new tag at the same commit"
else fail "a new build number names a new tag at the same commit"; fi

# 12. A framework built here, nothing published, and asked for: used as is.
setup ""
built_here "${local_stamp}"
if RUST_CORE_IOS_ALLOW_LOCAL=1 fetch && said "built here" && local_build && [ "$(stamp)" = "${local_stamp}" ]; then
    pass "a local build is used with RUST_CORE_IOS_ALLOW_LOCAL=1 and no pin"
else fail "a local build is used with RUST_CORE_IOS_ALLOW_LOCAL=1 and no pin"; cat "${work}/out"; fi

# 13. The same, not asked for: refused, and the build is left alone.
if ! fetch && said "RUST_CORE_IOS_ALLOW_LOCAL=1" && said "pins no sha256" && local_build; then
    pass "a local build without the opt-in is refused and kept"
else fail "a local build without the opt-in is refused and kept"; cat "${work}/out"; fi

# 14. The same with a published pin, not asked for: replaced by the published.
setup "${good}"
command cp -f "${work}/good.zip" "${work}/release/XrayRust-ios.zip"
rm -rf "${work}/cache"
if fetch && said "replacing" && present && ! local_build && [ "$(stamp)" = "rust-core-ios-0123456789ab-b1 ${good}" ] \
    && [ ! -e "${work}/dest/rust-core-ios.build.json" ] && [ -f "${work}/dest/XrayRust.xcframework.build.json" ]; then
    pass "a local build without the opt-in gives way to the published pin"
else fail "a local build without the opt-in gives way to the published pin"; cat "${work}/out"; fi

# 15. A local build for another tag (an older pin or build number) is never
#     used, even when asked for.
setup ""
built_here "rust-core-ios-0123456789ab-b0 local 0123456789abcdef0123456789abcdef01234567"
if ! RUST_CORE_IOS_ALLOW_LOCAL=1 fetch && said "pins no sha256" && ! said "built here"; then
    pass "a local build for another tag is not used"
else fail "a local build for another tag is not used"; cat "${work}/out"; fi

if [ "${failures}" -ne 0 ]; then
    echo "${failures} case(s) failed"
    exit 1
fi
echo "all cases passed"
