import Cores
import Foundation
import os

/// olcRTC, running inside the tunnel extension.
///
/// It used to run inside the app instead, as a SOCKS proxy on loopback that
/// other apps had to be pointed at by hand and that survived backgrounding only
/// by playing silent audio to stop iOS suspending the process. Here it is a
/// transport like any other: sing-box owns the tun and reaches this engine over
/// a loopback SOCKS port, the same arrangement xhttp already uses for Xray.
///
/// The engine is one `MobileRuntime` for the life of the extension process;
/// the log writer is still a package-level hook. This stays a namespace: the
/// runtime is a static because a tunnel extension hosts one tunnel.
enum OlcrtcEngine {

    /// What the app writes into the App Group for us. A room and a key address
    /// an olcRTC location; there is no link to parse.
    struct Parameters: Decodable {
        let carrierName: String
        let transportName: String
        let roomId: String
        let clientId: String
        let keyHex: String
        let socksPort: Int
        let socksUser: String
        let socksPass: String
        let vp8Fps: Int
        let vp8BatchSize: Int
        /// The engine's direct rules under Bypass Russia, empty under Global.
        /// Optional so a file written by an app without the field still decodes.
        let directRules: String?
        /// The other rooms of the location's failover group (`##rooms`), the
        /// primary excluded; the engine hops to them when the room it is in
        /// ends. Optional for the same reason as `directRules`.
        ///
        /// ai-generated: this field, `subscriptionUrl`, and the room handling
        /// in `launch`, `applyRooms` and `relaunch` (see RoomKeeper).
        let failoverRooms: [String]?
        /// Where the room list came from, when it came from a subscription:
        /// RoomKeeper re-reads it through the tunnel after every handover.
        let subscriptionUrl: String?
    }

    private static let log = Logger(subsystem: "org.proofkit.app", category: "olcrtc")

    private static let appGroup = "group.org.proofkit.app"

    /// Verbose engine logging: on for development builds, never for a release.
    ///
    /// olcRTC keeps almost everything worth reading behind `logger.Debugf`, so
    /// with this off a failed start says "olcRTC start timed out" and not one
    /// word about what it tried — no ICE state, no carrier, no signalling. That
    /// is the whole of what a user, or whoever is debugging for them, ever gets,
    /// and it cost most of a day.
    ///
    /// Off. ICE tracing writes hundreds of lines a second — it answered the
    /// eight-second timeout and then made the log unreadable for everything
    /// else, including in development, where it is on all the time by
    /// definition. Flip to true while chasing a connect that fails; the engine
    /// still logs its ordinary markers here either way, and `olcrtc.log` in the
    /// App Group keeps them whether this is on or not.
    private static let verbose = false

    /// The engine, from the Go constructor: `MobileRuntime()` allocates a
    /// bare struct with no defaults and cannot start. `nonisolated(unsafe)`
    /// because gobind classes are not Sendable and the extension drives one
    /// tunnel at a time from the provider's own serialisation.
    nonisolated(unsafe) private static let runtime: MobileRuntime = {
        guard let runtime = MobileNew() else {
            fatalError("olcrtc: MobileNew() returned nil")
        }
        return runtime
    }()

    /// Held for the lifetime of the process: olcRTC keeps whatever is handed to
    /// `setProtector`/`MobileSetLogWriter`, and Go's reference does not keep a
    /// Swift object alive on its own.
    private static let protector = InterfaceProtector()
    private static let logWriter = EngineLog(
        file: FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("olcrtc.log")
    )

    /// What `start` was last given, for `relaunch`. Written by the provider's
    /// start, read on RoomKeeper's queue; the lock is for that handoff.
    private static let currentLock = NSLock()
    nonisolated(unsafe) private static var current: (parameters: Parameters, resolvers: [String])?

    /// How long to wait for the engine to answer before calling it a failure.
    ///
    /// Was eight seconds, inherited from the in-app path. A trace of a failed
    /// start shows ICE reaching two valid candidate pairs — a TURN relay and a
    /// server-reflexive one — inside the first second, both `succeeded`, neither
    /// nominated, and then nothing at all until the timeout. Eight seconds may
    /// simply be short for a negotiation that crosses the Atlantic to an SFU in
    /// Russia and still has DTLS and the VP8 channel ahead of it.
    ///
    /// So: raised, as an experiment that tells the two remaining explanations
    /// apart. If it connects at twelve seconds the answer is that this was too
    /// short and the number wants choosing properly; if it still stops dead
    /// after the same pairs, more time was never the problem and the log now
    /// covers enough of the attempt to say what is.
    // 35 s, up from 20. A cellular ICE negotiation to the Telemost SFU took
    // 21 s from start to "SOCKS5 server listening" on 2026-09-09 and was
    // declared timed out one second short of ready; the app itself waits 45 s
    // for the tunnel, so there is room, and a slow start is not a dead one.
    private static let readyTimeoutMillis = 35_000

