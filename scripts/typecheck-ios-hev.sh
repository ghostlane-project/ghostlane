#!/usr/bin/env bash
# Typechecks the hev-socks5-tunnel integration — HevTunnel.swift,
# TunDescriptor.swift and PacketTunnelProvider.swift — with a swift.org
# toolchain on Linux, in both language modes. NetworkExtension, Network,
# Cores (the Go bind), hev's C API and the extension's own Darwin-heavy
# helpers (LibboxPlatform, the two engine bridges, the traces) are shimmed to
# the members these files use; everything else — Foundation, Dispatch,
# control flow, the isolation rules — is checked for real.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
swiftc="${SWIFTC:-swiftc}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cat > "$work/shims.swift" <<'SHIM'
import Foundation
import Dispatch
#if canImport(Glibc)
import Glibc
#endif

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

// NetworkExtension.
open class NEPacketTunnelFlow: NSObject {
  public func value(forKeyPath keyPath: String) -> Any? { nil }
}
open class NETunnelNetworkSettings: NSObject {}
open class NEPacketTunnelNetworkSettings: NETunnelNetworkSettings {}
public struct NEProviderStopReason: RawRepresentable, Sendable {
  public let rawValue: Int
  public init(rawValue: Int) { self.rawValue = rawValue }
}
open class NEPacketTunnelProvider: NSObject {
  public override init() {}
  public var packetFlow: NEPacketTunnelFlow { NEPacketTunnelFlow() }
  public func setTunnelNetworkSettings(_ settings: NETunnelNetworkSettings?, completionHandler: ((Error?) -> Void)? = nil) {}
  public func cancelTunnelWithError(_ error: Error?) {}
  open func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {}
  open func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {}
  open func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {}
}

// Network: the path monitor.
public final class NWInterface: Sendable {
  public enum InterfaceType: Sendable { case wifi, cellular, other }
  public let name = ""
  public let type = InterfaceType.other
}
public final class NWPath: Sendable {
  public enum Status: Sendable { case satisfied, unsatisfied }
  public let status = Status.satisfied
  public let availableInterfaces: [NWInterface] = []
  public let supportsIPv4 = true
  public let supportsIPv6 = true
  public func usesInterfaceType(_ type: NWInterface.InterfaceType) -> Bool { false }
}
public final class NWPathMonitor {
  public init() {}
  public var pathUpdateHandler: (@Sendable (NWPath) -> Void)?
  public func start(queue: DispatchQueue) {}
  public func cancel() {}
}

// Cores, the Go bind.
public final class LibboxSetupOptions: NSObject {
  public var basePath = ""; public var workingPath = ""; public var tempPath = ""
  public var fixAndroidStack = false; public var commandServerListenPort: Int32 = 0
  public var commandServerSecret = ""; public var logMaxLines: Int32 = 0; public var debug = false
}
public func LibboxSetup(_ o: LibboxSetupOptions?, _ e: UnsafeMutablePointer<NSError?>?) {}
public func LibboxSetMemoryLimit(_ enabled: Bool) {}
public func LibboxRedirectStderr(_ path: String?, _ e: UnsafeMutablePointer<NSError?>?) {}
public final class LibboxOverrideOptions: NSObject {}
public final class LibboxCommandServer: NSObject {
  public func startOrReloadService(_ config: String?, options: LibboxOverrideOptions?) throws {}
  public func resetNetwork() {}
  public func closeService() throws {}
  public func close() {}
}
public final class LibboxSystemProxyStatus: NSObject { public var available = false; public var enabled = false }
public protocol LibboxCommandServerHandlerProtocol: NSObjectProtocol {
  func serviceStop() throws
  func serviceReload() throws
  func getSystemProxyStatus() throws -> LibboxSystemProxyStatus
  func setSystemProxyEnabled(_ enabled: Bool) throws
  func writeDebugMessage(_ message: String?)
}
public func LibboxNewCommandServer(_ h: LibboxCommandServerHandlerProtocol?, _ p: LibboxPlatform?, _ e: UnsafeMutablePointer<NSError?>?) -> LibboxCommandServer? { nil }
public func MobileSetMemoryLimit(_ bytes: Int64) {}
public func MobileMemoryLimit() -> Int64 { 0 }

