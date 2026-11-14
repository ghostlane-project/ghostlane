#!/usr/bin/env bash
# Checks out xray-rust at the pin in scripts/rust-core-pins.sh into <dir>.
#
# Fetches exactly XRAY_RUST_COMMIT (GitHub serves a commit by its full id),
# with no history, and refuses the tree when its HEAD is anything else or when
# its rust-toolchain.toml asks for another channel than XRAY_RUST_TOOLCHAIN.
# <dir> is replaced. Prints nothing on stdout but the checkout's path, last,
# so a caller can take it with `| tail -n 1`.
#
# Usage: rust-core-checkout.sh <dir>
# bash 3.2 safe (macOS runners).
set -euo pipefail

dir="${1:?usage: rust-core-checkout.sh <dir>}"
# shellcheck source=scripts/rust-core-pins.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/rust-core-pins.sh"

case "${XRAY_RUST_COMMIT}" in
    *[!0-9a-f]*) echo "XRAY_RUST_COMMIT '${XRAY_RUST_COMMIT}' is not a commit id" >&2; exit 1 ;;
esac
[ "${#XRAY_RUST_COMMIT}" -eq 40 ] || {
    echo "XRAY_RUST_COMMIT '${XRAY_RUST_COMMIT}' is not a full 40-digit commit" >&2
    exit 1
}

rm -rf "${dir}"
mkdir -p "${dir}"
dir="$(cd "${dir}" && pwd)"

echo "== ${XRAY_RUST_REPO} at ${XRAY_RUST_COMMIT} ==" >&2
git -C "${dir}" init --quiet
git -C "${dir}" remote add origin "https://github.com/${XRAY_RUST_REPO}.git"
git -C "${dir}" -c protocol.version=2 fetch --quiet --depth 1 origin "${XRAY_RUST_COMMIT}"
git -C "${dir}" -c advice.detachedHead=false checkout --quiet --detach FETCH_HEAD

got="$(git -C "${dir}" rev-parse HEAD)"
if [ "${got}" != "${XRAY_RUST_COMMIT}" ]; then
    echo "checked out ${got}, but rust-core-pins.sh pins ${XRAY_RUST_COMMIT}" >&2
    exit 1
fi

chan="$(sed -n 's/^channel *= *"\(.*\)" *$/\1/p' "${dir}/rust-toolchain.toml")"
if [ "${chan}" != "${XRAY_RUST_TOOLCHAIN}" ]; then
    echo "rust-toolchain.toml at ${XRAY_RUST_COMMIT:0:12} asks for '${chan}'; rust-core-pins.sh pins '${XRAY_RUST_TOOLCHAIN}'" >&2
    exit 1
fi

echo "xray-rust $(git -C "${dir}" log -1 --format='%h %cs %s') (toolchain ${chan})" >&2
echo "${dir}"
