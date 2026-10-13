// The sing-box the daemon runs, and where it is allowed to run it from.
//
// Never from a path the client sends, and never from a directory a non-root user
// can write. /Applications is writable by any admin, so a binary verified there
// and executed there can be swapped between the two; the bundled core is
// therefore copied into a root-owned directory, verified where it then lies,
// and executed from there. Without that, this daemon is a way for any admin to
// run their own code as root.
import Foundation

enum DaemonError: Error {
    case message(String)

    var text: String {
        if case let .message(value) = self { return value }
        return "unknown error"
    }
}

final class TunnelChild {
    static let stateDir = URL(
        fileURLWithPath: "/Library/Application Support/org.olcbox.app",
        isDirectory: true
    )

    /// Whether a binary lying in the daemon's own directory is one this team
    /// signed. A parameter because a Mac that is only asked what it does (a CI
    /// runner) has no binary of ours to check; the daemon itself never passes one.
    private let isTrusted: (URL) -> Bool

    /// The core to copy when it is not the bundle's own; as above, nil in the daemon.
    private let coreSource: URL?

    /// Called, on a thread of its own, when the child ends without having been
    /// told to: the tunnel died.
    var onDeath: (() -> Void)?

    private var process: Process?
    private var tail: [String] = []
    private let lock = NSLock()

    init(coreSource: URL? = nil, isTrusted: @escaping (URL) -> Bool = PeerAuthority.isTrustedBinary) {
        self.coreSource = coreSource
        self.isTrusted = isTrusted
    }

    var isRunning: Bool { locked { process?.isRunning ?? false } }

    var pid: Int32? { locked { process?.isRunning == true ? process?.processIdentifier : nil } }

    var logTail: String { locked { tail.suffix(40).joined(separator: "\n") } }

    /// The bundled core, found relative to this binary: the daemon lives at
    /// `ProofKit.app/Contents/MacOS/ProofKitTunnelDaemon` and the core at
    /// `ProofKit.app/Contents/Resources/sing-box`.
    ///
    /// `Bundle.main.executableURL` rather than `CommandLine.arguments[0]`: launchd
    /// does not promise argv[0] is an absolute path, and a relative one walked two
    /// directories up lands somewhere arbitrary — where the copy step would fail
    /// with a path that says nothing about why.
    private var bundledCore: URL {
        let executable = Bundle.main.executableURL
            ?? URL(fileURLWithPath: CommandLine.arguments[0])
        return executable
            .resolvingSymlinksInPath()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Resources/sing-box")
    }

    /// This binary, for the step it makes on the child's way to a group of its own (GroupExec).
    private var ownExecutable: URL {
        (Bundle.main.executableURL ?? URL(fileURLWithPath: CommandLine.arguments[0])).resolvingSymlinksInPath()
    }

    /// The daemon's own copy of this binary, in its own directory: the step a
    /// child takes on its way to a group (GroupExec). For the tunnel here, and
    /// for a line's program (LineChild).
    func step() throws -> URL {
        try makeRootOnlyDirectory(Self.stateDir)
        let binDir = Self.stateDir.appendingPathComponent("bin", isDirectory: true)
        try makeRootOnlyDirectory(binDir)
        return try install(ownExecutable, as: "as-group", in: binDir)
    }

