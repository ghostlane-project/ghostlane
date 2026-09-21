#!/usr/bin/env bash
# Compiles RoomList.swift with its test and runs it. `xcrun` on a Mac; any
# swift.org toolchain's swiftc elsewhere (SWIFTC=/path/to/swiftc).
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
  "$root/iosApp/PacketTunnel/RoomList.swift" \
  "$root/iosApp/Tests/RoomListTests.swift" \
  -o "$test_dir/room-list-tests"
"$test_dir/room-list-tests"
