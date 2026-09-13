#!/usr/bin/env bash
# Typechecks AppMemoryWatch (iosApp/iosApp/OlcboxIosApp.swift) with a swift.org
# toolchain on Linux, in both language modes the project builds with. UIKit,
# Mach and the Darwin-only Dispatch memory-pressure source are shimmed; the
# rest - Foundation, Dispatch, Swift 6 isolation rules - is checked for real.
# The enum is extracted by its doc comment and the type that follows it, so
# keep both markers if you move it.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
swiftc="${SWIFTC:-swiftc}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cat > "$work/shims.swift" <<'SHIM'
import Foundation
import Dispatch

public struct UIBackgroundTaskIdentifier: Equatable, Sendable {
  public static let invalid = UIBackgroundTaskIdentifier()
}
@MainActor public final class UIApplication {
  public static let shared = UIApplication()
  nonisolated public static let didEnterBackgroundNotification = Notification.Name("didEnterBackground")
  nonisolated public static let willEnterForegroundNotification = Notification.Name("willEnterForeground")
  nonisolated public static let didReceiveMemoryWarningNotification = Notification.Name("didReceiveMemoryWarning")
  public func beginBackgroundTask(
    withName: String?, expirationHandler: (@MainActor @Sendable () -> Void)? = nil
  ) -> UIBackgroundTaskIdentifier { .invalid }
  public func endBackgroundTask(_ id: UIBackgroundTaskIdentifier) {}
}
// Darwin-only Dispatch: the memory pressure source.
extension DispatchSource {
  public struct MemoryPressureEvent: OptionSet, Sendable {
    public let rawValue: UInt
    public init(rawValue: UInt) { self.rawValue = rawValue }
    public static let normal = MemoryPressureEvent(rawValue: 1)
    public static let warning = MemoryPressureEvent(rawValue: 2)
    public static let critical = MemoryPressureEvent(rawValue: 4)
  }
  public static func makeMemoryPressureSource(
    eventMask: MemoryPressureEvent, queue: DispatchQueue?
  ) -> DispatchSourceMemoryPressure { fatalError("shim") }
}
public protocol DispatchSourceMemoryPressure: DispatchSourceProtocol {
  var data: DispatchSource.MemoryPressureEvent { get }
}
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
{
  printf 'import Foundation\nimport Dispatch\n\n'
  sed -n '/^\/\/\/ Records what the \*app\* holds/,/^enum TunnelCounters {/p' "$root/iosApp/iosApp/OlcboxIosApp.swift" | sed '$d'
} > "$work/AppMemoryWatch.swift"
grep -q '^enum AppMemoryWatch' "$work/AppMemoryWatch.swift" || { echo "AppMemoryWatch not found between its markers"; exit 1; }
for mode in 5 6; do
  $swiftc -typecheck -swift-version "$mode" -parse-as-library "$work/shims.swift" "$work/AppMemoryWatch.swift"
  echo "AppMemoryWatch: typechecks in Swift $mode mode"
done