    /// [group] is the group the child runs in, for a tunnel started under the
    /// kill switch's block (TrafficHold); nil leaves it in root's own, as ever.
    func start(config: String, files: [String: String] = [:], group: gid_t? = nil) throws {
        stop()

        let source = coreSource ?? bundledCore
        guard FileManager.default.isReadableFile(atPath: source.path) else {
            throw DaemonError.message("no bundled sing-box at \(source.path)")
        }

        try makeRootOnlyDirectory(Self.stateDir)
        let binDir = Self.stateDir.appendingPathComponent("bin", isDirectory: true)
        try makeRootOnlyDirectory(binDir)

        let core = try install(source, as: "sing-box", in: binDir)

        // The rule-sets a Bypass Russia config names, root-owned like the
        // config, at the paths the app wrote into it. Written or removed, never
        // left behind: a config naming a file that is not there stops sing-box
        // with "no such file", and a file nothing names is dead weight.
        let rulesDir = Self.stateDir.appendingPathComponent("rules", isDirectory: true)
        if files.isEmpty {
            try? FileManager.default.removeItem(at: rulesDir)
        } else {
            try makeRootOnlyDirectory(rulesDir)
            for (name, base64) in files {
                guard !name.contains("/"), !name.hasPrefix("."),
                      let data = Data(base64Encoded: base64) else {
                    throw DaemonError.message("rule-set \(name) is not a plain file name with base64 content")
                }
                let url = rulesDir.appendingPathComponent(name)
                try data.write(to: url, options: .atomic)
                try FileManager.default.setAttributes(
                    [.posixPermissions: 0o600, .ownerAccountID: 0],
                    ofItemAtPath: url.path
                )
            }
        }

        let configURL = Self.stateDir.appendingPathComponent("tun.json")
        try config.write(to: configURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600, .ownerAccountID: 0],
            ofItemAtPath: configURL.path
        )

        let task = Process()
        if let group {
            let step = try install(ownExecutable, as: "as-group", in: binDir)
            task.executableURL = step
            task.arguments = [GroupExec.flag, String(group), core.path, "run", "-c", configURL.path]
        } else {
            task.executableURL = core
            task.arguments = ["run", "-c", configURL.path]
        }
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            guard let self else { return }
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            self.locked {
                self.tail.append(contentsOf: text.split(separator: "\n").map(String.init))
                if self.tail.count > 200 { self.tail.removeFirst(self.tail.count - 200) }
            }
        }
        task.terminationHandler = { [weak self] ended in self?.ended(ended) }
        try task.run()
        locked {
            self.process = task
            self.tail = []
        }
    }

    /// A child is gone. Unless a stop took it first, nobody asked it to go.
    private func ended(_ task: Process) {
        let unasked: Bool = locked {
            guard process === task else { return false }
            process = nil
            return true
        }
        if unasked { onDeath?() }
    }

    /// Copies [source] into the daemon's own directory and only then asks
    /// whose it is: what was checked is then the file that is run, in a place
    /// nobody but root can put another. A link is not followed into one: what
    /// lies there afterwards has to be a file.
    private func install(_ source: URL, as name: String, in directory: URL) throws -> URL {
        let target = directory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: target)
        try FileManager.default.copyItem(at: source, to: target)
        let attributes = try FileManager.default.attributesOfItem(atPath: target.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular else {
            try? FileManager.default.removeItem(at: target)
            throw DaemonError.message("\(source.path) is not a file")
        }
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700, .ownerAccountID: 0],
            ofItemAtPath: target.path
        )
        guard isTrusted(target) else {
            try? FileManager.default.removeItem(at: target)
            throw DaemonError.message("\(source.lastPathComponent) is not signed by this team")
        }
        return target
    }

    func stop() {
        let running: Process? = locked {
            defer { process = nil }
            return process?.isRunning == true ? process : nil
        }
        guard let task = running else { return }
        // SIGTERM and then wait, never SIGKILL first, so that sing-box takes
        // its routes out itself. Killed outright it leaves nothing behind
        // either (the utun and every route through it go with the process,
        // measured on macOS 26), but that is the kernel tidying up after a
        // death, and a stop should not look like one.
        task.terminate()
        let deadline = Date().addingTimeInterval(5)
        while task.isRunning && Date() < deadline { usleep(100_000) }
        if task.isRunning { kill(task.processIdentifier, SIGKILL) }
    }

    private func makeRootOnlyDirectory(_ url: URL) throws {
        try FileManager.default.createDirectory(
            at: url,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700, .ownerAccountID: 0]
        )
        // createDirectory leaves an existing directory's attributes alone, and an
        // existing one is the common case after the first run.
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700, .ownerAccountID: 0],
            ofItemAtPath: url.path
        )
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
