#!/usr/bin/env bash
# Typechecks the tunnel extension's two trace writers, MemoryWatch and
# NetworkDiagnostics (iosApp/PacketTunnel/), with a swift.org toolchain on
# Linux, in both language modes. Cores (the Go bind), Mach and the App Group
# container lookup are shimmed; Foundation, Dispatch and the isolation rules
# are checked for real. Both files are taken whole.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
swiftc="${SWIFTC:-swiftc}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cat > "$work/shims.swift" <<'SHIM'
import Foundation
import Dispatch

// os.Logger: `"\(x, privacy: .public)"` has to compile.
public enum OSLogPrivacy { case `public`, `private` }
public struct OSLogMessage: ExpressibleByStringLiteral, ExpressibleByStringInterpolation {
  public init(stringLiteral value: String) {}
  public init(stringInterpolation: StringInterpolation) {}
  public struct StringInterpolation: StringInterpolationProtocol {
    public init(literalCapacity: Int, interpolationCount: Int) {}
    public mutating func appendLiteral(_ literal: String) {}
    public mutating func appendInterpolation<T>(_ value: @autoclosure () -> T) {}
    public mutating func appendInterpolation<T>(_ value: @autoclosure () -> T, privacy: OSLogPrivacy) {}
  }
}
public struct Logger: Sendable {
  public init(subsystem: String, category: String) {}
  public func info(_ message: OSLogMessage) {}
  public func error(_ message: OSLogMessage) {}
  public func debug(_ message: OSLogMessage) {}
}
// Cores, the Go bind.
public func MobileMemoryStats() -> String { "" }
public func MobileFreeOSMemory() {}
public func os_proc_available_memory() -> Int { 0 }
// Mach task info.
public typealias mach_msg_type_number_t = UInt32
public typealias natural_t = UInt32
public typealias integer_t = Int32
public typealias task_flavor_t = Int32
public typealias kern_return_t = Int32
public struct task_vm_info_data_t { public var phys_footprint: UInt64 = 0; public init() {} }
public let TASK_VM_INFO: Int32 = 22
public let KERN_SUCCESS: kern_return_t = 0
public let mach_task_self_: UInt32 = 0
public func task_info(
  _ task: UInt32, _ flavor: task_flavor_t,
  _ info: UnsafeMutablePointer<integer_t>, _ count: UnsafeMutablePointer<mach_msg_type_number_t>
) -> kern_return_t { KERN_SUCCESS }
// Darwin-only Foundation API for the App Group container.
extension FileManager {
  public func containerURL(forSecurityApplicationGroupIdentifier id: String) -> URL? { nil }
}
SHIM
for f in MemoryWatch NetworkDiagnostics; do
  # The real imports name modules that do not exist here; the shims stand in.
  sed -e 's/^import Cores$//' -e 's/^import Darwin$//' -e 's/^import os$//' \
    "$root/iosApp/PacketTunnel/$f.swift" > "$work/$f.swift"
done
for mode in 5 6; do
  $swiftc -typecheck -swift-version "$mode" -parse-as-library "$work/shims.swift" "$work/MemoryWatch.swift" "$work/NetworkDiagnostics.swift"
  echo "MemoryWatch + NetworkDiagnostics: typecheck in Swift $mode mode"
done
