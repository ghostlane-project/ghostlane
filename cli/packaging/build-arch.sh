#!/usr/bin/env bash
# build-arch.sh <amd64|arm64|arm7>: builds ghostlane for one architecture as a
# static musl program and stages it under dist/linux-<arch>/ with what it
# starts beside it:
#
#   ghostlane          this crate, built here
#   ghostlane-engine   the Core build's program at the pin
#   ghostlane-hev      hev-socks5-tunnel, the tun's tun2socks
#
# The engine and hev are not built here: the Core build makes them
# (core-build.yml's Linux and hev-linux jobs, scripts/build-ghostlane-core-host.sh
# and scripts/build-hev-linux.sh) and the release job restores or makes them
# first. CORE_OUT is where the first writes (bin/<target>/ghostlane-engine)
# and HEV_OUT where the second does (<target>/hev-socks5-tunnel); the defaults
# are out/linux and out/hev-linux of the repository.
#
# The C of ring (under rustls) is built with the cross compiler of
# scripts/build-hev-linux.sh --toolchain, as the core's own build does, unless
# CC_<target> is already set. VERSION and CRYPT_KEY_V1 reach the program as
# GHOSTLANE_VERSION and GHOSTLANE_CRYPT_KEY_V1 at build time.
set -euo pipefail

arch="${1:?usage: build-arch.sh <amd64|arm64|arm7>}"
cli="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(cd "$cli/.." && pwd)"
case "$arch" in
  amd64) target=x86_64-unknown-linux-musl ;;
  arm64) target=aarch64-unknown-linux-musl ;;
  arm7) target=armv7-unknown-linux-musleabihf ;;
  *) echo "build-arch.sh: no architecture $arch" >&2; exit 2 ;;
esac
core_out="${CORE_OUT:-$root/out/linux}"
hev_out="${HEV_OUT:-$root/out/hev-linux}"
engine="$core_out/bin/$target/ghostlane-engine"
hev="$hev_out/$target/hev-socks5-tunnel"
[ -s "$engine" ] || { echo "build-arch.sh: no ghostlane-engine for $target at $engine (the Core build makes it)" >&2; exit 1; }
[ -s "$hev" ] || { echo "build-arch.sh: no hev-socks5-tunnel for $target at $hev (scripts/build-hev-linux.sh makes it)" >&2; exit 1; }

low="${target//-/_}"
cc_var="CC_${low}"
if [ -z "${!cc_var:-}" ]; then
  env_file="$(mktemp)"
  bash "$root/scripts/build-hev-linux.sh" --toolchain \
    "${GHOSTLANE_MUSL_TOOLCHAIN_DIR:-${TMPDIR:-/tmp}/ghostlane-musl-toolchain}" "$target" > "$env_file"
  while IFS= read -r line; do
    [ -z "$line" ] || export "${line?}"
  done < "$env_file"
  rm -f "$env_file"
fi

export GHOSTLANE_VERSION="${VERSION:-dev}"
export GHOSTLANE_CRYPT_KEY_V1="${CRYPT_KEY_V1:-}"
( cd "$cli" && rustup target add "$target" && cargo build --locked --release --target "$target" )

out="$cli/dist/linux-$arch"
mkdir -p "$out"
install -m 0755 "$cli/target/$target/release/ghostlane" "$out/ghostlane"
install -m 0755 "$engine" "$out/ghostlane-engine"
install -m 0755 "$hev" "$out/ghostlane-hev"

# the pin the engine says is the pin this build was asked for, where it can be run here
if [ -n "${GHOSTLANE_CORE_COMMIT:-}" ] && [ "$arch" = amd64 ] && [ "$(uname -m)" = x86_64 ]; then
  said="$("$out/ghostlane-engine" version | sed -n 's/.*"pin":"\([0-9a-f]*\)".*/\1/p' | head -n 1)"
  [ "$said" = "$GHOSTLANE_CORE_COMMIT" ] || { echo "build-arch.sh: the engine says pin '$said', not $GHOSTLANE_CORE_COMMIT" >&2; exit 1; }
fi
echo "== $arch ($target): $(ls -l "$out" | tail -n +2 | awk '{print $5, $9}' | tr '\n' ' ')"