    /// How long a stop may take before the engine is abandoned to the process.
    private static let stopTimeoutMillis = 5_000

    /// `resolvers` are the servers of the network the extension stands on, as
    /// ResolverSnapshot read them before the tunnel's settings went on; empty
    /// when none could be read.
    static func start(_ parameters: Parameters, resolvers: [String] = []) throws {
        // Fresh per attempt, so whatever the app reads back afterwards belongs
        // to the attempt it is reporting on.
        logWriter.reset()
        MobileSetLogWriter(logWriter)
        currentLock.lock()
        current = (parameters, resolvers)
        currentLock.unlock()
        // The app's list, joined with what this process last learned for the
        // same carrier and key: the app may have slept through several
        // handovers.
        let rooms = RoomKeeper.initialRooms(for: parameters)
        // Before the launch, so the first session the engine reports is seen.
        RoomKeeper.shared.begin(parameters: parameters, rooms: rooms)
        do {
            try launch(parameters, resolvers: resolvers, rooms: rooms)
        } catch {
            RoomKeeper.shared.stop()
            throw error
        }
        RoomKeeper.shared.armed()
    }

    /// Starts the engine again over `rooms`, with everything else as `start`
    /// was given. For RoomKeeper, when a generation has ended.
    static func relaunch(rooms: RoomList.Parsed, resolversForNewPath: [String]? = nil) throws {
        currentLock.lock()
        let saved = current
        if let saved, let resolversForNewPath {
            current = (saved.parameters, resolversForNewPath)
        }
        currentLock.unlock()
        guard let saved else { throw failure(nil, "olcRTC was never started") }
        try launch(saved.parameters, resolvers: resolversForNewPath ?? saved.resolvers, rooms: rooms)
    }

    /// Hands the running engine a room list. It is read at the engine's next
    /// hop; the live session is not touched.
    static func applyRooms(_ rooms: RoomList.Parsed) throws {
        try runtime.setRoom(rooms.primary)
        runtime.clearFailoverRooms()
        for room in rooms.extras {
            try runtime.addFailoverRoom(room)
        }
    }

    /// Whether a generation is starting, running or stopping.
    static var isRunning: Bool { runtime.isRunning() }

    /// A line in olcrtc.log beside the engine's own, for what happens to it
    /// from this side: room lists, restarts.
    static func note(_ line: String) {
        logWriter.writeLog(line)
    }

    private static func launch(_ parameters: Parameters, resolvers: [String], rooms: RoomList.Parsed) throws {
        runtime.setDebug(verbose)
        // Before anything dials: inside the extension the default route is our
        // own tun, so an unprotected socket loops straight back into it.
        runtime.setProtector(protector)
        // Each session the engine opens - the first, and every one after a hop
        // or a reconnect - is reported to the keeper, which refreshes the room
        // list through it.
        runtime.setSessionListener(RoomKeeper.shared)
        try runtime.setTransport(parameters.transportName)
        // The network's own resolvers first, a public operator behind them; the
        // engine adds that operator's IPv6 twin and the other operators after.
        // Some mobile networks answer only their own servers (olcbox#16).
        try runtime.setDNS((resolvers + ["1.1.1.1:53"]).joined(separator: ","))
        log.info("resolvers from the network: \(resolvers.count, privacy: .public)")
        // Bypass Russia lives in the engine on this platform: hev fronts it
        // and routes nothing. A matching name or address is dialed by the
        // engine through the pin above, resolved on the resolvers above;
        // everything else rides the room. Empty text is Global.
        let directRules = parameters.directRules ?? ""
        try runtime.setDirectRules(directRules)
        log.info("direct rules: \(directRules.utf8.count, privacy: .public) bytes")
        try runtime.setVP8Options(parameters.vp8Fps, batchSize: parameters.vp8BatchSize)
        // Loopback only. The port is fixed rather than user-set now: nothing
        // outside this process is meant to reach it.
        try runtime.setSocksListenHost("127.0.0.1")
        try runtime.setProvider(parameters.carrierName)
        // The primary and the failover extras. The engine walks them under its
        // supervisor when the room it is in ends, and re-reads the list at
        // every hop, so RoomKeeper can grow it while a session is live.
        try applyRooms(rooms)
        runtime.setDeviceID(parameters.clientId)
        try runtime.setKey(parameters.keyHex)
        try runtime.setSocksPort(parameters.socksPort)
        try runtime.setSocksCredentials(parameters.socksUser, password: parameters.socksPass)

        // A previous tunnel that died without tearing down would otherwise hold
        // the port and make this look like a bind failure.
        if runtime.isRunning() {
            try? runtime.stop(stopTimeoutMillis)
        }

        do {
            try runtime.start()
        } catch {
            throw failure(error as NSError, "olcRTC would not start")
        }

        do {
            try runtime.waitReady(readyTimeoutMillis)
        } catch {
            // Leaving a half-started engine behind would hold the SOCKS port
            // against the next attempt.
            try? runtime.stop(stopTimeoutMillis)
            throw failure(error as NSError, "olcRTC did not become ready")
        }
        log.info("olcrtc ready on 127.0.0.1:\(parameters.socksPort, privacy: .public)")
    }

