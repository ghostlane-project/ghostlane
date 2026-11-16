#!/usr/bin/env bash
# The extension's memory relief policy (iosApp/PacketTunnel/MemoryRelief.swift)
# with its test: when MemoryWatch and MemoryPressure ask ghostlane-core to
# trim() and how they move its byte budget (set_byte_budget). Foundation only,
# so Xcode's swiftc or a swift.org toolchain on Linux runs it.
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
  "$root/iosApp/PacketTunnel/MemoryRelief.swift" \
  "$root/iosApp/Tests/MemoryReliefTests.swift" \
  -o "$test_dir/memory-relief-tests"
"$test_dir/memory-relief-tests"
