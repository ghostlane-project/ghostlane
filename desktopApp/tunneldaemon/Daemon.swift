// What the daemon does with a request: the tunnel's process, the system's
// resolver while that process runs, and the kill switch's block.
//
// The tunnel's process is hev with a config written here from the fields of a
// `tun` request. Its routes are laid here (Routes.swift), and taken away by
// every stop and when hev dies. The sing-box tunnel (`start`, the first four
// protocols) is gone: a request for it is an unknown verb, and what it left in
// root's directory is swept when the daemon starts (Sweep.swift).
//
// Kept apart from the socket it is asked over (main.swift) so that it can be
// driven without one, on a Mac that has no signed app to do the asking.
import Foundation

/// The daemon's own binary as it was when launchd started it.
///
/// An app update replaces the bundle underneath a daemon launchd keeps alive,
/// and a request the old code serves with the new app's expectations fails the
/// way a broken tunnel does. When the file on disk is no longer the one that is
/// running, the daemon steps aside on the next start or stop and launchd brings
/// up the new one.
struct BinaryIdentity: Equatable {
    let size: UInt64
    let modified: Date
    let fileNumber: UInt64?

    static func onDisk() -> BinaryIdentity? {
        guard let path = Bundle.main.executableURL?.path,
              let attributes = try? FileManager.default.attributesOfItem(atPath: path) else { return nil }
        return BinaryIdentity(
            size: (attributes[.size] as? NSNumber)?.uint64Value ?? 0,
            modified: attributes[.modificationDate] as? Date ?? Date.distantPast,
            fileNumber: (attributes[.systemFileNumber] as? NSNumber)?.uint64Value
        )
    }
}

final class Daemon {
    // Bumped when a request gains a field the app depends on: 2 = rule files on
    // `start`; 3 = `names` and `hold` on `start`, `hold` on `stop`, `release`,
    // and `hold` in every reply; 4 = `line` and `line-stop`, and `line` in
    // every reply; 5 = `tun` (hev, from fields, and the routes it takes),
    // `routes` in every reply and `tunnel` while one runs. The numbers keep
    // their meaning: `start` of 2 to 4 is the verb that was deleted, and the
    // app reads the number back to tell this daemon from an older one that
    // would silently drop a field.
    static let protocolVersion = 5

    private let child: TunnelChild
    private let line: LineChild
    /// hev's routes.
    private let routes = Routes()
    private let hold: TrafficHold
    private let launchedAs = BinaryIdentity.onDisk()

    /// One at a time: a request, the child's death, and the daemon being told to end.
    private let lock = NSLock()

    /// Set by a request that found a newer daemon on disk: the caller sends the reply and exits.
    private(set) var quitAfterReply = false

    init(child: TunnelChild = TunnelChild(), line: LineChild = LineChild(), hold: TrafficHold = TrafficHold()) {
        self.child = child
        self.line = line
        self.hold = hold
        // What an earlier build left in root's directory, before anybody is answered.
        Sweep.run()
        // A daemon that was killed left its key where it was, pointing the
        // system's lookups at a tunnel that died with it.
        SystemNames.restore()
        child.onDeath = { [weak self] in self?.tunnelDied() }
    }

    /// [user] is who asks, as the socket names its peer: the user a line's
    /// program is run as. Nil where nobody can be named, and then none is run.
    func handle(_ request: String, from user: uid_t? = nil) -> Data {
        lock.lock()
        defer { lock.unlock() }

        guard let payload = request.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
              let verb = object["verb"] as? String else {
            return failure("unparseable request")
        }

        if (verb == "tun" || verb == "stop" || verb == "line") && replacedOnDisk() {
            // Only on the verbs that change a child's state: a status query from
            // the new app must not cost a tunnel the old daemon is still carrying.
            // The block is the kernel's and outlives this process: a stop that
            // asks for it to go still takes it, and a tun leaves it for the
            // daemon that comes next to load its own over.
            stopTunnel()
            line.stop()
            if verb == "stop" && !keepsHold(object) { hold.release() }
            quitAfterReply = true
            return reply([
                "ok": false,
                "error": "the tunnel helper was updated and is restarting",
                "restarting": true,
                "logTail": ""
            ])
        }

        switch verb {
        case "tun":
            return startHev(object)
        case "stop":
            stopTunnel()
            if !keepsHold(object) { hold.release() }
            return reply(["ok": true, "state": "idle", "logTail": child.logTail])
        case "line":
            return startLine(object, as: user)
        case "line-stop":
            line.stop()
            return status()
        case "release":
            // The block alone: a tunnel that runs goes on running, beside
            // traffic that is no longer held when it stops.
            hold.release()
            return status()
        case "status":
            return status()
        default:
            return failure("unknown verb \(verb)")
        }
    }

    /// launchd's SIGTERM: the daemon is being stopped, removed or replaced.
    /// Nothing of it is left on the machine, the block least of all.
    func shutDown() -> Never {
        lock.lock()
        stopTunnel()
        line.stop()
        hold.release()
        exit(0)
    }