    static func stop() {
        // The keeper first, or its watchdog would read the stop below as an
        // engine that died and start it again.
        RoomKeeper.shared.stop()
        // Unconditional teardown runs on every tunnel stop, including tunnels
        // that never involved olcRTC at all.
        if runtime.isRunning() {
            try? runtime.stop(stopTimeoutMillis)
        }
    }

    private static func failure(_ error: NSError?, _ fallback: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 5,
                userInfo: [NSLocalizedDescriptionKey: error?.localizedDescription ?? fallback])
    }
}

/// Keeps olcRTC's own sockets off our tun.
///
/// The engine dials from inside the extension, where the default route points
/// at the tun sing-box owns — so a socket left to the system's judgement comes
/// straight back to us. This is the same pin sing-box needs for its outbounds,
/// and deliberately the same implementation.
private final class InterfaceProtector: NSObject, MobileSocketProtectorProtocol, @unchecked Sendable {
    func protect(_ fd: Int) -> Bool {
        LibboxPlatform.pinToPhysicalInterface(Int32(fd))
    }
}

/// olcRTC explains itself through a callback rather than stderr, so its lines
/// do not reach the engine.log the provider redirects sing-box into. Sending
/// them to the system log at least puts them in the same place as ours.
/// Where olcRTC's own account of itself goes.
///
/// The unified log alone was not enough. Reaching it means Console.app, an
/// iPhone in the sidebar, "Include Info Messages" switched on and a filter on
/// the right process — four steps, each of which has silently produced an empty
/// window at least once, for a log that answers the only question that matters
/// when a connect fails. So it is also kept in the App Group, where the app can
/// read it back and put it on screen next to the failure.
private final class EngineLog: NSObject, @unchecked Sendable, MobileLogWriterProtocol {
    private let log = Logger(subsystem: "org.proofkit.app", category: "olcrtc")
    private let file: URL?
    /// Every mutable field below is touched only from this queue. Go calls
    /// `writeLog` from whichever goroutine happens to be logging.
    private let queue = DispatchQueue(label: "org.proofkit.olcrtc-log")

    /// Both ends of the attempt, never the sagging middle.
    ///
    /// A plain tail is the wrong shape here: with ICE tracing on, a few seconds
    /// of candidate checks bury the lines that say which room was joined and
    /// what was negotiated — and those come first. So the opening is kept
    /// whole, the most recent lines are kept whole, and what is dropped between
    /// them is counted rather than hidden.
    private var head: [String] = []
    private var tail: [String] = []
    private var dropped = 0
    private var flushScheduled = false

    private static let headWindow = 150
    private static let tailWindow = 250

    init(file: URL?) {
        self.file = file
        super.init()
    }

    func reset() {
        queue.async { [self] in
            head.removeAll(keepingCapacity: true)
            tail.removeAll(keepingCapacity: true)
            dropped = 0
            if let file { try? Data().write(to: file, options: .atomic) }
        }
    }

    func writeLog(_ msg: String?) {
        guard let msg else { return }
        log.info("\(msg, privacy: .public)")
        guard file != nil else { return }
        queue.async { [self] in
            if head.count < Self.headWindow {
                head.append(msg)
            } else {
                tail.append(msg)
                if tail.count > Self.tailWindow {
                    let over = tail.count - Self.tailWindow
                    tail.removeFirst(over)
                    dropped += over
                }
            }
            scheduleFlush()
        }
    }

    /// Coalesced, because ICE tracing logs faster than a phone should be asked
    /// to rewrite a file. An atomic write per line — a temp file and a rename,
    /// hundreds of times a second, inside an extension with a ~50 MB ceiling —
    /// would risk changing the very outcome this is here to observe.
    private func scheduleFlush() {
        guard !flushScheduled else { return }
        flushScheduled = true
        queue.asyncAfter(deadline: .now() + 0.25) { [self] in
            flushScheduled = false
            persist()
        }
    }

    private func persist() {
        guard let file else { return }
        var out = head
        if dropped > 0 { out.append("… \(dropped) lines dropped …") }
        out += tail
        // Rewritten whole rather than appended: an append interrupted by the
        // process dying can tear the last line, and the last line is the one
        // this exists to read.
        try? Data((out.joined(separator: "\n") + "\n").utf8)
            .write(to: file, options: .atomic)
    }
}
