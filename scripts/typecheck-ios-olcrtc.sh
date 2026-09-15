#!/usr/bin/env bash
# Typechecks the two olcRTC bridges with a swift.org toolchain on Linux.
# Cores and Darwin symbols are shimmed (as plain protocols: the Linux toolchain
# has no Objective-C interop); everything else is checked for real.
# The shim mirrors the gobind header of the engine's proofkit branch
# (MobileRuntime + MobileNew + MobileSetLogWriter); regenerate it with
# `gobind -lang=objc ./mobile` when the engine API changes.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
swiftc="${SWIFTC:-swiftc}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cat > "$work/shims.swift" <<'SHIM'
import Foundation

public struct Logger: Sendable {
  public init(subsystem: String, category: String) {}
  public func info(_ m: LogMessage) {}
  public func error(_ m: LogMessage) {}
}
public struct LogMessage: ExpressibleByStringInterpolation, ExpressibleByStringLiteral {
  public init(stringLiteral value: String) {}
  public init(stringInterpolation: Interp) {}
  public struct Interp: StringInterpolationProtocol {
    public init(literalCapacity: Int, interpolationCount: Int) {}
    public mutating func appendLiteral(_ literal: String) {}
    public mutating func appendInterpolation<T>(_ value: T, privacy: Privacy = .private) {}
    public mutating func appendInterpolation<T>(_ value: T) {}
  }
  public enum Privacy { case `public`, `private` }
}
public protocol MobileSocketProtectorProtocol { func protect(_ fd: Int) -> Bool }
public protocol MobileLogWriterProtocol { func writeLog(_ msg: String?) }
public class MobileRuntime: NSObject {
  public func setDebug(_ e: Bool) {}
  public func setProtector(_ p: MobileSocketProtectorProtocol?) {}
  public func setTransport(_ t: String?) throws {}
  public func setDNS(_ d: String?) throws {}
  public func setDirectRules(_ t: String?) throws {}
  public func setVP8Options(_ f: Int, batchSize: Int) throws {}
  public func setSocksListenHost(_ h: String?) throws {}
  public func setProvider(_ p: String?) throws {}
  public func setRoom(_ r: String?) throws {}
  public func setDeviceID(_ d: String?) {}
  public func setKey(_ k: String?) throws {}
  public func setSocksPort(_ p: Int) throws {}
  public func setSocksCredentials(_ u: String?, password: String?) throws {}
  public func setUDP(_ e: Bool) {}
  public func isRunning() -> Bool { false }
  public func start() throws {}
  public func waitReady(_ ms: Int) throws {}
  public func stop(_ ms: Int) throws {}
  public func check(_ p: String?, transportName: String?, roomID: String?, deviceID: String?, keyHex: String?,
                    socksPort: Int, timeoutMillis: Int, vp8FPS: Int, vp8BatchSize: Int,
                    ret0_: UnsafeMutablePointer<Int64>?) throws {}
  public func ping(_ p: String?, transportName: String?, roomID: String?, deviceID: String?, keyHex: String?,
                   socksPort: Int, timeoutMillis: Int, pingURL: String?, vp8FPS: Int, vp8BatchSize: Int,
                   ret0_: UnsafeMutablePointer<Int64>?) throws {}
}
public func MobileNew() -> MobileRuntime? { MobileRuntime() }
public func MobileSetLogWriter(_ w: MobileLogWriterProtocol?) {}
public enum LibboxPlatform { public static func pinToPhysicalInterface(_ fd: Int32) -> Bool { true } }
// Darwin-only Foundation API the extension uses for its App Group log file.
extension FileManager {
  public func containerURL(forSecurityApplicationGroupIdentifier id: String) -> URL? { nil }
}
SHIM
for mode in 5 6; do
  # Strip the framework imports: the shim provides those names.
  sed -e '/^import Cores$/d' -e '/^import os$/d' "$root/iosApp/PacketTunnel/OlcrtcEngine.swift" > "$work/OlcrtcEngine.swift"
  $swiftc -typecheck -swift-version "$mode" -parse-as-library "$work/shims.swift" "$work/OlcrtcEngine.swift"
  echo "OlcrtcEngine.swift: typechecks in Swift $mode mode"
done
