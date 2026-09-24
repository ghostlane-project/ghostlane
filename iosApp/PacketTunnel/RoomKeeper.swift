import Cores
import CryptoKit
import Foundation
import os

/// Keeps a running olcRTC location's room list current, and its engine alive,
/// for as long as the tunnel is up.
///
/// The server behind an olcRTC location may move its clients between rooms
/// that live about a day: it advertises the next room in the subscription
/// (`##rooms`), retires the old one once the client has been told, and the
/// engine hops to the next room it knows. Two things make that the extension's
/// job rather than the app's. The subscription is often reachable only through
/// the tunnel, and iOS suspends the app for most of the tunnel's life, so the
/// process that can still fetch the list after a handover is this one. And an
/// engine whose every room has died ends its generation with an error; the app
/// that would restart it is asleep, so the restart has to come from here too.
///
/// Everything below runs on one serial queue, except `running`, which the
/// provider's stop flips from its own thread. The engine's own calls are
/// thread-safe; the state around them is not, and does not need to be.
///
/// ai-generated: the whole file.
final class RoomKeeper: NSObject, MobileSessionListenerProtocol, @unchecked Sendable {
    static let shared = RoomKeeper()

    /// Two refreshes closer than this are one: a client cycling through dead
    /// rooms every two seconds would otherwise turn every hop into a fetch.
    static let minRefreshInterval: TimeInterval = 15
    /// The app's own schedule does not tick while the app is suspended; this
    /// one catches whatever a handover event did not.
    static let periodicRefresh: TimeInterval = 5 * 60
    /// A fetch that failed - the tunnel was mid-hop, the server hiccuped - is
    /// tried again sooner than the next periodic pass.
    static let retryAfterFailure: TimeInterval = 30
    static let watchdogTick: TimeInterval = 3
    /// Between restarts of an engine whose rooms all died. Never gives up:
    /// where the list is only reachable through the tunnel, a restart is the
    /// only way back to one, and the rooms in the list may come alive again.
    static let restartBackoff: [TimeInterval] = [2, 4, 8, 16, 20]

