// What an earlier version of the helper left in root's own directory.
//
// Until the flip the helper ran a sing-box the app configured: it copied the
// program into `bin`, wrote the config (with the SOCKS port's login in it) to
// `tun.json`, and kept the rule-sets a bypass config named under `rules`. The
// helper now runs hev alone, from fields; none of those files is read again.
// A copy of sing-box that is left is a program a root-owned directory still
// holds, and a config that is left holds a login, so the helper takes them
// away when it starts, before it answers anybody. Idempotent: a second sweep
// finds nothing.
import Foundation

enum Sweep {
    /// The files the Go cores were copied as, whatever their name in the app.
    static let names: Set<String> = ["sing-box", "xray", "olcrtc"]

    /// Per-system copies of the olcRTC engine and the library the Go builder made.
    static let prefixes = ["olcrtc-", "libolcrtc"]

    /// Files of the state directory itself, and the directory of rule-sets.
    static let files = ["tun.json"]
    static let directories = ["rules"]

    /// Whether [name] in `bin` is a Go core of an earlier build: not hev, not the step.
    static func isStale(_ name: String) -> Bool {
        names.contains(name) || prefixes.contains { name.hasPrefix($0) }
    }

    /// Removes the old Go copies and the old config from [directory], and
    /// returns what it removed, sorted, as paths below [directory]. A
    /// directory that is not there is a clean one.
    @discardableResult
    static func run(in directory: URL = TunnelChild.stateDir) -> [String] {
        let manager = FileManager.default
        var removed: [String] = []
        func take(_ relative: String) {
            let url = directory.appendingPathComponent(relative)
            // lstat, so that a link is taken as the link it is and never followed.
            var info = stat()
            guard lstat(url.path, &info) == 0 else { return }
            if (try? manager.removeItem(at: url)) != nil { removed.append(relative) }
        }
        let bin = directory.appendingPathComponent("bin", isDirectory: true)
        if let entries = try? manager.contentsOfDirectory(atPath: bin.path) {
            for name in entries where isStale(name) { take("bin/\(name)") }
        }
        for name in files { take(name) }
        for name in directories { take(name) }
        return removed.sorted()
    }
}
