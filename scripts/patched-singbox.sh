#!/usr/bin/env bash
# patched-singbox.sh <dest>
#
# Writes the sing-box source every Ghostlane build compiles into <dest>: the
# module at SINGBOX_VERSION (scripts/cores-pins.sh), as the Go checksum
# database vouches for it and with the hash pinned there, plus
# scripts/patches/sing-box-reality-hello.patch. Called by the CLI's Makefile
# (cli/go.mod replaces sing-box with this tree), by release.yml for Android
# and the three desktops (each builds ./cmd/sing-box in it) and by
# build-cores-ios.sh. One tree for all of them, so no platform ships the
# hello the patch exists to change.
#
# The copy comes from the module cache rather than a clone, as for the iOS
# Cores' xray-core: it is the content the checksum database verified, the
# same bytes cli/go.mod used to resolve.
set -euo pipefail

dest="${1:?usage: patched-singbox.sh <dest>}"
here="$(cd "$(dirname "$0")" && pwd)"
# The pins win over anything inherited: the tree is the pinned version or no
# tree at all.
unset SINGBOX_VERSION SINGBOX_MODULE_SUM
# shellcheck source=scripts/cores-pins.sh
. "${here}/cores-pins.sh"
patch_file="${here}/patches/sing-box-reality-hello.patch"
module="github.com/sagernet/sing-box@v${SINGBOX_VERSION}"

# Outside any module, so nothing here can resolve another version; the
# download is checked against the checksum database (GOSUMDB stays default).
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
(cd "${work}" && GOFLAGS=-mod=mod go mod download -json "${module}") > "${work}/download.json"
sum="$(sed -n 's/^[[:space:]]*"Sum": "\(.*\)",*$/\1/p' "${work}/download.json")"
if [ "${sum}" != "${SINGBOX_MODULE_SUM}" ]; then
  echo "${module}: the module hashes to '${sum}', cores-pins.sh pins '${SINGBOX_MODULE_SUM}'." >&2
  exit 1
fi
src="$(go env GOMODCACHE)/github.com/sagernet/sing-box@v${SINGBOX_VERSION}"
if command -v cygpath >/dev/null 2>&1; then src="$(cygpath -u "${src}")"; fi
[ -f "${src}/go.mod" ] || { echo "${module}: not in the module cache at ${src}" >&2; exit 1; }

rm -rf "${dest}"
mkdir -p "$(dirname "${dest}")"
cp -R "${src}" "${dest}"
chmod -R u+w "${dest}"
if command -v patch >/dev/null 2>&1; then
  patch -p1 -d "${dest}" --forward --silent < "${patch_file}"
else
  # git apply works like patch outside a repository; the ceiling keeps it from
  # finding one above <dest> (cli/ is inside this repository).
  dest_abs="$(cd "${dest}" && pwd)"
  (cd "${dest_abs}" && GIT_CEILING_DIRECTORIES="$(dirname "${dest_abs}")" git apply "${patch_file}")
fi
# The name the CLI refers to, so a build against this tree is a build with
# the patch; and the line the patch removes, so a half-applied tree fails here.
grep -q 'RealityAnnouncedVersion = ' "${dest}/common/tls/reality_ghostlane.go"
if grep -q 'curveID != utls.X25519MLKEM768' "${dest}/common/tls/reality_client.go"; then
  echo "${dest}: the Reality hello still strips X25519MLKEM768" >&2
  exit 1
fi
echo "sing-box v${SINGBOX_VERSION} (${sum}) + $(basename "${patch_file}") -> ${dest}"
