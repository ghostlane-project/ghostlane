#!/usr/bin/env bash
# engine-src.sh <dest>
#
# Checks the olcRTC engine out at the commit scripts/cores-pins.sh pins
# (OLCRTC_VERSION's 12-hex tail), from the fork release.yml checks out
# (ghostlane-project/olcrtc), and refuses a checkout whose commit or commit
# time does not match the pseudo-version, as scripts/olcrtc-pin.sh does for a
# release. The Android job hands <dest> to Gradle as OLCRTC_REPO, as
# release.yml's build-android does; the desktop job builds the engine's
# binaries and its loopback test from it.
#
# bash 3.2 safe (macOS runners).
set -euo pipefail

dest="${1:?usage: engine-src.sh <dest>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unset GO_VERSION SINGBOX_VERSION SINGBOX_MODULE_SUM LIBXRAY_VERSION OLCRTC_VERSION CORES_BUILD CORES_TAG
# shellcheck source=../../scripts/cores-pins.sh
. "${here}/../../scripts/cores-pins.sh"

rev="${OLCRTC_VERSION##*-}"
stamp="${OLCRTC_VERSION#v0.0.0-}"
stamp="${stamp%%-*}"

if [ -e "${dest}" ]; then
  echo "${dest} exists; engine-src.sh wants a fresh path" >&2
  exit 1
fi
git clone --quiet --filter=blob:none --no-checkout https://github.com/ghostlane-project/olcrtc.git "${dest}"
git -C "${dest}" -c advice.detachedHead=false checkout --quiet --detach "${rev}"

full="$(git -C "${dest}" rev-parse HEAD)"
case "${full}" in
  "${rev}"*) ;;
  *)
    echo "checked out ${full}, but the pin names ${rev}" >&2
    exit 1
    ;;
esac
when="$(TZ=UTC git -C "${dest}" show -s --format=%cd --date=format-local:%Y%m%d%H%M%S HEAD)"
if [ "${when}" != "${stamp}" ]; then
  echo "commit ${full} is from ${when} UTC, but the pseudo-version says ${stamp}" >&2
  exit 1
fi
echo "olcRTC engine ${full} (${OLCRTC_VERSION}) in ${dest}"
