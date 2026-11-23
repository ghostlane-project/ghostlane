#!/usr/bin/env bash
# Checks out xray-rust, the Rust core, into <dir>: the commit
# scripts/ghostlane-core-resolve.sh names, which is the pin in
# scripts/ghostlane-core-pin.sh, or [commit] when a run asks for another one
# (a workflow_dispatch's `pin`; empty means the pin).
#
# Fetches exactly that commit (GitHub serves a commit by its full id), with no
# history, and refuses the tree when
#   - its HEAD is anything else;
#   - its rust-toolchain.toml asks for another channel than the pin file's
#     toolchain;
#   - it takes rustls from anywhere but ghostlane-project/shaped-rustls: the
#     [patch.crates-io] line of its Cargo.toml and the source its Cargo.lock
#     records must both name that repository;
#   - its Cargo.lock names a git source outside ghostlane-project.
# <dir> is replaced, but only when it is empty, absent or an earlier checkout
# of the same repository: never /, $HOME or a directory of something else.
# Prints nothing on stdout but the checkout's path, last, so a caller can take
# it with `| tail -n 1`.
#
# Usage: rust-core-checkout.sh <dir> [commit]
#        rust-core-checkout.sh --check-tree <dir>   the tree checks alone, on a
#                                                    tree already there
#                                                    (test-rust-core-checkout.sh)
# bash 3.2 safe (macOS runners).
set -euo pipefail

usage="usage: rust-core-checkout.sh <dir> [commit] | --check-tree <dir>"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The TLS the core is built with: our shaped rustls, and no other.
SHAPED_RUSTLS_REPO="ghostlane-project/shaped-rustls"

# The commit, its repository and its toolchain, from the one pin.
if [ "${1:-}" = "--check-tree" ]; then
    resolved="$(bash "${here}/ghostlane-core-resolve.sh")"
else
    resolved="$(bash "${here}/ghostlane-core-resolve.sh" "${2:-}")"
fi
CORE_REPO="$(sed -n 's/^GHOSTLANE_CORE_REPO=//p' <<< "${resolved}")"
CORE_COMMIT="$(sed -n 's/^GHOSTLANE_CORE_COMMIT=//p' <<< "${resolved}")"
CORE_TOOLCHAIN="$(sed -n 's/^GHOSTLANE_CORE_TOOLCHAIN=//p' <<< "${resolved}")"
remote="https://github.com/${CORE_REPO}.git"

# check_tree <dir>: the toolchain and the TLS source of a checked-out tree.
# Prints the rustls revision the tree takes.
check_tree() {
    local tree="$1" chan tls_repo patch_line lock_source other_git
    chan="$(sed -n 's/^channel *= *"\(.*\)" *$/\1/p' "${tree}/rust-toolchain.toml")"
    if [ "${chan}" != "${CORE_TOOLCHAIN}" ]; then
        echo "rust-toolchain.toml at ${CORE_COMMIT:0:12} asks for '${chan}'; the pin file names '${CORE_TOOLCHAIN}'" >&2
        return 1
    fi
    # rustls, from our shaped-rustls fork and nowhere else.
    tls_repo="https://github.com/${SHAPED_RUSTLS_REPO}"
    patch_line="$(awk '/^\[patch\.crates-io\]/{on=1; next} /^\[/{on=0} on && /^rustls[ =]/' "${tree}/Cargo.toml")"
    case "${patch_line}" in
        *"git = \"${tls_repo}\""*) ;;
        *)
            echo "Cargo.toml at ${CORE_COMMIT:0:12} does not take rustls from ${tls_repo}: [patch.crates-io] says '${patch_line:-nothing}'" >&2
            return 1
            ;;
    esac
    lock_source="$(awk '/^\[\[package\]\]/{name=""} /^name = /{name=$3} /^source = / && name == "\"rustls\"" {print $3}' "${tree}/Cargo.lock")"
    case "${lock_source}" in
        "\"git+${tls_repo}?rev="*) ;;
        *)
            echo "Cargo.lock at ${CORE_COMMIT:0:12} records rustls from ${lock_source:-no source}, not ${tls_repo}" >&2
            return 1
            ;;
    esac
    other_git="$(grep -E '^source = "git\+' "${tree}/Cargo.lock" | grep -vF 'source = "git+https://github.com/ghostlane-project/' || true)"
    if [ -n "${other_git}" ]; then
        echo "Cargo.lock at ${CORE_COMMIT:0:12} names git sources outside ghostlane-project:" >&2
        echo "${other_git}" >&2
        return 1
    fi
    lock_source="${lock_source##*#}"
    echo "${lock_source%\"}"
}

if [ "${1:-}" = "--check-tree" ]; then
    check_tree "${2:?${usage}}" >/dev/null
    exit 0
fi
dir="${1:?${usage}}"

refuse_dir() { echo "rust-core-checkout.sh: will not replace '${dir}': $1" >&2; exit 1; }
case "${dir}" in
    ""|/|.|..) refuse_dir "not a checkout directory" ;;
esac
if [ -e "${dir}" ]; then
    [ -d "${dir}" ] || refuse_dir "not a directory"
    abs="$(cd "${dir}" && pwd -P)"
    [ "${abs}" != "/" ] || refuse_dir "it is /"
    [ "${abs}" != "$(cd "${HOME}" && pwd -P)" ] || refuse_dir "it is \$HOME"
    if [ -n "$(ls -A "${dir}")" ]; then
        top="$(git -C "${dir}" rev-parse --show-toplevel 2>/dev/null || true)"
        url="$(git -C "${dir}" remote get-url origin 2>/dev/null || true)"
        if [ -z "${top}" ] || [ "$(cd "${top}" && pwd -P)" != "${abs}" ] || [ "${url}" != "${remote}" ]; then
            refuse_dir "it is not empty and not an earlier checkout of ${remote}"
        fi
    fi
fi

rm -rf "${dir}"
mkdir -p "${dir}"
dir="$(cd "${dir}" && pwd)"

echo "== ${CORE_REPO} at ${CORE_COMMIT} ==" >&2
git -C "${dir}" init --quiet
git -C "${dir}" remote add origin "${remote}"
git -C "${dir}" -c protocol.version=2 fetch --quiet --depth 1 origin "${CORE_COMMIT}"
git -C "${dir}" -c advice.detachedHead=false checkout --quiet --detach FETCH_HEAD

got="$(git -C "${dir}" rev-parse HEAD)"
if [ "${got}" != "${CORE_COMMIT}" ]; then
    echo "checked out ${got}, but the run asked for ${CORE_COMMIT}" >&2
    exit 1
fi
tls_rev="$(check_tree "${dir}")"

echo "xray-rust $(git -C "${dir}" log -1 --format='%h %cs %s') (toolchain ${CORE_TOOLCHAIN}; rustls ${SHAPED_RUSTLS_REPO}@${tls_rev:0:12})" >&2
echo "${dir}"
