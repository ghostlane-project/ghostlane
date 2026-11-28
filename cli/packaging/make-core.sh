#!/usr/bin/env bash
# make-core.sh <rust target>...: makes what the CLI's packages carry and the
# caches did not hold, for each target given: the Core build's ghostlane-engine
# (scripts/build-ghostlane-core-host.sh, under $CORE_OUT, default out/linux) and
# hev-socks5-tunnel (scripts/build-hev-linux.sh, under $HEV_OUT, default
# out/hev-linux). What is there already is kept.
#
# The jobs restore core-build.yml's caches first; this runs after them, so that
# a release or a pull request on a commit the Core build has not seen still
# works. With GITHUB_OUTPUT set it writes engine_built=true|false and
# hev_built=true|false, which tell a job whether there is something to keep.
#
# Needs what those two scripts need: cmake, clang, lld, llvm, libclang-dev and
# docker; GHOSTLANE_CORE_SRC and GHOSTLANE_MUSL_TOOLCHAIN_DIR name where the
# core's source and the cross compiler go (both default under $TMPDIR).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
core_out="${CORE_OUT:-$root/out/linux}"
hev_out="${HEV_OUT:-$root/out/hev-linux}"
hev_source="$root/androidApp/src/main/jni/hev-socks5-tunnel"

engines=()
hevs=()
for target in "$@"; do
  [ -s "$core_out/bin/$target/ghostlane-engine" ] || engines+=("$target")
  [ -s "$hev_out/$target/hev-socks5-tunnel" ] || hevs+=("$target")
done

engine_built=false
hev_built=false
if [ "${#engines[@]}" -gt 0 ]; then
  echo "== ghostlane-engine for ${engines[*]}: no cache held it"
  bash "$root/scripts/build-ghostlane-core-host.sh" "$core_out" "${engines[@]}"
  engine_built=true
fi
if [ "${#hevs[@]}" -gt 0 ]; then
  echo "== hev-socks5-tunnel for ${hevs[*]}: no cache held it"
  bash "$root/scripts/build-hev-linux.sh" "$hev_source" "$hev_out" "${hevs[@]}"
  hev_built=true
fi
for target in "$@"; do
  [ -s "$core_out/bin/$target/ghostlane-engine" ] || { echo "make-core.sh: no ghostlane-engine for $target" >&2; exit 1; }
  [ -s "$hev_out/$target/hev-socks5-tunnel" ] || { echo "make-core.sh: no hev-socks5-tunnel for $target" >&2; exit 1; }
done
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "engine_built=$engine_built" >> "$GITHUB_OUTPUT"
  echo "hev_built=$hev_built" >> "$GITHUB_OUTPUT"
fi