    private let log = Logger(subsystem: "org.proofkit.app", category: "olcrtc-rooms")
    private let queue = DispatchQueue(label: "org.proofkit.olcrtc-rooms")
    private lazy var http: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }()

    /// Between `begin` and `stop`. Read on the queue, written by the provider
    /// thread too, hence the lock rather than the queue.
    private let runningLock = NSLock()
    private var runningFlag = false
    private var running: Bool {
        runningLock.lock()
        defer { runningLock.unlock() }
        return runningFlag
    }
    var isActive: Bool { running }
    private func setRunning(_ value: Bool) {
        runningLock.lock()
        runningFlag = value
        runningLock.unlock()
    }

    // All of the following is touched only on `queue`.
    private var parameters: OlcrtcEngine.Parameters?
    /// The list the engine has now: primary first.
    private var rooms: RoomList.Parsed?
    private var sessions = 0
    /// Whether the first session of the current generation should refresh the
    /// list. Not on the initial start, where the app is awake and refreshes on
    /// Connected; yes after a restart from here, when it may well not be.
    private var firstSessionRefreshes = false
    private var lastRefresh = Date.distantPast
    private var fetching = false
    private var periodic: DispatchSourceTimer?
    private var retry: DispatchSourceTimer?
    private var watchdog: DispatchSourceTimer?
    private var restartAttempt = 0
    private var restartNotBefore = Date.distantPast
    private var networkRecoveryGeneration = 0

    /// The list a fresh start begins with: what the app wrote, joined with what
    /// this process last learned for the same carrier and key. The app's copy
    /// can be older than the extension's - the app was suspended through the
    /// handovers and its file names rooms that have since been retired - and a
    /// start on a dead room with no other room to try is what these rooms are
    /// for.
    static func initialRooms(for parameters: OlcrtcEngine.Parameters) -> RoomList.Parsed {
        var extras = parameters.failoverRooms ?? []
        if let remembered = RoomMemory.load(
            carrier: parameters.carrierName,
            keyHex: parameters.keyHex,
            startHasRoomsGroup: !extras.isEmpty
        ) {
            extras += remembered.all
        }
        let unique = RoomList.Parsed(primary: parameters.roomId, extras: extras).all
        return RoomList.Parsed(primary: parameters.roomId, extras: Array(unique.dropFirst()))
    }

    /// Before the engine is launched, so the first session it reports is
    /// counted. Synchronous: the engine's first event must find this state.
    func begin(parameters: OlcrtcEngine.Parameters, rooms: RoomList.Parsed) {
        queue.sync { [self] in
            self.parameters = parameters
            self.rooms = rooms
            sessions = 0
            firstSessionRefreshes = false
            restartAttempt = 0
            restartNotBefore = .distantPast
            networkRecoveryGeneration += 1
            lastRefresh = .distantPast
            cancelTimers()
            setRunning(true)
            OlcrtcEngine.note("room list at start (\(rooms.all.count) rooms: \(RoomDigest.list(rooms.all)))")
            NetworkDiagnostics.record("olcrtc rooms at start: \(rooms.all.count)")
        }
    }

    /// Once the engine is ready: from here the keeper watches it.
    func armed() {
        queue.async { [self] in
            guard running else { return }
            startTimers()
        }
    }

    /// From the provider's stop. Sets the flag first, off the queue, so a
    /// restart in flight on the queue cannot outlive the tunnel it belonged to.
    func stop() {
        setRunning(false)
        queue.async { [self] in
            networkRecoveryGeneration += 1
            cancelTimers()
        }
    }

    /// Reopen the WebRTC session on a new physical network without replacing
    /// the TUN or losing the room list learned while the app was asleep.
    /// This runs on the same queue as the watchdog, so the two cannot launch
    /// competing engine generations. Repeated path events collapse to one.
    func networkChanged() {
        queue.async { [self] in
            guard running else { return }
            networkRecoveryGeneration += 1
            let generation = networkRecoveryGeneration
            queue.asyncAfter(deadline: .now() + 1) { [self] in
                guard running, generation == networkRecoveryGeneration, let rooms else { return }
                sessions = 0
                firstSessionRefreshes = true
                // The watchdog owns a failed launch after this attempt.
                restartNotBefore = Date().addingTimeInterval(Self.restartBackoff[0])
                NetworkDiagnostics.record("olcrtc network handover: reopening session, keeping tun")
                do {
                    // ResolverSnapshot was taken on the old network before
                    // the TUN came up. Drop those addresses after a handover;
                    // the engine's public resolver ring supplies fallbacks.
                    try OlcrtcEngine.relaunch(rooms: rooms, resolversForNewPath: [])
                    NetworkDiagnostics.record("olcrtc network handover: ready")
                } catch {
                    OlcrtcEngine.note("network handover failed: \(error.localizedDescription)")
                    NetworkDiagnostics.record("olcrtc network handover failed; watchdog will retry")
                }
            }
        }
    }

    /// The app's copy of the list, sent while it is awake and a refresh changed
    /// it; the extension's own fetch is the same list a few minutes later.
    ///
    /// Only for the carrier the engine runs. The app matches the location
    /// before it sends, but a list for a sibling carrier - same origin, same
    /// key - that got through would reach the engine as failover rooms it
    /// cannot join and be saved in RoomMemory under this carrier, where the
    /// next start would pick it up again. So this checks too, and a list for
    /// another carrier is neither applied nor remembered.
    func apply(primary: String, extras: [String], carrier: String, source: String) {
        queue.async { [self] in
            guard running, let parameters else { return }
            guard RoomList.sameCarrier(carrier, parameters.carrierName) else {
                OlcrtcEngine.note("room list from the \(source) dropped: it is for \(carrier), the engine runs \(parameters.carrierName)")
                NetworkDiagnostics.record("olcrtc rooms from the \(source) dropped: another carrier")
                return
            }
            apply(RoomList.Parsed(primary: primary, extras: extras), source: source)
        }
    }

    // MARK: MobileSessionListenerProtocol

    /// The engine established a session: the initial one, or the one after a
    /// hop or a reconnect. Right after a handover the list holds exactly one
    /// live room until it is read again, so every session after the first
    /// refreshes it.
    func onSessionOpened(_ room: String?, sessionID: String?) {
        queue.async { [self] in
            guard running else { return }
            sessions += 1
            // A session is what a restart was for; only now does the backoff
            // start over, so an engine that dies again a second later waits
            // longer each time rather than two seconds every time.
            restartAttempt = 0
            restartNotBefore = .distantPast
            NetworkDiagnostics.record("olcrtc session opened (\(sessions == 1 ? "first of the generation" : "handover or reconnect"))")
            if sessions > 1 || firstSessionRefreshes {
                refresh(reason: "session opened in room \(room.map(RoomDigest.of) ?? "?")")
            }
        }
    }

    // MARK: - Refresh

    private func startTimers() {
        cancelTimers()
        let periodic = DispatchSource.makeTimerSource(queue: queue)
        periodic.schedule(deadline: .now() + Self.periodicRefresh, repeating: Self.periodicRefresh)
        periodic.setEventHandler { [weak self] in self?.refresh(reason: "periodic") }
        periodic.resume()
        self.periodic = periodic

        let watchdog = DispatchSource.makeTimerSource(queue: queue)
        watchdog.schedule(deadline: .now() + Self.watchdogTick, repeating: Self.watchdogTick)
        watchdog.setEventHandler { [weak self] in self?.checkEngine() }
        watchdog.resume()
        self.watchdog = watchdog
    }

    private func cancelTimers() {
        periodic?.cancel()
        periodic = nil
        retry?.cancel()
        retry = nil
        watchdog?.cancel()
        watchdog = nil
    }

    /// Pulls the subscription through the tunnel and hands the engine the rooms
    /// it names now. The request is deliberately not pinned to the physical
    /// interface: inside the extension the default route is our own tun, and
    /// the tun is the only way the list is reachable where this matters.
    private func refresh(reason: String) {
        guard running, !fetching, let parameters else { return }
        guard let urlText = parameters.subscriptionUrl, let url = URL(string: urlText) else {
            NetworkDiagnostics.record("olcrtc rooms: no subscription url, nothing to refresh")
            return
        }
        guard Date().timeIntervalSince(lastRefresh) >= Self.minRefreshInterval else { return }
        lastRefresh = Date()
        fetching = true
        OlcrtcEngine.note("room list refresh (\(reason))")
        var request = URLRequest(url: url)
        request.setValue("text/plain, text/markdown, application/octet-stream, */*", forHTTPHeaderField: "Accept")
        request.setValue(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            forHTTPHeaderField: "User-Agent"
        )
        let task = http.dataTask(with: request) { [weak self] data, response, error in
            self?.queue.async { self?.fetched(data: data, response: response, error: error) }
        }
        task.resume()
    }

    private func fetched(data: Data?, response: URLResponse?, error: Error?) {
        fetching = false
        guard running, let parameters else { return }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard error == nil, (200..<300).contains(status), let data, let body = String(data: data, encoding: .utf8) else {
            OlcrtcEngine.note("room list refresh failed (status \(status)\(error.map { ": \($0.localizedDescription)" } ?? ""))")
            NetworkDiagnostics.record("olcrtc rooms refresh failed status=\(status)")
            scheduleRetry()
            return
        }
        guard let parsed = RoomList.parse(body, keyHex: parameters.keyHex, carrier: parameters.carrierName) else {
            OlcrtcEngine.note("room list refresh: the subscription has no line with this location's carrier and key")
            NetworkDiagnostics.record("olcrtc rooms refresh: carrier+key not in subscription")
            return
        }
        apply(parsed, source: "subscription")
    }

    private func scheduleRetry() {
        retry?.cancel()
        let retry = DispatchSource.makeTimerSource(queue: queue)
        retry.schedule(deadline: .now() + Self.retryAfterFailure)
        retry.setEventHandler { [weak self] in
            self?.lastRefresh = .distantPast
            self?.refresh(reason: "retry")
        }
        retry.resume()
        self.retry = retry
    }

    /// Hands the engine a list. It takes effect at the engine's next hop; the
    /// live session is untouched, which is the whole point of a live update.
    private func apply(_ next: RoomList.Parsed, source: String) {
        guard let parameters else { return }
        if next == rooms {
            OlcrtcEngine.note("room list unchanged (\(next.all.count) rooms, \(source))")
            return
        }
        do {
            try OlcrtcEngine.applyRooms(next)
        } catch {
            OlcrtcEngine.note("room list rejected by the engine: \(error.localizedDescription)")
            return
        }
        rooms = next
        RoomMemory.save(next, carrier: parameters.carrierName, keyHex: parameters.keyHex)
        // Digests, not a bare count: a handover only works when the standby
        // the server advertised is in here, and a count cannot tell "the
        // standby is missing" from "there is one". Not the ids either - see
        // RoomDigest.
        OlcrtcEngine.note("room list refreshed (\(next.all.count) rooms: \(RoomDigest.list(next.all))) - live, no restart")
        NetworkDiagnostics.record("olcrtc rooms refreshed: \(next.all.count) (\(source))")
    }

    // MARK: - Watchdog

    /// An engine whose generation ended - every room it knew was tried once and
    /// none held - is started again over the rooms known now, with a backoff.
    /// hev keeps the tun throughout; apps see their connections fail until the
    /// SOCKS port is back, which is what they would see on Android too.
    private func checkEngine() {
        guard running, !OlcrtcEngine.isRunning, let rooms else { return }
        guard Date() >= restartNotBefore else { return }
        restartAttempt += 1
        let delay = Self.restartBackoff[min(restartAttempt, Self.restartBackoff.count) - 1]
        restartNotBefore = Date().addingTimeInterval(delay)
        OlcrtcEngine.note("engine is down - restart \(restartAttempt) over \(rooms.all.count) rooms")
        NetworkDiagnostics.record("olcrtc engine down; restart \(restartAttempt)")
        sessions = 0
        firstSessionRefreshes = true
        // Read again right before the launch: a stop that landed while the
        // lines above ran must not be followed by an engine that outlives it.
        guard running else { return }
        do {
            try OlcrtcEngine.relaunch(rooms: rooms)
            // The backoff is not reset here but in onSessionOpened: a restart
            // has only worked once a session came of it.
            NetworkDiagnostics.record("olcrtc engine restarted")
        } catch {
            OlcrtcEngine.note("restart \(restartAttempt) failed: \(error.localizedDescription); next in \(Int(delay))s")
        }
    }
}

