#!/usr/bin/env bash
# Compiles RoomList.swift and RoomMemoryRecord.swift with their test and runs
# it, then DeviceHeaders.swift with its own: what the room keeper reads of a
# list, and how its refresh names the device and reads a host's refusal.
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
  "$root/iosApp/PacketTunnel/RoomList.swift" \
  "$root/iosApp/PacketTunnel/RoomMemoryRecord.swift" \
  "$root/iosApp/Tests/RoomListTests.swift" \
  -o "$test_dir/room-list-tests"
"$test_dir/room-list-tests"

# The root is read by the test: it holds the Swift copy to the Kotlin it copies.
$swiftc \
  -parse-as-library \
  "$root/iosApp/PacketTunnel/DeviceHeaders.swift" \
  "$root/iosApp/Tests/DeviceHeadersTests.swift" \
  -o "$test_dir/device-headers-tests"
"$test_dir/device-headers-tests" "$root"
