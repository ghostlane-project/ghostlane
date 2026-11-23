#!/usr/bin/env bash
# Compiles TunArrangement.swift with its test and runs it: what the tunnel
# extension starts behind hev from the files the app wrote (the Rust core
# from core.json, the olcRTC client from olcrtc.json, a refusal with
# neither), and the SOCKS port and login it reads for hev. The root is passed
# so the test also reads the configs the shared Kotlin writes (every iPhone
# reference core.json of RustCoreConfigDumpTest).
# `xcrun` on a Mac; any swift.org toolchain's swiftc elsewhere
# (SWIFTC=/path/to/swiftc).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

swiftc="${SWIFTC:-}"
if [ -z "$swiftc" ]; then
  if command -v xcrun >/dev/null 2>&1; then swiftc="xcrun swiftc"; else swiftc="swiftc"; fi
fi

$swiftc \
  -parse-as-library \
  "$root/iosApp/PacketTunnel/TunArrangement.swift" \
  "$root/iosApp/Tests/TunArrangementTests.swift" \
  -o "$test_dir/tun-arrangement-tests"
"$test_dir/tun-arrangement-tests" "$root"
