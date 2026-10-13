// What the daemon does with a request: the tunnel's process, the system's
// resolver while that process runs, and the kill switch's block.
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
    // and `hold` in every reply. The app reads it back to tell this daemon from
    // an older one that would silently drop the field.
    static let protocolVersion = 3

    private let child: TunnelChild
    private let hold: TrafficHold
    private let launchedAs = BinaryIdentity.onDisk()

    /// One at a time: a request, the child's death, and the daemon being told to end.
    private let lock = NSLock()

    /// Set by a request that found a newer daemon on disk: the caller sends the reply and exits.
    private(set) var quitAfterReply = false

    init(child: TunnelChild = TunnelChild(), hold: TrafficHold = TrafficHold()) {
        self.child = child
        self.hold = hold
        // A daemon that was killed left its key where it was, pointing the
        // system's lookups at a tunnel that died with it.
        SystemNames.restore()
        child.onDeath = { [weak self] in self?.tunnelDied() }
    }

    func handle(_ line: String) -> Data {
        lock.lock()
        defer { lock.unlock() }

        guard let payload = line.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
              let verb = object["verb"] as? String else {
            return failure("unparseable request")
        }

        if (verb == "start" || verb == "stop") && replacedOnDisk() {
            // Only on the verbs that change the child's state: a status query from
            // the new app must not cost a tunnel the old daemon is still carrying.
            // The block is the kernel's and outlives this process: a stop that
            // asks for it to go still takes it, and a start leaves it for the
            // daemon that comes next to load its own over.
            stopTunnel()
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
        case "start":
            return start(object)
        case "stop":
            stopTunnel()
            if !keepsHold(object) { hold.release() }
            return reply(["ok": true, "state": "idle", "logTail": child.logTail])
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
        exit(0)
    }

    private func start(_ object: [String: Any]) -> Data {
        guard let config = object["config"] as? String else {
            return failure("start without a config")
        }
        // Rule-set binaries the config refers to, file name → base64; absent
        // for a config that names none.
        let files = object["files"] as? [String: String] ?? [:]
        // The address the system is to ask for names while this tunnel runs.
        var names: String?
        if let asked = object["names"] {
            guard let address = asked as? String, HoldRequest.isAddress(address) else {
                return failure("names are asked of an address")
            }
            names = address
        }
        do {
            let wanted = try HoldRequest.parse(object["hold"])
            stopTunnel()
            // Before the tunnel, so that it comes up behind the block. A start
            // that asks for none is a session without the switch, and takes
            // away one that stood: its tunnel is in no rule of it.
            if let wanted {
                try hold.arm(wanted)
            } else if hold.stands {
                hold.release()
            }
            try child.start(config: config, files: files, group: wanted == nil ? nil : TrafficHold.group)
            // Report what the child is doing a moment later, not what it was asked
            // to do. sing-box rejects a bad config in well under a second, and an
            // "ok" issued before that is a lie the app draws as a green light.
            Thread.sleep(forTimeInterval: 1.0)
            guard child.isRunning else {
                return failure("sing-box exited at once", logTail: child.logTail)
            }
            if let names, !SystemNames.ask(names) {
                // Up, and with names still going where they went before it: not
                // what was asked for, so not a tunnel to leave running.
                child.stop()
                return failure("the system did not take the tunnel as its resolver", logTail: child.logTail)
            }
            return reply([
                "ok": true,
                "state": "running",
                "pid": Int(child.pid ?? 0),
                "logTail": child.logTail
            ])
        } catch {
            let text = (error as? DaemonError)?.text ?? error.localizedDescription
            return failure(text, logTail: child.logTail)
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
        child.stop()
        SystemNames.restore()
    }

    /// The tunnel ended by itself. The system goes back to the resolver it had,
    /// at once: the one it was given answers nowhere now. The block is left
    /// exactly as it is, which is what it is for.
    private func tunnelDied() {
        lock.lock()
        defer { lock.unlock() }
        // Not when another tunnel has been started since: the key is that one's.
        if !child.isRunning { _ = SystemNames.isSet }
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
        let data = (try? JSONSerialization.data(withJSONObject: object))
            ?? Data("{\"ok\":false,\"error\":\"the daemon could not encode its own reply\"}".utf8)
        return data + Data("\n".utf8)
    }
}