/// A room id in a log line, as the first eight hex digits of its SHA-256.
///
/// A room id is the address of a meeting: with it a stranger joins the same
/// conference. olcrtc.log and the app's log both go into the diagnostics the
/// user exports and sends, so the id itself never goes there - the engine
/// writes it nowhere either. The digest keeps what the line is for: it is
/// stable, so a standby appearing, a list that did not change, and the app's
/// list matching the extension's are all still visible.
///
/// ai-generated: the whole type.
enum RoomDigest {
    static func of(_ room: String) -> String {
        SHA256.hash(data: Data(room.utf8)).prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    static func list(_ rooms: [String]) -> String {
        rooms.map(of).joined(separator: ", ")
    }
}

/// The last room list this process learned, kept in the App Group so the next
/// start - possibly from an app whose copy is older - can begin with it.
///
/// Keyed by carrier and key, as RoomList groups: one origin's Telemost, WB
/// Stream and SaluteJazz locations share a key, and a list learned under one
/// of them is no use to the others. Which start a record belongs to is decided
/// in RoomMemoryRecord, a file of its own so its test needs neither CryptoKit
/// nor the App Group.
///
/// ai-generated: the whole type.
enum RoomMemory {
    private static let file = FileManager.default
        .containerURL(forSecurityApplicationGroupIdentifier: "group.org.proofkit.app")?
        .appendingPathComponent("olcrtc-rooms.json")

