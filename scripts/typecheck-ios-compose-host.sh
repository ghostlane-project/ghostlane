#!/usr/bin/env bash
# Typechecks ComposeSceneHost (iosApp/iosApp/OlcboxIosApp.swift) with a
# swift.org toolchain on Linux, in both language modes. UIKit is shimmed as
# plain classes with the members the host uses; Swift 6 isolation rules and
# everything Foundation are checked for real. Extracted by its doc comment and
# the type that follows it, so keep both markers if you move it.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
swiftc="${SWIFTC:-swiftc}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cat > "$work/shims.swift" <<'SHIM'
import Foundation

public struct CGRect: Sendable { public init() {} }
public struct UIViewAutoresizing: OptionSet, Sendable {
  public let rawValue: UInt
  public init(rawValue: UInt) { self.rawValue = rawValue }
  public static let flexibleWidth = UIViewAutoresizing(rawValue: 2)
  public static let flexibleHeight = UIViewAutoresizing(rawValue: 16)
}
public final class UIColor: @unchecked Sendable { public static let black = UIColor() }
@MainActor open class UIView {
  public init() {}
  public var frame = CGRect()
  public var bounds: CGRect { CGRect() }
  public var autoresizingMask: UIViewAutoresizing = []
  public var backgroundColor: UIColor?
  public func addSubview(_ v: UIView) {}
  public func insertSubview(_ v: UIView, at: Int) {}
  public func removeFromSuperview() {}
  public func snapshotView(afterScreenUpdates: Bool) -> UIView? { UIView() }
}
public final class NSCoder {}
@MainActor open class UIViewController {
  public init(nibName: String?, bundle: Bundle?) {}
  public required init?(coder: NSCoder) { nil }
  public var view: UIView! = UIView()
  public private(set) var parent: UIViewController?
  open func viewDidLoad() {}
  public func addChild(_ c: UIViewController) { c.parent = self }
  public func removeFromParent() { parent = nil }
  public func didMove(toParent: UIViewController?) {}
  public func willMove(toParent: UIViewController?) {}
}
@MainActor public final class UIApplication {
  nonisolated public static let didEnterBackgroundNotification = Notification.Name("didEnterBackground")
  nonisolated public static let willEnterForegroundNotification = Notification.Name("willEnterForeground")
}
SHIM
{
  printf 'import Foundation\nimport Dispatch\n\n'
  sed -n '/^\/\/\/ Hosts the Compose view controller and lets go of it while the app is in/,/^@MainActor$/p' "$root/iosApp/iosApp/OlcboxIosApp.swift" | sed '$d'
} > "$work/ComposeSceneHost.swift"
grep -q '^final class ComposeSceneHost' "$work/ComposeSceneHost.swift" || { echo "ComposeSceneHost not found between its markers"; exit 1; }
for mode in 5 6; do
  $swiftc -typecheck -swift-version "$mode" -parse-as-library "$work/shims.swift" "$work/ComposeSceneHost.swift"
  echo "ComposeSceneHost: typechecks in Swift $mode mode"
done
