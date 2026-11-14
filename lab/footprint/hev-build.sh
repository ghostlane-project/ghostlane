#!/usr/bin/env bash
# hev-build.sh <bin-dir>
#
# hev-socks5-tunnel (https://github.com/heiher/hev-socks5-tunnel, MIT, C,
# lwIP) at the commit scripts/hev-pins.sh pins for the iPhone (HEV_COMMIT:
# 941c7581, tag 2.17.1 plus three commits; src/hev-config-const.h says
# 2.17.1), checked out the way .github/workflows/hev-tunnel.yml checks it out
# (a recursive clone, then the commit with its submodules), and built with the
# make target the desktop app uses on Linux (desktopApp/build.gradle.kts
# buildHevSocks5TunnelLinux: `make clean exec` in the source tree). The
# desktop app and Android run that target on a tree of their own,
# androidApp/src/main/jni/hev-socks5-tunnel (2.14.4 with the app's fixes);
# the lab builds the iPhone's pin, because the row stands for the iPhone's
# shape (HevTunnel.swift's configuration).
#
# The iPhone links the same tree as a static library (scripts/build-hev-ios.sh:
# `make static` with Apple's clang, -O3 from the Makefile either way) and runs
# it on a thread of the extension; the lab runs the executable, which is that
# library plus main(), as root, as its own process with a tun of its own.
#
# Writes <bin-dir>/hev-socks5-tunnel and <bin-dir>/hev.versions.txt.
set -euo pipefail

out="${1:?usage: hev-build.sh <bin-dir>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unset HEV_COMMIT HEV_BUILD HEV_TAG
# shellcheck source=../../scripts/hev-pins.sh
. "${here}/../../scripts/hev-pins.sh"

src="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/hev-socks5-tunnel"
rm -rf "${src}"
git clone --quiet --recursive https://github.com/heiher/hev-socks5-tunnel.git "${src}"
git -C "${src}" -c advice.detachedHead=false checkout --quiet --recurse-submodules "${HEV_COMMIT}"
git -C "${src}" submodule update --init --recursive --quiet
got="$(git -C "${src}" rev-parse HEAD)"
if [ "${got}" != "${HEV_COMMIT}" ]; then
  echo "checked out ${got}, but scripts/hev-pins.sh pins ${HEV_COMMIT}" >&2
  exit 1
fi

( cd "${src}" && make clean exec )

mkdir -p "${out}"
install -m 0755 "${src}/bin/hev-socks5-tunnel" "${out}/hev-socks5-tunnel"
{
  echo "hev-socks5-tunnel ${got} (scripts/hev-pins.sh HEV_COMMIT)"
  grep -E '^#define (MAJOR|MINOR|MICRO)_VERSION' "${src}/src/hev-config-const.h" | tr -s ' ' | tr '\n' ' '
  echo
  git -C "${src}" submodule status --recursive
  "${CC:-cc}" --version | head -n 1
} > "${out}/hev.versions.txt"
cat "${out}/hev.versions.txt"
ls -l "${out}/hev-socks5-tunnel"

# Whether this OS has MSG_MORE at all (and, on a Mac, the iPhone SDK), and the
# fallback hev's SOCKS5 client takes without it: lab.py udp-flow keeps the
# answer beside its figures. Never fails the build.
client_c="${src}/src/core/src/hev-socks5-client.c"
probe='#include <sys/socket.h>\n#ifdef MSG_MORE\nPK_MORE_DEFINED MSG_MORE\n#else\nPK_MORE_UNDEFINED\n#endif\n'
(
  set +e
  echo "MSG_MORE in <sys/socket.h>, $(uname -s) $(uname -m), $("${CC:-cc}" --version 2>&1 | head -n 1):"
  printf "${probe}" | "${CC:-cc}" -E -x c - 2>&1 | grep PK_MORE
  if [ "$(uname -s)" = Darwin ] && xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    echo "MSG_MORE in the iPhone SDK's <sys/socket.h>, iphoneos $(xcrun --sdk iphoneos --show-sdk-version), arm64:"
    printf "${probe}" | xcrun --sdk iphoneos clang -arch arm64 -E -x c - 2>&1 | grep PK_MORE
  fi
  echo "hev's fallback, ${client_c#"${src}/"}:"
  grep -n -A2 'ifndef MSG_MORE' "${client_c}"
) > "${out}/msg_more.txt" 2>&1 || true
cat "${out}/msg_more.txt"

# A second binary for lab.py udp-flow (V9) only: the same tree with MSG_MORE
# dropped from the two writes of the SOCKS5 handshake that pass it
# (hev-socks5-core f5c97b0, in the pin, sends the method offer and the login
# with MSG_MORE in the standard handshake too). That is how Apple's clang
# builds the iPhone's copy (Darwin has no MSG_MORE, so the file's fallback
# makes it 0), and what upstream's b43fa85 (2026-11-06) does outside pipeline
# mode. The pinned binary above is installed first and stays as built; a
# failure here costs only the V9 row.
# (perl, not sed -i: BSD sed on a Mac reads -i's next word as a suffix.)
if [ "$(grep -c 'MSG_WAITALL | MSG_MORE' "${client_c}")" = 2 ]; then
  perl -pi -e 's/MSG_WAITALL \| MSG_MORE/MSG_WAITALL/' "${client_c}"
  if ! ( cd "${src}" && make clean exec ); then
    echo "the MSG_MORE-free build failed; no hev-socks5-tunnel-nomore" >&2
  elif cmp -s "${src}/bin/hev-socks5-tunnel" "${out}/hev-socks5-tunnel"; then
    echo "on $(uname -s) the pin built without MSG_MORE is byte for byte the pin's own build: MSG_MORE is 0 here, so V9 is V1" \
      | tee "${out}/hev-nomore.identical.txt"
  else
    install -m 0755 "${src}/bin/hev-socks5-tunnel" "${out}/hev-socks5-tunnel-nomore"
    echo "hev-socks5-tunnel-nomore: ${got} with MSG_MORE dropped from the two handshake writes in ${client_c#"${src}/"}" \
      > "${out}/hev-nomore.versions.txt"
    ls -l "${out}/hev-socks5-tunnel-nomore"
  fi
else
  echo "${client_c} does not hold the two MSG_MORE writes; no hev-socks5-tunnel-nomore" >&2
fi
