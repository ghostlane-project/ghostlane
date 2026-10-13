// A line's own program, run for the app that asks: as the user the app runs
// as, in the group the kill switch's block lets out.
//
// A room's engine binds every socket it gathers its ways through to the
// address of the interface that carries the internet, and this system routes
// what is bound that way by the address, past any tunnel's routes: it leaves
// on the physical interface, in the engine's own name. Under the block that is
// refused like anything else there. Seen on a Mac with a room joined behind
// the block: every STUN and TURN request from those sockets timed out, a
// carrier with a way over TCP was joined by that and one with UDP alone
// (VK Calls) not at all.
//
// The packet filter cannot be told which program a packet is from, only whose
// socket it left by. So the engine is given the tunnel's group, which only
// root can give: the daemon runs it for the app, and gives up being root on
// the way (GroupExec). The app reads what it says from a file of its own, and
// ends it as it ends any process of its user.
//
// Run this way the engine is the one a session without the switch runs: it
// joins its room before there is a tunnel, by the interface it would use
// anyway, and nothing it sends passes through the tun on its way out.
//
// The program is not run from where the app keeps it, which its user can
// write: the group is for this team's engine and not for whatever lies there.
// It is copied into a directory of root's that everybody can read, checked
// where it then lies, and run from there. Only a file that is its asker's own
// is copied: the daemon reads as root, and the copy is for everybody to read.
import Foundation

final class LineChild {
    static let directory = URL(
        fileURLWithPath: "/Library/Application Support/org.olcbox.app.lines",
        isDirectory: true
    )

    private let isTrusted: (URL) -> Bool
    private var process: Process?
    private var said: [String] = []
    private let lock = NSLock()

    init(isTrusted: @escaping (URL) -> Bool = PeerAuthority.isTrustedBinary) {
        self.isTrusted = isTrusted
    }

    var pid: Int32? { locked { process?.isRunning == true ? process?.processIdentifier : nil } }

    /// What the step said when it could not become the program.
    var lastWords: String { locked { said.suffix(4).joined(separator: "; ") } }

    /// Runs a copy of [program] as [user], with what it prints appended to
    /// [output]; [step] is the daemon's own copy that takes it there. Returns
    /// where the copy lies: the name a tun knows the line's program by.
    func start(program: String, arguments: [String], output: String, user: uid_t, step: URL) throws -> String {
        stop()
        guard user != 0 else { throw DaemonError.message("a line is run as the user who asks, and root does not ask") }
        guard program.hasPrefix("/"), output.hasPrefix("/") else {
            throw DaemonError.message("a line's program and its output are named by their full paths")
        }
        let name = URL(fileURLWithPath: program).lastPathComponent
        let plain = !name.isEmpty && !name.hasPrefix(".") && name.unicodeScalars.allSatisfy {
            $0.isASCII && (CharacterSet.alphanumerics.contains($0) || "._-".unicodeScalars.contains($0))
        }
        guard plain else { throw DaemonError.message("a line's program has a plain file name") }

        let target = try install(program, as: name, askedBy: user)

        let task = Process()
        task.executableURL = step
        task.arguments = [
            GroupExec.flag, String(TrafficHold.group),
            GroupExec.userFlag, String(user),
            GroupExec.outputFlag, output,
            target.path,
        ] + arguments
        // Nothing of root's: the step adds the user's own home and name.
        task.environment = [
            "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
            "NO_PROXY": "127.0.0.1,localhost",
            "no_proxy": "127.0.0.1,localhost",
        ]
        task.currentDirectoryURL = URL(fileURLWithPath: "/")
        task.standardInput = FileHandle.nullDevice
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            // Closed by the step once the program's output is the app's file.
            guard !data.isEmpty else { handle.readabilityHandler = nil; return }
            guard let self, let text = String(data: data, encoding: .utf8) else { return }
            self.locked {
                self.said.append(contentsOf: text.split(separator: "\n").map { String($0.prefix(200)) })
                if self.said.count > 20 { self.said.removeFirst(self.said.count - 20) }
            }
        }
        task.terminationHandler = { [weak self] ended in
            self?.locked { if self?.process === ended { self?.process = nil } }
        }
        locked { said = [] }
        try task.run()
        locked { process = task }
        return target.path
    }

    func stop() {
        let running: Process? = locked {
            defer { process = nil }
            return process?.isRunning == true ? process : nil
        }
        guard let task = running else { return }
        task.terminate()
        let deadline = Date().addingTimeInterval(3)
        while task.isRunning && Date() < deadline { usleep(100_000) }
        if task.isRunning { kill(task.processIdentifier, SIGKILL) }
    }

    /// Copies the program into root's directory and only then asks whose it
    /// is. Written by this process from its first byte, as root's and nobody
    /// else's to change: the directory can be read by everybody, so a copy
    /// that was its user's for a moment could be held open and rewritten after
    /// the check. A link is not followed, on either side.
    private func install(_ program: String, as name: String, askedBy user: uid_t) throws -> URL {
        let everybodyReads: [FileAttributeKey: Any] = [
            .posixPermissions: 0o755, .ownerAccountID: 0, .groupOwnerAccountID: 0,
        ]
        try FileManager.default.createDirectory(
            at: Self.directory, withIntermediateDirectories: true, attributes: everybodyReads
        )
        try FileManager.default.setAttributes(everybodyReads, ofItemAtPath: Self.directory.path)

        let input = open(program, O_RDONLY | O_NOFOLLOW)
        guard input >= 0 else { throw DaemonError.message("no line's program at \(program)") }
        defer { close(input) }
        var info = stat()
        guard fstat(input, &info) == 0, (info.st_mode & S_IFMT) == S_IFREG else {
            throw DaemonError.message("\(program) is not a file")
        }
        guard info.st_uid == user else {
            throw DaemonError.message("\(program) is not a file of the user who asks")
        }

        let target = Self.directory.appendingPathComponent(name)
        unlink(target.path)
        let output = open(target.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o700)
        guard output >= 0 else {
            throw DaemonError.message("cannot write \(target.path): \(String(cString: strerror(errno)))")
        }
        var copied = false
        defer {
            close(output)
            if !copied { unlink(target.path) }
        }
        var buffer = [UInt8](repeating: 0, count: 1 << 16)
        while true {
            let count = read(input, &buffer, buffer.count)
            if count == 0 { break }
            guard count > 0 else { throw DaemonError.message("reading \(program): \(String(cString: strerror(errno)))") }
            var written = 0
            while written < count {
                let step = buffer.withUnsafeBytes { write(output, $0.baseAddress! + written, count - written) }
                guard step > 0 else {
                    throw DaemonError.message("writing \(target.path): \(String(cString: strerror(errno)))")
                }
                written += step
            }
        }
        guard fchown(output, 0, 0) == 0 else {
            throw DaemonError.message("cannot make \(target.path) root's")
        }
        guard isTrusted(target) else {
            throw DaemonError.message("\(name) is not signed by this team")
        }
        // Root's alone until here; now everybody's to read and to run.
        guard fchmod(output, 0o755) == 0 else {
            throw DaemonError.message("cannot let \(target.path) be run")
        }
        copied = true
        return target
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