// hev-socks5-tunnel's C API, as Swift imports it (size_t is Int).
public func hev_socks5_tunnel_main_from_str(_ config: UnsafePointer<UInt8>!, _ length: UInt32, _ tunFd: Int32) -> Int32 { 0 }
public func hev_socks5_tunnel_quit() {}
public func hev_socks5_tunnel_stats(_ a: UnsafeMutablePointer<Int>!, _ b: UnsafeMutablePointer<Int>!, _ c: UnsafeMutablePointer<Int>!, _ d: UnsafeMutablePointer<Int>!) {}

// The extension's own Darwin-heavy helpers, to the members the provider uses.
public final class LibboxPlatform: NSObject {
  public enum Tun {
    public static let address = "172.19.0.1"; public static let mask = "255.255.255.252"; public static let mtu = 9000
    public static let dns = ["172.19.0.2"]; public static let dnsThroughSocks = ["208.67.222.222", "208.67.220.220"]
  }
  public init(provider: NEPacketTunnelProvider) {}
  public static func tunnelSettings(dns: [String] = Tun.dns) -> NEPacketTunnelNetworkSettings { NEPacketTunnelNetworkSettings() }
  public static func invalidatePinCache() {}
  public static func tracePhysicalInterfaces(_ stage: String) {}
}
public enum XrayEngine { public static func start(configJSON: String) throws {}; public static func stop() {} }
public enum OlcrtcEngine {
  public struct Parameters: Decodable {
    public let socksPort: Int; public let socksUser: String; public let socksPass: String
  }
  public static func start(_ p: Parameters, resolvers: [String] = []) throws {}
  public static func stop() {}
}
public enum DirectResolver { public static func substitute(in config: String, resolvers: [String]) -> String { config } }
public enum ResolverSnapshot { public static func servers() -> [String] { [] } }
public enum MemoryWatch { public static func start(container: URL) {}; public static func stop() {}; public static func mark(_ s: String) {} }
public enum MemoryPressure { public static func start() {}; public static func stop() {} }
public enum NetworkDiagnostics { public static func reset() {}; public static func record(_ e: String) {} }
extension FileManager {
  public func containerURL(forSecurityApplicationGroupIdentifier id: String) -> URL? { nil }
}
#if !canImport(Darwin)
public let IFNAMSIZ_shim: Int32 = 16
#endif
SHIM
for f in HevTunnel TunDescriptor PacketTunnelProvider; do
  # The real imports name modules that do not exist here; the shims stand in.
  sed -e 's/^import Cores$//' -e 's/^import Darwin$/import Glibc/' -e 's/^import os$//' \
      -e 's/^import Network$//' -e 's/^import NetworkExtension$//' -e 's/^import HevSocks5Tunnel$//' \
    "$root/iosApp/PacketTunnel/$f.swift" > "$work/$f.swift"
  # On Darwin these files get Foundation through NetworkExtension; here it is explicit.
  sed -i "1i import Foundation" "$work/$f.swift"
done
# The extension target builds in Swift 5 mode (SWIFT_VERSION = 5.0), and the
# provider's path-monitor closure predates this work and is a Swift 6 error
# (a captured var mutated in a @Sendable closure). So the provider is checked
# in its own mode, and the two new files in both.
$swiftc -typecheck -swift-version 5 -parse-as-library \
  "$work/shims.swift" "$work/HevTunnel.swift" "$work/TunDescriptor.swift" "$work/PacketTunnelProvider.swift"
echo "HevTunnel + TunDescriptor + PacketTunnelProvider: typecheck in Swift 5 mode"
$swiftc -typecheck -swift-version 6 -parse-as-library \
  "$work/shims.swift" "$work/HevTunnel.swift" "$work/TunDescriptor.swift"
echo "HevTunnel + TunDescriptor: typecheck in Swift 6 mode"