    /// A line's program, run for the user who asks (LineChild). It is told
    /// apart from the tunnel in every way: a stop of the tunnel leaves it, the
    /// tunnel's death leaves it, and the app ends it as it ends any process of
    /// its own.
    private func startLine(_ object: [String: Any], as user: uid_t?) -> Data {
        guard let user else { return failure("a line is run as the user who asks, and nobody is named") }
        guard let program = object["program"] as? String, let output = object["output"] as? String else {
            return failure("a line names its program and where it writes")
        }
        let arguments = object["arguments"] as? [String] ?? []
        do {
            let lies = try line.start(
                program: program, arguments: arguments, output: output, user: user, step: try child.step()
            )
            // What the step says when it cannot become the program, it says at once.
            Thread.sleep(forTimeInterval: 0.3)
            guard line.pid != nil else {
                return failure("the line's program did not start: \(line.lastWords)")
            }
            return reply(["ok": true, "state": child.isRunning ? "running" : "idle", "program": lies, "logTail": ""])
        } catch {
            let text = (error as? DaemonError)?.text ?? error.localizedDescription
            return failure(text)
        }
    }

    /// hev's tunnel (protocol 5). The request is read whole before anything
    /// is touched: one that is refused costs the tunnel that runs nothing.
    private func startHev(_ object: [String: Any]) -> Data {
        let request: HevRequest
        let wanted: HoldRequest?
        do {
            request = try HevRequest.parse(object)
            wanted = try HoldRequest.parse(object["hold"])
        } catch {
            return failure((error as? DaemonError)?.text ?? error.localizedDescription)
        }
        var names: String?
        if let asked = object["names"] {
            guard let address = asked as? String, HoldRequest.isAddress(address) else {
                return failure("names are asked of an address")
            }
            names = address
        }
        if let wanted, wanted.interface != request.name {
            return failure("the block is written for the tunnel's own interface, \(request.name)")
        }
        do {
            stopTunnel()
            // The block first, so that the tunnel comes up behind it. A tun that
            // asks for none is a session without the switch, and takes away one
            // that stood: its tunnel is in no rule of it.
            if let wanted {
                try hold.arm(wanted)
            } else if hold.stands {
                hold.release()
            }
            try child.startHev(request, group: wanted == nil ? nil : TrafficHold.group)
            guard child.waitUntilUp(request.name, holding: request.ipv4, seconds: 5) else {
                let tail = child.logTail
                stopTunnel()
                return failure("hev did not bring \(request.name) up", logTail: tail)
            }
            // What hev refuses after its tun is up (its mapdns) ends it at once.
            Thread.sleep(forTimeInterval: 0.3)
            guard child.isRunning else {
                return failure("hev exited at once", logTail: child.logTail)
            }
            // Into the tun, all but what it keeps out; before the names, which
            // are asked of an address only the tun answers.
            try routes.lay(on: request.name, holes: request.exclude, ipv6: request.ipv6 != nil)
            if let names, !SystemNames.ask(names) {
                let tail = child.logTail
                stopTunnel()
                return failure("the system did not take the tunnel as its resolver", logTail: tail)
            }
            return reply([
                "ok": true,
                "state": "running",
                "pid": Int(child.pid ?? 0),
                "interface": request.name,
                "logTail": child.logTail
            ])
        } catch {
            let tail = child.logTail
            stopTunnel()
            return failure((error as? DaemonError)?.text ?? error.localizedDescription, logTail: tail)
        }
    }

    private func status() -> Data {
        reply([
            "ok": true,
            "state": child.isRunning ? "running" : "idle",
            "pid": Int(child.pid ?? 0),
            "logTail": child.logTail
        ])
    }

    /// The tunnel's process and what stands only while it runs.
    private func stopTunnel() {
        // The routes first: through a tun that is going they lead nowhere.
        routes.takeAway()
        child.stop()
        SystemNames.restore()
    }

    /// The tunnel ended by itself. The system goes back to the resolver it had,
    /// at once: the one it was given answers nowhere now. The block is left
    /// exactly as it is, which is what it is for.
    private func tunnelDied() {
        lock.lock()
        defer { lock.unlock() }
        // Not when another tunnel has been started since: the key and the
        // routes are that one's. Its own routes went with its utun.
        if !child.isRunning {
            routes.takeAway()
            SystemNames.restore()
        }
    }

    private func keepsHold(_ object: [String: Any]) -> Bool {
        object["hold"] as? String == "keep"
    }

    private func replacedOnDisk() -> Bool {
        guard let launchedAs, let now = BinaryIdentity.onDisk() else { return false }
        return now != launchedAs
    }

    private func failure(_ message: String, logTail: String = "") -> Data {
        reply(["ok": false, "error": message, "logTail": logTail])
    }

    private func reply(_ object: [String: Any]) -> Data {
        var object = object
        object["protocol"] = Self.protocolVersion
        // With a tunnel up the answer is what this process loaded or took away.
        // With none it is pf's own: that is when the answer is acted on (is this
        // machine's traffic held?), and the rules can have gone without this
        // process having been told, by a hand or by another program.
        object["hold"] = child.isRunning ? hold.stands : hold.look()
        object["line"] = Int(line.pid ?? 0)
        if let kind = child.kind { object["tunnel"] = kind.rawValue }
        object["routes"] = routes.laid.count
        let data = (try? JSONSerialization.data(withJSONObject: object))
            ?? Data("{\"ok\":false,\"error\":\"the daemon could not encode its own reply\"}".utf8)
        return data + Data("\n".utf8)
    }
}
