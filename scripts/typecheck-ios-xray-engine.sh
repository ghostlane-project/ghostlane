#!/usr/bin/env bash
# Typechecks XrayEngine (iosApp/PacketTunnel/XrayEngine.swift) with a swift.org
# toolchain on Linux, in both language modes. The Cores bind (LibXrayInvoke,
# CoresSetenv) and os.Logger are shimmed; Foundation is checked for real.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
swiftc="${SWIFTC:-swiftc}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cat > "$work/shims.swift" <<'SHIM'
import Foundation

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
}
// The Cores bind.
public func LibXrayInvoke(_ request: String) -> String { "" }
public func CoresSetenv(_ key: String, _ value: String) throws {}
SHIM
sed -e 's/^import Cores$//' -e 's/^import os$//' "$root/iosApp/PacketTunnel/XrayEngine.swift" > "$work/XrayEngine.swift"
for mode in 5 6; do
  $swiftc -typecheck -swift-version "$mode" -parse-as-library "$work/shims.swift" "$work/XrayEngine.swift"
  echo "XrayEngine: typechecks in Swift $mode mode"
done