    /// The key is stored as a digest: the file names rooms, which are not
    /// secret on their own, and it must not be a second copy of the key.
    private static func digest(_ keyHex: String) -> String {
        SHA256.hash(data: Data(keyHex.lowercased().utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// `startHasRoomsGroup`: the app's list for this start carries a `##rooms`
    /// group - see RoomMemoryRecord.rooms for why a record from the key-only
    /// build is kept for those starts alone.
    static func load(carrier: String, keyHex: String, startHasRoomsGroup: Bool) -> RoomList.Parsed? {
        guard let file, let data = try? Data(contentsOf: file),
              let record = try? JSONDecoder().decode(RoomMemoryRecord.self, from: data)
        else { return nil }
        return record.rooms(
            carrier: carrier,
            keyDigest: digest(keyHex),
            startHasRoomsGroup: startHasRoomsGroup,
            now: Date().timeIntervalSince1970
        )
    }

    static func save(_ rooms: RoomList.Parsed, carrier: String, keyHex: String) {
        guard let file else { return }
        let record = RoomMemoryRecord(
            key: digest(keyHex),
            carrier: RoomList.normalizedCarrier(carrier),
            primary: rooms.primary,
            extras: rooms.extras,
            at: Date().timeIntervalSince1970
        )
        if let data = try? JSONEncoder().encode(record) {
            try? data.write(to: file, options: .atomic)
        }
    }
}
