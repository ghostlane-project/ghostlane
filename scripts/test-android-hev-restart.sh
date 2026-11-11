#!/usr/bin/env bash
# Builds the Android tun's hev-socks5-tunnel for this machine and runs it
# through what a stop and a restart in one process have to survive
# (scripts/android-hev-restart/harness.c says what each case is). A Linux host
# with cc and make; no device, no NDK, no root, about half a minute.
#
#   scripts/test-android-hev-restart.sh            every case
#   scripts/test-android-hev-restart.sh restart    the cases named
#
# The vendored tree builds into itself (bin/, build/), so a copy of it is built
# in a temporary directory and nothing is written to the checkout.
set -euo pipefail

# hev reads a Linux tun as bare packets, which is what the socketpair gives it.
if [ "$(uname -s)" != Linux ]; then
  echo "scripts/test-android-hev-restart.sh runs on Linux only"
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jni="$root/androidApp/src/main/jni"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cases=("$@")
if [ "${#cases[@]}" -eq 0 ]; then
  cases=(restart reuse first leftover many-runs carrying fragment early-exit fresh-session dead-tun second-start)
fi

# What the wrapper answers a start beside a run with is a number the service
# reads by name. Two files say it, and they have to say the same one.
native_code="$(sed -n 's/^#define OLCBOX_TUN2SOCKS_RUN_IN_PROGRESS (\(-[0-9][0-9]*\))$/\1/p' "$jni/olcbox_tun2socks_run.h")"
kotlin_code="$(sed -n 's/^ *const val RUN_IN_PROGRESS = \(-[0-9][0-9]*\)$/\1/p' \
  "$root/sharedUI/src/jvmAndroidMain/kotlin/org/olcbox/app/vpn/NativeTunnelRun.kt")"
if [ -z "$native_code" ] || [ "$native_code" != "$kotlin_code" ]; then
  echo "A run in progress is '$native_code' in olcbox_tun2socks_run.h and '$kotlin_code' in NativeTunnelRun.kt"
  exit 1
fi

cp -R "$jni/hev-socks5-tunnel" "$work/hev"
# The copy takes with it whatever a build in the checkout left behind: bin/ and
# build/, which the vendored .gitignore files keep out of git status. In a copy
# an object is no older than its source, so make would compile nothing and the
# cases would run the objects of that earlier build, whatever the sources say
# now. The copy is cleaned first.
# REV_ID: the copy is no git checkout, and the Makefile would ask git for one.
if ! { make -C "$work/hev" clean REV_ID=test && make -C "$work/hev" -j2 static REV_ID=test; } > "$work/build.log" 2>&1; then
  cat "$work/build.log"
  echo "hev-socks5-tunnel did not build"
  exit 1
fi

hev="$work/hev"
# Warnings are shown and not fatal: the runner's compiler is not the one this
# was written with, and the harness is a test, not what ships.
"${CC:-cc}" -O1 -g -Wall -Wextra -pthread \
  -I"$jni" \
  -I"$hev/src" \
  -I"$hev/third-part/lwip/src/include" \
  -I"$hev/third-part/lwip/src/ports/include" \
  -o "$work/harness" \
  "$root/scripts/android-hev-restart/harness.c" \
  "$jni/olcbox_tun2socks_run.c" \
  "$hev/bin/libhev-socks5-tunnel.a" \
  "$hev/third-part/lwip/bin/liblwip.a" \
  "$hev/third-part/hev-task-system/bin/libhev-task-system.a" \
  "$hev/third-part/yaml/bin/libyaml.a"

failed=()
for name in "${cases[@]}"; do
  printf '== %s\n' "$name"
  # hev logs to stderr; what a case found is on standard output.
  if TMPDIR="$work" "$work/harness" "$name" 2>&1; then
    :
  else
    printf 'exit status %d\n' "$?"
    failed+=("$name")
  fi
done

if [ "${#failed[@]}" -gt 0 ]; then
  printf '\nFAILED: %s\n' "${failed[*]}"
  exit 1
fi
printf '\nPassed: %s\n' "${cases[*]}"
