#!/usr/bin/env bash
# Compiles HevTunnel.swift, TunArrangement.swift (LoopbackSocks) and
# HevTunnelConfigurationTests.swift and runs the test: the YAML hev-socks5-tunnel
# is started with, the keys hev reads, the task stack hev will really use, and
# lwIP's pool sizes (scripts/patches/hev-lwipopts-ios.patch) against the
# session count. The root is passed so the test reads that patch.
#
# HevTunnel.swift is compiled as it ships, with two imports stood in for:
# hev's C module (three functions the test never calls) and, off Darwin,
# os.Logger. `xcrun` on a Mac; any swift.org toolchain's swiftc elsewhere
# (SWIFTC=/path/to/swiftc).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

swiftc="${SWIFTC:-}"
if [ -z "$swiftc" ]; then
  if command -v xcrun >/dev/null 2>&1; then swiftc="xcrun swiftc"; else swiftc="swiftc"; fi
fi

cat > "$test_dir/HevShims.swift" <<'SHIM'
import Foundation

// hev-socks5-tunnel's C API as Swift imports it (size_t is Int). The test
// only builds the configuration; nothing here is called.
func hev_socks5_tunnel_main_from_str(_ config: UnsafePointer<UInt8>!, _ length: UInt32, _ tunFd: Int32) -> Int32 { 0 }
func hev_socks5_tunnel_quit() {}
func hev_socks5_tunnel_stats(_ a: UnsafeMutablePointer<Int>!, _ b: UnsafeMutablePointer<Int>!,
                             _ c: UnsafeMutablePointer<Int>!, _ d: UnsafeMutablePointer<Int>!) {}

#if !canImport(os)
// os.Logger, to the members HevTunnel.swift uses: `"\(x, privacy: .public)"`
// has to compile.
enum OSLogPrivacy { case `public`, `private` }
struct OSLogMessage: ExpressibleByStringLiteral, ExpressibleByStringInterpolation {
  init(stringLiteral value: String) {}
  init(stringInterpolation: StringInterpolation) {}
  struct StringInterpolation: StringInterpolationProtocol {
    init(literalCapacity: Int, interpolationCount: Int) {}
    mutating func appendLiteral(_ literal: String) {}
    mutating func appendInterpolation<T>(_ value: @autoclosure () -> T) {}
    mutating func appendInterpolation<T>(_ value: @autoclosure () -> T, privacy: OSLogPrivacy) {}
  }
}
struct Logger: Sendable {
  init(subsystem: String, category: String) {}
  func info(_ message: OSLogMessage) {}
  func error(_ message: OSLogMessage) {}
  func debug(_ message: OSLogMessage) {}
}
#endif
SHIM

# The module hev's framework provides does not exist here; os does on a Mac.
drop='/^import HevSocks5Tunnel$/d'
if [ "$(uname -s)" != Darwin ]; then drop="$drop;/^import os\$/d"; fi
sed -e "$drop" "$root/iosApp/PacketTunnel/HevTunnel.swift" > "$test_dir/HevTunnel.swift"
grep -q 'static func configuration(socks: Socks, mtu: Int) -> String' "$test_dir/HevTunnel.swift"

$swiftc \
  -parse-as-library \
  "$test_dir/HevShims.swift" \
  "$test_dir/HevTunnel.swift" \
  "$root/iosApp/PacketTunnel/TunArrangement.swift" \
  "$root/iosApp/Tests/HevTunnelConfigurationTests.swift" \
  -o "$test_dir/hev-config-tests"
"$test_dir/hev-config-tests" "$root"
