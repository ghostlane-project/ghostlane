import Combine
import NetworkExtension
import SwiftUI
import SharedUI
import UIKit
import os

@main
struct OlcboxIosApp: App {
    private let platformBridge: SwiftPlatformBridge
    private let olcRtcBridge: SwiftOlcRtcManager
    private let packetTunnelBridge: SwiftPacketTunnelBridge
    private let appSession: IosAppSession

    init() {
        let platformBridge = SwiftPlatformBridge()
        let olcRtcBridge = SwiftOlcRtcManager()
        let packetTunnelBridge = SwiftPacketTunnelBridge()
        self.platformBridge = platformBridge
        self.olcRtcBridge = olcRtcBridge
        self.packetTunnelBridge = packetTunnelBridge
        self.appSession = IosAppFactory().createSession(
            platformBridge: platformBridge,
            olcRtcBridge: olcRtcBridge,
            packetTunnelBridge: packetTunnelBridge
        )
        // What this process holds, on the transitions that matter. The
        // extension is not what runs out of memory; the device is, and this
        // app is the largest thing on it that we control. See AppMemoryWatch.
        AppMemoryWatch.start()
    }

    var body: some Scene {
        WindowGroup {
            ComposeHostView(
                platformBridge: platformBridge,
                appSession: appSession
            )
            .ignoresSafeArea()
            // The one-tap import link: proofkit://add?url=… by scheme, and
            // https://proofkit.org/add#… as a universal link. Both end in the
            // same import a paste goes through.
            .onOpenURL { url in
                appSession.handleIncomingLink(uri: url.absoluteString)
            }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                if let url = activity.webpageURL {
                    appSession.handleIncomingLink(uri: url.absoluteString)
                }
            }
        }
    }
}

private struct ComposeHostView: UIViewControllerRepresentable {
    let platformBridge: SwiftPlatformBridge
    let appSession: IosAppSession

    func makeUIViewController(context: Context) -> UIViewController {
        let session = appSession
        let host = ComposeSceneHost(
            makeCompose: { session.createViewController() },
            collectGarbage: { session.collectGarbage() }
        )
        platformBridge.presenter = host
        return host
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        platformBridge.presenter = uiViewController
    }
}

/// Hosts the Compose view controller and lets go of it while the app is in
/// the background.
///
/// olcbox#24, measured on 1.0.420 with the delayed samples: in the background
/// the app drops from about 260 MB to about 200 MB within three seconds and
/// then stays there. The extension that carries the tunnel is killed by the
/// system at 45 MB during a speed test with 5 MB of its own allowance unused,
/// after a burst of `memory pressure critical` — and while it dies this app is
/// the largest thing on the phone that we control, holding those 200 MB of
/// Skia surfaces, drawables and Metal driver memory behind a screen nobody is
/// looking at.
///
/// 1.0.423 took the Compose view out of the window and kept the controller,
/// counting on Compose Multiplatform 1.12 to dispose the scene — it does, when
/// its view leaves the window — and on that disposal to free the graphics. The
/// next log said it did not: the delayed samples read 180–215 MB, the same as
/// before, while the *foreground* sample after a long background stretch twice
/// read 122–128 MB. So the memory does go, only not when the scene is
/// disposed: the Kotlin objects that own the Metal layer, the Skia context and
/// the drawables are unreachable from then on but stay allocated until
/// Kotlin/Native's collector runs, and a backgrounded app that allocates
/// nothing gives it no reason to. The foreground allocations of the new scene
/// were what finally triggered it, which is why the drop showed up there.
///
/// So now the controller itself is dropped in the background and built afresh
/// on return, and the Kotlin/Native collector is asked to run twice after the
/// drop: once after CMP's half-second drawable drain, once more just before
/// the +3 s sample, so the export answers whether this was the reason.
/// Plain `remember {}` state does not survive, as before: the app returns on
/// its home screen with any sheet closed, which is also where it starts. The
/// view models live outside the composition and keep everything that matters.
///
/// A snapshot of the last frame stands in for the view meanwhile, so the app
/// switcher and the first moment after returning show what was there.
final class ComposeSceneHost: UIViewController {
    private let makeCompose: () -> UIViewController
    private let collectGarbage: () -> Void
    private var compose: UIViewController?
    private var placeholder: UIView?
    // Written once in viewDidLoad on the main actor and read by deinit, which
    // is nonisolated and may not touch main-actor state of a non-Sendable
    // type; the tokens are only ever handed back to NotificationCenter.
    nonisolated(unsafe) private var observers: [NSObjectProtocol] = []

    /// Seconds after the drop at which the collector is asked to run. The
    /// first is past CMP's 500 ms drawable drain; the second lands before the
    /// app's +3 s memory sample so that sample reflects the collection; the
    /// third, before the +8 s one, catches what the Metal driver released
    /// late. All inside the background task the memory watch holds.
    private static let collectAfter: [TimeInterval] = [0.7, 2.5, 5.0, 10.0, 20.0]

    init(makeCompose: @escaping () -> UIViewController, collectGarbage: @escaping () -> Void) {
        self.makeCompose = makeCompose
        self.collectGarbage = collectGarbage
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        attachCompose()
        let centre = NotificationCenter.default
        observers = [
            centre.addObserver(
                forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil
            ) { [weak self] _ in MainActor.assumeIsolated { self?.detachCompose() } },
            centre.addObserver(
                forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil
            ) { [weak self] _ in MainActor.assumeIsolated { self?.attachCompose() } },
        ]
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    private func attachCompose() {
        guard compose == nil else { return }
        let compose = makeCompose()
        self.compose = compose
        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // A container that moves a child's view by hand owes the child its
        // appearance callbacks; UIKit forwards them only for its own
        // transitions. CMP's controller acts on them (sceneDidAppear).
        compose.beginAppearanceTransition(true, animated: false)
        // Under the snapshot, which stays on top until the scene has drawn.
        view.insertSubview(compose.view, at: 0)
        compose.endAppearanceTransition()
        compose.didMove(toParent: self)
        guard let placeholder else { return }
        self.placeholder = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            placeholder.removeFromSuperview()
        }
    }

    /// Removes the overlay Compose Multiplatform leaves behind for its
    /// dialogs and sheets.
    ///
    /// CMP 1.12 does not draw dialogs, popups and bottom sheets inside the
    /// Compose view: `ComposeLayersViewController` embeds a `ComposeLayersView`
    /// with its own Metal view into the view directly under the window, so
    /// that it covers everything. `disposeComposeScene` drops the holder that
    /// owns that controller but never removes the view, and dropping our
    /// child controller cannot reach it either. So going to the background
    /// with a sheet open left a Metal-backed full-screen view in the window,
    /// and the 1.0.426 samples say exactly that: 70-95 MB floors after
    /// backgrounding from the home screen, 146-147 MB after backgrounding
    /// from the transport picker - and nothing frees it later, so the floor
    /// climbs a cycle at a time. Found by class name, since the view is not
    /// ours; what was removed is written to the app's memory log, so the next
    /// export says whether the name still matches.
    private func removeStrayLayers() {
        guard let window = view.window else { return }
        var top: UIView = view
        while let parent = top.superview, parent !== window { top = parent }
        for stray in top.subviews where !view.isDescendant(of: stray) {
            let name = NSStringFromClass(type(of: stray))
            // Kotlin/Native mangles the class name (a framework or package
            // prefix around ComposeLayersView), so the match is loose; the
            // full name is logged either way.
            let lowered = name.lowercased()
            guard lowered.contains("layersview") || lowered.contains("compose") else {
                AppMemoryWatch.note("kept a stranger under the window: \(name) subviews=\(stray.subviews.count)")
                continue
            }
            stray.removeFromSuperview()
            AppMemoryWatch.note("removed the layers overlay: \(name)")
        }
    }

    private func detachCompose() {
        guard let compose else { return }
        if let snapshot = compose.view.snapshotView(afterScreenUpdates: false) {
            snapshot.frame = view.bounds
            snapshot.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            placeholder?.removeFromSuperview()
            view.addSubview(snapshot)
            placeholder = snapshot
        }
        compose.willMove(toParent: nil)
        // Without this pair the child never hears viewWillDisappear or
        // viewDidDisappear: UIKit sends them for its own transitions, not
        // for a view a container removes by hand. CMP's hosting controller
        // stops the scene's rendering in the first and checks its window
        // containment after the second, so a detach without them left the
        // disposal to whatever the view's didMoveToWindow triggered - which
        // is one reading of the 1.0.427 floors varying between 98 and 203 MB
        // from one background to the next.
        compose.beginAppearanceTransition(false, animated: false)
        compose.view.removeFromSuperview()
        compose.endAppearanceTransition()
        compose.removeFromParent()
        self.compose = nil
        removeStrayLayers()
        let collect = collectGarbage
        for delay in Self.collectAfter {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { collect() }
        }
    }
}

@MainActor
final class PacketTunnelController: ObservableObject {

    /// Must match the extension target's bundle identifier exactly; iOS silently
    /// finds nothing to launch if it does not.
    private static let providerBundleId = "org.proofkit.app.PacketTunnel"

    @Published private(set) var status: String = "not loaded"

    /// The last status the system itself reported, readable without hopping to
    /// the main actor.
    ///
    /// The watchdog asks "is the tunnel up?" from Kotlin's own thread, and a flag
    /// the app sets when it *requests* a tunnel cannot answer that: an extension
    /// killed for exceeding its memory cap never tells anyone, and the app went
    /// on showing "connected" over a tunnel the system had already written off.
    nonisolated(unsafe) private(set) static var systemConnected = false

    /// When the system says the running tunnel was established, in epoch
    /// milliseconds, or 0 when nothing is up.
    nonisolated(unsafe) private(set) static var systemConnectedSinceMs: Int64 = 0

    private var manager: NETunnelProviderManager?
    // Touched from deinit, which is not actor-isolated, so it cannot be either.
    private nonisolated(unsafe) var observer: NSObjectProtocol?
    private nonisolated(unsafe) var foregroundObserver: NSObjectProtocol?

    private let log = Logger(subsystem: "org.proofkit.app", category: "tunnel-controller")

    init() {
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let connection = note.object as? NEVPNConnection else { return }
            // NEVPNConnection is not Sendable; its status is a plain enum. Read
            // it here rather than carrying the connection into the task.
            let text = Self.describe(connection.status)
            Self.adopt(connection.status, connectedDate: connection.connectedDate)
            Task { @MainActor in self?.status = text }
        }

        // A notification is only ever a *change*, and the changes that matter
        // most happen while this process is not running to hear them. See
        // `syncFromSystem`.
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in await self?.syncFromSystem() }
        }

        Task { @MainActor [weak self] in await self?.syncFromSystem() }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver) }
    }

    /// Records what the system is doing, for readers that cannot hop to the
    /// main actor.
    private nonisolated static func adopt(_ status: NEVPNStatus, connectedDate: Date?) {
        // `.reasserting` is the system rebuilding the tunnel underneath us after
        // a network change — the tunnel is still ours and still up. Counting it
        // as down is how the watchdog came to tear down a perfectly healthy
        // tunnel on an ordinary Wi-Fi↔cellular handover.
        let up = status == .connected || status == .reasserting
        systemConnected = up
        systemConnectedSinceMs = up
            ? Int64(((connectedDate ?? Date()).timeIntervalSince1970 * 1000).rounded())
            : 0
    }

    /// Adopts whatever the system is doing right now, rather than waiting to be
    /// told about the next change.
    ///
    /// Two things make this necessary rather than merely tidy. A tunnel outlives
    /// the app: iOS suspends and then terminates a backgrounded app while the
    /// extension keeps carrying traffic, so a launch begins with no idea that a
    /// VPN is up. And `NEVPNStatusDidChange` is only delivered for a manager
    /// this process has loaded — before the first `loadAllFromPreferences()`
    /// the app is deaf to it entirely, so "connected" was never missed so much
    /// as never observable. Between them, the app reported itself disconnected
    /// over a working VPN, and the Kotlin watchdog read that as a dead tunnel.
    func syncFromSystem() async {
        if manager == nil {
            manager = try? await NETunnelProviderManager.loadAllFromPreferences().first
        }
        guard let manager else { return }
        let connection = manager.connection
        Self.adopt(connection.status, connectedDate: connection.connectedDate)
        status = Self.describe(connection.status)
    }

    /// Creates the VPN configuration if it is missing. The first save is what
    /// raises the system's "allow VPN configuration" prompt.
    func prepare() async {
        do {
            let existing = try await NETunnelProviderManager.loadAllFromPreferences()
            let manager = existing.first ?? NETunnelProviderManager()

            let proto = NETunnelProviderProtocol()
            proto.providerBundleIdentifier = Self.providerBundleId
            // Shown in Settings → VPN. Not a real address; the tunnel decides
            // where it goes once a transport exists.
            proto.serverAddress = "ProofKit"

            manager.protocolConfiguration = proto
            manager.localizedDescription = "ProofKit"
            manager.isEnabled = true

            try await manager.saveToPreferences()
            // Reloading after a save is not optional: starting from the in-memory
            // object that was just saved fails with a stale-configuration error.
            try await manager.loadFromPreferences()

            self.manager = manager
            Self.adopt(manager.connection.status, connectedDate: manager.connection.connectedDate)
            status = Self.describe(manager.connection.status)
            log.info("configuration ready")
        } catch {
            status = "prepare failed: \(error.localizedDescription)"
            log.error("prepare failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func start() async {
        // Also when it is present but disabled: `syncFromSystem` loads whatever
        // is in preferences so the app can see a running tunnel, and what it
        // loads may be a configuration the user switched off in Settings.
        // Starting that throws, where `prepare` re-enables and saves it.
        if manager == nil || manager?.isEnabled != true { await prepare() }
        guard let manager else { return }
        do {
            try manager.connection.startVPNTunnel()
            log.info("startVPNTunnel requested")
        } catch {
            status = "start failed: \(error.localizedDescription)"
            log.error("start failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
        log.info("stopVPNTunnel requested")
    }

    /// Waits for the system to let go of the previous tunnel.
    ///
    /// `stopVPNTunnel()` only queues the request, and a start issued while the
    /// old tunnel is still tearing down races it: the extension is asked to
    /// establish while the system is still dismantling the one before, and what
    /// comes out is a tunnel that reaches the relay and then dies. Returns as
    /// soon as the system reports it down, or after `timeout` — a start attempt
    /// that is merely late beats one that never happens.
    func waitUntilDown(timeout: TimeInterval = 5) async {
        guard let manager else { return }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            switch manager.connection.status {
            case .disconnected, .invalid:
                return
            default:
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
        }
        log.error("tunnel did not report itself down within \(timeout, privacy: .public)s")
    }

    /// Waits for the system to say the tunnel is actually up.
    ///
    /// `startVPNTunnel()` only queues the request: it returns without error for
    /// a tunnel that is about to die, and an extension that throws out of
    /// `startTunnel` reports nothing back to the app at all. Without this the
    /// app said "connected" over a dead tunnel — which is exactly how a
    /// transport sing-box could not even parse went unnoticed.
    ///
    /// Returns nil on success, or a description of what went wrong.
    /// 45s, which has to stay clear of the extension's own budget: olcRTC may
    /// now spend twenty seconds reaching its SFU, and sing-box still has to
    /// start behind it. Give up first and the app reports a failure over a
    /// tunnel that was seconds from coming up.
    func waitUntilUp(timeout: TimeInterval = 45) async -> String? {
        guard let manager else { return "no VPN configuration" }
        var sawAttempt = false
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            switch manager.connection.status {
            case .connected:
                return nil
            case .connecting, .reasserting:
                sawAttempt = true
            case .disconnected, .invalid:
                // Only after an attempt began: the status is still
                // `disconnected` for a moment after the request is queued.
                if sawAttempt { return Self.lastStage() ?? "the tunnel stopped right after starting" }
            case .disconnecting:
                break
            @unknown default:
                break
            }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        return Self.lastStage() ?? "timed out waiting for the tunnel"
    }

    /// Written by the app just before it asks for a tunnel, so a stage read back
    /// afterwards is known to belong to this attempt. See `start(request:)`.
    ///
    /// `nonisolated` because the bridge writes it from Kotlin's thread, and this
    /// class is `@MainActor`.
    nonisolated static let requestedStage = "requested by the app"

    /// The extension's own breadcrumb, which is the only thing that knows *why*
    /// a start failed — the system tells the app nothing but "disconnected".
    private static func lastStage() -> String? {
        let stage = shared("stage.txt")

        // Our own sentinel, untouched: the extension never wrote a line, so it
        // never ran. Nothing further can be said from here, and saying it plainly
        // beats reporting a stage from some earlier run as if it were this one.
        if stage == requestedStage {
            return "the tunnel extension did not start — check the VPN profile in Settings"
        }

        // A stage that says `failed` explains itself — as far as it goes. What
        // it never carries is the engine's own account: "olcRTC start timed out"
        // says a WebRTC session did not come up in eight seconds and nothing
        // whatever about why. The tail of the engine log does, and it is the
        // difference between a bug report and a shrug.
        if let stage, stage.hasPrefix("failed") {
            guard let tail = engineLogTail() else { return stage }
            return "\(stage)\n\(tail)"
        }

        // The harder case, and until now the silent one: the extension reached
        // a perfectly good stage — `ready`, carrying traffic — and was then
        // killed from outside. The stage cannot explain that, so it was
        // reported as the useless "stopped right after starting". The memory
        // trace can: it says how much headroom was left at the last sample
        // before the process disappeared. Near zero means the ceiling; wide
        // open means the kill had nothing to do with memory.
        if let stage, let memory = lastMemorySample() {
            return "died at stage '\(stage)' — \(memory)"
        }
        return nil
    }

    /// The last few lines the engines wrote about themselves.
    ///
    /// `olcrtc.log` first, because olcRTC is the engine that fails on its own —
    /// it has to cross the internet to an SFU before it reports ready, and it
    /// does not write to stderr at all: it is given a log writer, so redirecting
    /// stderr never captured a word of it. sing-box and Xray do use stderr, and
    /// `engine.log` is theirs.
    ///
    /// Trimmed hard on purpose: this goes on a phone screen under a status pill,
    /// not into a log viewer, and the lines that matter are always the last ones.
    private static func engineLogTail(lines: Int = 4) -> String? {
        guard let log = shared("olcrtc.log") ?? shared("engine.log") else { return nil }
        let tail = log
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .suffix(lines)
        return tail.isEmpty ? nil : tail.joined(separator: "\n")
    }

    /// Last line of the extension's memory trace. See MemoryWatch.
    private static func lastMemorySample() -> String? {
        shared("memory.txt")?
            .split(separator: "\n")
            .last
            .map { $0.trimmingCharacters(in: .whitespaces) }
    }

    private static func shared(_ name: String) -> String? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: "group.org.proofkit.app"
        ) else { return nil }
        return try? String(
            contentsOf: container.appendingPathComponent(name), encoding: .utf8
        )
    }

    private nonisolated static func describe(_ status: NEVPNStatus) -> String {
        switch status {
        case .invalid: return "invalid"
        case .disconnected: return "disconnected"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .reasserting: return "reasserting"
        case .disconnecting: return "disconnecting"
        @unknown default: return "unknown"
        }
    }
}

/// The app's half of starting the packet tunnel.
///
/// Kotlin decides *what* to run and hands over a finished config; only the app
/// can ask the system to launch the extension, so that part lives here. The
/// config goes through the App Group because a tunnel provider is a separate
/// process — there is no argument to pass it.
final class SwiftPacketTunnelBridge: NSObject, @unchecked Sendable, IosPacketTunnelBridge {

    /// Main-actor bound, so it cannot be a stored property of this class — the
    /// bridge is called from Kotlin's background dispatcher and is deliberately
    /// not isolated itself.
    @MainActor private static let controller = PacketTunnelController()

    private static let appGroupId = "group.org.proofkit.app"

    override init() {
        super.init()
        // Force the controller into existence at launch.
        //
        // A static `let` is lazy, and the two things that read it from Kotlin —
        // `isRunning` and `connectedSinceEpochMs` — reach a static *variable*
        // without ever touching the instance. Nothing else creates it until the
        // first START, so the observers it registers, and the question it asks
        // the system about what is already running, would not happen until the
        // tap this exists to make unnecessary.
        Task { @MainActor in _ = Self.controller }
    }

    func start(request: IosPacketTunnelStartRequest, callback: IosBridgeCallback) {
        let answer = SendableCallback(callback: callback)

        // The Simulator has no Network Extension. `saveToPreferences` fails
        // there, so the manager stays nil and the app reported "no VPN
        // configuration" — which reads as something missing that could be
        // supplied, and sends whoever sees it looking for it. Nothing is
        // missing; the platform does not have the feature.
        #if targetEnvironment(simulator)
        answer.callback.onResult(result: IosBridgeResult(
            success: false,
            message: "The VPN cannot run in the Simulator — iOS has no Network "
                + "Extension there. Build to a device."
        ))
        return
        #endif

        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroupId
        ) else {
            answer.callback.onResult(
                result: IosBridgeResult(success: false, message: "app group unavailable")
            )
            return
        }

        do {
            try Data(request.config.utf8)
                .write(to: container.appendingPathComponent("config.json"))
            // Written or removed, never left behind: a stale file from a previous
            // connection would start a core behind a tunnel that does not use one.
            try Self.handOver(
                request.xrayConfig,
                to: container.appendingPathComponent("xray.json")
            )
            try Self.handOver(
                Self.olcrtcParameters(request.olcrtc),
                to: container.appendingPathComponent("olcrtc.json")
            )
            // Written or removed, like the two above: a `rules/` left behind
            // by a Bypass Russia connection is harmless to a Global config,
            // which never names it, but a config that names a file the app
            // failed to write stops libbox with "no such file" — so the write
            // happens here, before the extension is asked for anything.
            try Self.handOverRuleSets(
                request.ruleSets,
                into: container.appendingPathComponent("libbox/work/rules", isDirectory: true)
            )
            // Claim the breadcrumb before the extension is asked to run.
            //
            // `stage.txt` outlives the process that wrote it, and the app now
            // *shows* it. Without this, an extension that never gets as far as
            // its own first line leaves the previous attempt's stage on screen —
            // a failure reported in detail, belonging to a run that is over. If
            // this sentinel is still there afterwards, the extension wrote
            // nothing at all, which is itself the most useful thing to say.
            try Data(PacketTunnelController.requestedStage.utf8)
                .write(to: container.appendingPathComponent("stage.txt"))
        } catch {
            answer.callback.onResult(result: IosBridgeResult(
                success: false,
                message: "could not hand over the config: \(error.localizedDescription)"
            ))
            return
        }

        // Nothing waits here: the tunnel takes seconds to settle and the answer
        // goes back through the callback once the system has actually decided,
        // instead of holding a coroutine thread on a semaphore for the duration.
        Task { @MainActor in
            // Starting an already-running tunnel does nothing at all, and the
            // extension keeps the config it was launched with — which looks
            // exactly like a working VPN that does not change your IP.
            //
            // What "already running" means has to come from the system. It used
            // to come from a flag this object set when it last started a tunnel
            // itself, which is false on every fresh launch — including a launch
            // over a tunnel that is very much running, now that the app adopts
            // one. The old tunnel then survived the start, or, worse, the stop
            // Kotlin had already sent landed midway through it.
            Self.controller.stop()
            // Waited for, not slept through. A fixed 700 ms was a guess about
            // how long a teardown takes; this asks.
            await Self.controller.waitUntilDown()
            await Self.controller.start()
            let reason = await Self.controller.waitUntilUp()
            answer.callback.onResult(
                result: IosBridgeResult(success: reason == nil, message: reason)
            )
        }
    }

    /// Writes the file, or removes it when there is nothing to write.
    private static func handOver(_ contents: String?, to url: URL) throws {
        if let contents, !contents.isEmpty {
            try Data(contents.utf8).write(to: url)
        } else if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    /// Writes each file, or removes the directory when there is nothing to write.
    /// The directory is libbox's working path plus the relative directory the
    /// Kotlin config uses (`RuleSets.IOS_RELATIVE_DIR`); the two must agree.
    private static func handOverRuleSets(_ files: [String: String], into directory: URL) throws {
        let fileManager = FileManager.default
        if files.isEmpty {
            if fileManager.fileExists(atPath: directory.path) {
                try fileManager.removeItem(at: directory)
            }
            return
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        for (name, base64) in files {
            guard let data = Data(base64Encoded: base64) else {
                throw NSError(
                    domain: "org.proofkit.app", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "rule-set \(name) is not base64"]
                )
            }
            try data.write(to: directory.appendingPathComponent(name))
        }
    }

    /// olcRTC is addressed by parameters rather than by a config document, so it
    /// crosses to the extension as JSON — the shape `OlcrtcEngine.Parameters`
    /// decodes on the other side.
    private static func olcrtcParameters(_ request: IosOlcRtcStartRequest?) -> String? {
        guard let request else { return nil }
        let fields: [String: Any] = [
            "carrierName": request.carrierName,
            "transportName": request.transportName,
            "roomId": request.roomId,
            "clientId": request.clientId,
            "keyHex": request.keyHex,
            "socksPort": Int(request.socksPort),
            "socksUser": request.socksUser,
            "socksPass": request.socksPass,
            "vp8Fps": Int(request.vp8Fps),
            "vp8BatchSize": Int(request.vp8BatchSize),
            "directRules": request.directRules
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: fields) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func stop() {
        Task { @MainActor in Self.controller.stop() }
    }

    /// What the system says, not what we asked for. See `systemConnected`.
    func isRunning() -> Bool { PacketTunnelController.systemConnected }

    /// See `systemConnectedSinceMs`. Zero when nothing is up, which Kotlin
    /// reads as "no session".
    func connectedSinceEpochMs() -> Int64 { PacketTunnelController.systemConnectedSinceMs }

    func tunnelBytesIn() -> Int64 { TunnelCounters.read().bytesIn }

    func tunnelBytesOut() -> Int64 { TunnelCounters.read().bytesOut }

    func icmpLatencyMs(host: String, timeoutMillis: Int64) -> Int64 {
        IcmpProbe.measure(host: host, timeout: TimeInterval(timeoutMillis) / 1000)
    }

    /// The engine's stderr from the run that ended, trimmed to the part that
    /// explains it.
    ///
    /// A Go crash writes its reason first and then a stack for every goroutine,
    /// which on a tunnel carrying a speed test is hundreds of them. So the tail
    /// of the file is the least useful part of it: the reason is at the top of
    /// the crash, and that is what gets kept.
    private static func lastRunStderr(container: URL) -> String? {
        guard let text = try? String(
            contentsOf: container.appendingPathComponent("engine.log.old"), encoding: .utf8
        ) else { return nil }

        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let markers = ["panic:", "fatal error:", "signal SIG", "runtime: out of memory"]
        let crash = lines.firstIndex { line in markers.contains { line.contains($0) } }

        let kept: [String]
        let title: String
        if let crash {
            kept = Array(lines[crash...].prefix(120))
            title = "--- engine stderr, the run that ended: IT CRASHED ---"
        } else {
            kept = Array(lines.suffix(40))
            title = "--- engine stderr, the run that ended: no crash recorded ---"
        }
        let body = kept.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return body.isEmpty ? nil : title + "\n" + body.joined(separator: "\n")
    }

    /// The diagnostics of the run that was killed, titled with when it was
    /// last written so an old death is not mistaken for today's.
    private static func deadRunTrace(container: URL, name: String) -> String? {
        let url = container.appendingPathComponent(name)
        guard let text = try? String(contentsOf: url, encoding: .utf8),
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        var when = "date unknown"
        if let modified = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.modificationDate] as? Date {
            let formatter = ISO8601DateFormatter()
            when = "last written " + formatter.string(from: modified)
        }
        return "--- tunnel diagnostics: THE RUN THAT DIED, killed without stopTunnel (\(when); kept until the next death) ---\n"
            + text + "--- end of the run that died ---"
    }

    func engineLog() -> String {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroupId
        ) else { return "" }
        // olcRTC's first, since it is the engine that fails on its own; the
        // other two write to stderr, which lands in engine.log.
        // The previous run's trace first: its last line is why the tunnel
        // stopped, and that run is the one worth reading.
        // The run that died first, ahead of the previous and the current one.
        // The extension keeps a run that ended without stopTunnel under the
        // `-crash` name, through every clean restart after it, because the
        // reconnect and a couple of transport switches were enough to rotate
        // the interesting trace out of `-prev` before anyone exported it.
        var both: [String] = []
        if let died = Self.deadRunTrace(container: container, name: "network-diagnostics-crash.log") {
            both.append(died)
        }
        both += [
            "network-diagnostics-prev.log", "network-diagnostics.log", "olcrtc.log", "engine.log",
        ].compactMap { name -> String? in
            try? String(
                contentsOf: container.appendingPathComponent(name), encoding: .utf8
            )
        }

        // First, ahead of everything: why the last run ended, when it said so.
        //
        // libbox points Go's crash output at engine.log — `debug.SetCrashOutput`
        // in its RedirectStderr — and on the next start renames the previous
        // file to engine.log.old. A panic or a fatal runtime error that killed
        // the extension is therefore in `.old` by the time anyone exports a log,
        // and this reader has never opened that name. 1.0.411 died with 10.9 MB
        // of headroom, which rules out the memory allowance and leaves a crash
        // as the thing to look for; the trace was on the phone the whole time.
        if let ended = Self.lastRunStderr(container: container) {
            both.insert(ended, at: 0)
        }
        // The memory trace, when MemoryWatch is on. Each line names the peak as
        // well as the current footprint, so a short tail says whether the
        // process was climbing toward the ceiling.
        //
        // The previous run comes first and matters more: a provider that was
        // killed is followed by a restart, so by the time anyone exports a log
        // the live trace describes a healthy new process and the dead one is
        // only in memory-prev.txt.
        // The app's own trace is longer: a foreground tick every few seconds
        // (written when the figure moves), so its tail has to reach back far
        // enough to show a curve, not a point.
        for (name, title, tail) in [
            ("app-memory.txt", "--- app memory: transitions, foreground ticks, delayed background samples ---", 80),
            ("memory-crash.txt", "--- extension memory: THE RUN THAT DIED, killed without stopTunnel (kept until the next death) ---", 20),
            ("memory-prev.txt", "--- extension memory (the previous run; its last line says whether it stopped cleanly) ---", 20),
            ("memory.txt", "--- extension memory (current run, or the last one if the tunnel is down) ---", 20),
            ("goroutines.txt", "--- extension goroutines, one line a minute: what the count is made of (olcbox#26) ---", 12),
        ] {
            guard let text = try? String(
                contentsOf: container.appendingPathComponent(name), encoding: .utf8
            ) else { continue }
            let tail = text
                .split(separator: "\n")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
                .suffix(tail)
            if !tail.isEmpty {
                both.append(title + "\n" + tail.joined(separator: "\n"))
            }
        }

        #if DEBUG
        // Debug builds also carry sing-box's own log (SingBoxDebugLog in the
        // extension): the last few hundred lines, where the connection being
        // asked about is.
        if let singBox = try? String(
            contentsOf: container.appendingPathComponent("libbox/work/sing-box.log"), encoding: .utf8
        ) {
            let tail = singBox.split(separator: "\n", omittingEmptySubsequences: false).suffix(400)
            both.append("--- sing-box (debug build) ---\n" + tail.joined(separator: "\n"))
        }
        #endif
        return both.joined(separator: "\n")
    }
}

/// Bytes carried by our tun, read from the interface itself.
///
/// The tunnel lives in another process and libbox does not report to the app:
/// its command server is deliberately never started, and `NEVPNConnection` has
/// no byte counters at all. The kernel's per-interface counters are visible to
/// every process, so the app can simply read them — and because the interface is
/// created when the tunnel comes up, "since the interface appeared" is exactly
/// "this session".
/// Records what the *app* holds: at the transitions that matter, on a slow
/// clock while it is in the foreground, and a few seconds after it has gone
/// to the background.
///
/// The extension is not what runs out of memory. 1.0.416 died 0.1 s after the
/// device reported critical memory pressure, at 34.7 MB of footprint with
/// 15.3 MB of its own allowance still unused, its collector idle and its heap
/// at 17 of 40 MB. Nothing in it was misbehaving; the phone ran short and a
/// packet tunnel provider is an early choice when that happens.
///
/// So the question moved to what else on the phone is holding memory, and the
/// largest thing we control is this process. Instruments measured 136 MB here
/// at rest, of which 119 MB is anonymous VM and 44 MB is IOSurface — graphics,
/// four times the whole extension — and the release build holds 205 MB in the
/// foreground after real use (olcbox#24).
///
/// Two questions decide what to do about it, and both are answered by sampling
/// rather than by reasoning; the two changes made by reasoning on 2026-09-13
/// were both regressions.
///
/// 1. Does any of it go away once the app is really in the background? A
///    sample taken *at* the transition cannot say: the system is rendering the
///    app-switcher snapshot at that moment, which is why the figure there is
///    270 MB. So the transition sample is followed by two delayed ones, made
///    possible by a short background task; after that the app is suspended
///    and cannot sample itself.
/// 2. Does it grow without bound while the app is used? A tick every few
///    seconds in the foreground, written only when the figure moves, turns an
///    ordinary exported log into that curve.
///
/// Every line also carries the memory pressure level as this process receives
/// it, so the same log says whether the pressure the extension dies under is
/// present while this app holds its 200 MB. The memory warning is still only
/// recorded, not acted on: there is nothing to free by reasoning (the heap is
/// 17 MB of the 136), and Compose Multiplatform 1.12 keeps its Skia
/// `DirectContext` private, drains its drawables only when the Metal view
/// leaves the window, and handles no memory warning of its own.
enum AppMemoryWatch {

    private static let appGroup = "group.org.proofkit.app"
    private static let queue = DispatchQueue(label: "org.proofkit.app-memory")
    private static let launched = Date()

    /// Every mutable field below is touched only on `queue`, which is what
    /// makes `nonisolated(unsafe)` true rather than merely quiet.
    nonisolated(unsafe) private static var observers: [NSObjectProtocol] = []
    nonisolated(unsafe) private static var timer: DispatchSourceTimer?
    nonisolated(unsafe) private static var pressure: DispatchSourceMemoryPressure?
    nonisolated(unsafe) private static var pressureLevel = "normal"
    nonisolated(unsafe) private static var inBackground = false
    nonisolated(unsafe) private static var lastWritten: (at: Date, bytes: UInt64)?

    /// Foreground cadence, and how far a tick has to move from the last line
    /// written to earn a line of its own. A heartbeat goes out once a minute
    /// regardless, so a flat curve is visibly flat rather than absent.
    private static let tickInterval: TimeInterval = 5
    private static let tickDelta: UInt64 = 3 * 1_048_576
    private static let tickHeartbeat: TimeInterval = 60

    /// Seconds after `didEnterBackground` to sample again. The app-switcher
    /// snapshot is long done by the first; the second says whether the figure
    /// is still falling. Both fit comfortably inside what a background task
    /// buys, which is about thirty seconds.
    private static let backgroundDelays: [TimeInterval] = [3, 8, 15, 25]

    /// Keeps the file to a few tens of kilobytes: at the foreground cadence
    /// that is well over half an hour of a busy session, far more of a quiet
    /// one.
    private static let window = 400

    static func start() {
        queue.async {
            guard observers.isEmpty else { return }
            let centre = NotificationCenter.default
            observers = [
                centre.addObserver(
                    forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil
                ) { _ in enteredBackground() },
                centre.addObserver(
                    forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil
                ) { _ in enteredForeground() },
                centre.addObserver(
                    forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: nil
                ) { _ in record("memory-warning") },
            ]
            armPressureSource()
            record("launch")
            startTicking()
        }
    }

    /// A line in the app's memory log from elsewhere in the app, with the
    /// footprint of the moment. For the host's account of what it removed.
    static func note(_ event: String) {
        queue.async { record(event) }
    }

    // MARK: Transitions

    private static func enteredBackground() {
        queue.async {
            inBackground = true
            stopTicking()
            record("background")
        }
        // The delayed samples need a process that is still running. A
        // background task holds it for that long; the last sample ends it,
        // and so does the system if it runs out of patience first.
        Task { @MainActor in
            let hold = BackgroundHold()
            guard hold.begin("app-memory") else { return }
            for delay in backgroundDelays {
                queue.asyncAfter(deadline: .now() + delay) {
                    guard inBackground else { return }
                    record("background+\(Int(delay))s")
                }
            }
            let last = backgroundDelays.max() ?? 0
            queue.asyncAfter(deadline: .now() + last + 0.5) {
                Task { @MainActor in hold.end() }
            }
        }
    }

    private static func enteredForeground() {
        queue.async {
            inBackground = false
            record("foreground")
            startTicking()
        }
    }

    /// A background task that its own expiration handler can end.
    @MainActor
    private final class BackgroundHold {
        private var id = UIBackgroundTaskIdentifier.invalid

        func begin(_ name: String) -> Bool {
            id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
                MainActor.assumeIsolated { self?.end() }
            }
            return id != .invalid
        }

        func end() {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
    }

    // MARK: Foreground ticks

    private static func startTicking() {
        guard timer == nil else { return }
        let source = DispatchSource.makeTimerSource(queue: queue)
        source.schedule(deadline: .now() + tickInterval, repeating: tickInterval, leeway: .seconds(1))
        source.setEventHandler { tick() }
        timer = source
        source.resume()
    }

    private static func stopTicking() {
        timer?.cancel()
        timer = nil
    }

    private static func tick() {
        guard !inBackground else { return }
        let bytes = footprintBytes()
        if let last = lastWritten {
            let moved = bytes > last.bytes ? bytes - last.bytes : last.bytes - bytes
            if moved < tickDelta, Date().timeIntervalSince(last.at) < tickHeartbeat { return }
        }
        write(bytes: bytes, event: "tick")
    }

    // MARK: Pressure

    private static func armPressureSource() {
        let source = DispatchSource.makeMemoryPressureSource(
            eventMask: [.normal, .warning, .critical], queue: queue
        )
        source.setEventHandler {
            // Read through the static rather than capturing the source: the
            // handler is owned by the source, and capturing it here is a
            // cycle that outlives cancellation.
            let event = pressure?.data ?? []
            pressureLevel = event.contains(.critical) ? "critical"
                : event.contains(.warning) ? "warning" : "normal"
            record("pressure-\(pressureLevel)")
        }
        pressure = source
        source.resume()
    }

    // MARK: Lines

    /// One line for an event, sampled on `queue` so the level and the last
    /// written figure are read where they are written.
    private static func record(_ event: String) {
        queue.async { write(bytes: footprintBytes(), event: event) }
    }

    private static func write(bytes: UInt64, event: String) {
        lastWritten = (Date(), bytes)
        let stamp = ISO8601DateFormatter().string(from: Date())
        let figures = String(
            format: "%7.1fs  app footprint %6.1f MB",
            Date().timeIntervalSince(launched), Double(bytes) / 1_048_576
        )
        // Padded to a column for the short events; a longer one (the host's
        // notes carry a class name) is written whole. `padding(toLength:)`
        // truncates, which is how the first stranger's name was lost.
        let label = event.count < 16 ? event.padding(toLength: 16, withPad: " ", startingAt: 0) : event
        append("\(stamp)  \(figures)  \(label)  pressure=\(pressureLevel)")
    }

    /// Appends one line to the file the log export reads, keeping the last
    /// `window` lines.
    private static func append(_ line: String) {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup
        ) else { return }
        let file = container.appendingPathComponent("app-memory.txt")
        var lines = (try? String(contentsOf: file, encoding: .utf8))?
            .split(separator: "\n")
            .map(String.init) ?? []
        lines.append(line)
        if lines.count > window { lines.removeFirst(lines.count - window) }
        try? Data((lines.joined(separator: "\n") + "\n").utf8)
            .write(to: file, options: .atomic)
    }

    /// `phys_footprint`, the same figure the system enforces limits against and
    /// the same one the extension's own trace reports, so the two are
    /// comparable.
    private static func footprintBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size
        )
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }
        return info.phys_footprint
    }
}

enum TunnelCounters {

    struct Snapshot {
        let bytesIn: Int64
        let bytesOut: Int64
    }

    /// Must match `LibboxPlatform.Tun.address` in the extension. The two targets
    /// share no code — the extension links Cores, the app does not — so this is
    /// a duplicated constant, and the one place it could drift.
    private static let tunAddress = "172.19.0.1"

    static func read() -> Snapshot {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else {
            return Snapshot(bytesIn: 0, bytesOut: 0)
        }
        defer { freeifaddrs(head) }

        // Two passes over one list: the address that identifies our interface is
        // on the AF_INET entry, the counters are on the AF_LINK entry, and they
        // are different entries with the same name.
        var name: String?
        var entry: UnsafeMutablePointer<ifaddrs>? = first
        while let current = entry {
            defer { entry = current.pointee.ifa_next }
            guard let addr = current.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let ok = getnameinfo(
                addr, socklen_t(addr.pointee.sa_len),
                &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST
            ) == 0
            // Not `String(cString:)`: the array overload is deprecated, and the
            // buffer is fixed-size so it is padded with nulls past the name.
            let text = String(
                decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) },
                as: UTF8.self
            )
            if ok, text == tunAddress {
                name = String(cString: current.pointee.ifa_name)
                break
            }
        }
        guard let interface = name else { return Snapshot(bytesIn: 0, bytesOut: 0) }

        entry = first
        while let current = entry {
            defer { entry = current.pointee.ifa_next }
            guard String(cString: current.pointee.ifa_name) == interface,
                  let addr = current.pointee.ifa_addr,
                  addr.pointee.sa_family == UInt8(AF_LINK),
                  let raw = current.pointee.ifa_data else { continue }
            let data = raw.assumingMemoryBound(to: if_data.self).pointee
            return Snapshot(bytesIn: Int64(data.ifi_ibytes), bytesOut: Int64(data.ifi_obytes))
        }
        return Snapshot(bytesIn: 0, bytesOut: 0)
    }
}

/// One ICMP echo, timed.
///
/// The reason this exists at all: a latency figure for a location the app is
/// not connected to. The cores that speak Reality, Hysteria2 and XHTTP live in
/// the tunnel extension, which runs one location at a time, and they cannot be
/// linked into the app beside olcRTC's framework — two gomobile binds in one
/// binary is fifty duplicate symbols. So there is no way to negotiate with an
/// exit from here in order to time it.
///
/// ICMP measures the path instead of the protocol, which is both less and
/// exactly what a user means by "ping": how far away is that server. Every node
/// checked answered it.
///
/// `SOCK_DGRAM` with `IPPROTO_ICMP` needs no privileges and no entitlement on
/// Darwin — it is what Apple's own SimplePing uses. Two details it also
/// documents: the sender computes the checksum, and a reply read from this
/// socket arrives with its IP header still attached, so the ICMP message starts
/// after it. Replies are matched on sequence and payload rather than on the
/// identifier, which the kernel is free to rewrite.
enum IcmpProbe {

    private static let echoRequestV4: UInt8 = 8
    private static let echoReplyV4: UInt8 = 0
    // ICMPv6 numbers its echo differently, and the kernel — not us — computes
    // its checksum, because that checksum covers a pseudo-header only the stack
    // knows. Apple's SimplePing makes the same split.
    private static let echoRequestV6: UInt8 = 128
    private static let echoReplyV6: UInt8 = 129
    private static let payload = Array("proofkit-latency".utf8)

    /// A resolved address with everything needed to talk to it.
    private struct Target {
        var storage: sockaddr_storage
        var length: socklen_t
        var family: Int32
        var isV6: Bool { family == AF_INET6 }
    }

    nonisolated(unsafe) private static var sequence: UInt16 = 0
    private static let lock = NSLock()

    private static func nextSequence() -> UInt16 {
        lock.lock()
        defer { lock.unlock() }
        sequence &+= 1
        return sequence
    }

    /// Why a measurement did not happen, so the app can say which.
    ///
    /// Distinguishing these is the difference between "that server is down" and
    /// "this phone cannot send an ICMP echo at all" — one is about the network
    /// and the other about the app, and a single nil made them the same event.
    enum Failure: Int64 {
        case unresolved = -2
        case socketRefused = -3
        case sendFailed = -4
        case noReply = -1
    }

    /// Round trip in milliseconds, or a negative [Failure] raw value.
    static func measure(host: String, timeout: TimeInterval) -> Int64 {
        guard let target = resolve(host) else { return Failure.unresolved.rawValue }

        let fd = socket(
            target.family,
            SOCK_DGRAM,
            target.isV6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP
        )
        guard fd >= 0 else { return Failure.socketRefused.rawValue }
        defer { close(fd) }

        var tv = timeval(
            tv_sec: Int(timeout),
            tv_usec: Int32((timeout - Double(Int(timeout))) * 1_000_000)
        )
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        let seq = nextSequence()
        let request = echoPacket(sequence: seq, isV6: target.isV6)

        let started = Date()
        let sent = request.withUnsafeBufferPointer { buffer -> Int in
            var addr = target.storage
            return withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    sendto(fd, buffer.baseAddress, buffer.count, 0, sa, target.length)
                }
            }
        }
        guard sent == request.count else { return Failure.sendFailed.rawValue }

        // Somebody else's reply can arrive on this socket, so read until ours
        // does or the clock runs out. SO_RCVTIMEO bounds each read; the deadline
        // bounds the whole thing.
        var reply = [UInt8](repeating: 0, count: 1024)
        while Date().timeIntervalSince(started) < timeout {
            let read = reply.withUnsafeMutableBufferPointer { buffer in
                recv(fd, buffer.baseAddress, buffer.count, 0)
            }
            if read <= 0 { return Failure.noReply.rawValue }
            if matches(reply, count: read, sequence: seq, isV6: target.isV6) {
                return Int64(Date().timeIntervalSince(started) * 1000)
            }
        }
        return Failure.noReply.rawValue
    }

    private static func echoPacket(sequence: UInt16, isV6: Bool) -> [UInt8] {
        var packet = [UInt8](repeating: 0, count: 8 + payload.count)
        packet[0] = isV6 ? echoRequestV6 : echoRequestV4
        packet[1] = 0
        // 2..3 is the checksum, left zero while it is computed.
        packet[4] = 0
        packet[5] = 0
        packet[6] = UInt8(sequence >> 8)
        packet[7] = UInt8(sequence & 0xFF)
        for (i, byte) in payload.enumerated() { packet[8 + i] = byte }

        // Left at zero for ICMPv6 on purpose: its checksum covers a pseudo-header
        // built from the source address, which is chosen by the kernel after this
        // returns. The stack fills it in; computing one here would be wrong twice
        // over, and a wrong checksum is discarded silently by the far end.
        if !isV6 {
            let sum = checksum(packet)
            packet[2] = UInt8(sum >> 8)
            packet[3] = UInt8(sum & 0xFF)
        }
        return packet
    }

    /// The internet checksum: one's complement of the one's complement sum of
    /// the message read as 16-bit big-endian words.
    private static func checksum(_ bytes: [UInt8]) -> UInt16 {
        var total: UInt32 = 0
        var index = 0
        while index + 1 < bytes.count {
            total += UInt32(bytes[index]) << 8 | UInt32(bytes[index + 1])
            index += 2
        }
        if index < bytes.count { total += UInt32(bytes[index]) << 8 }
        while total >> 16 != 0 { total = (total & 0xFFFF) + (total >> 16) }
        return UInt16(truncatingIfNeeded: ~total)
    }

    private static func matches(
        _ reply: [UInt8], count: Int, sequence: UInt16, isV6: Bool
    ) -> Bool {
        guard count > 0 else { return false }
        // Darwin hands the IPv4 socket the IP header along with the reply, which
        // is what SimplePing documents and skips; the IPv6 one starts at the
        // ICMP message. Both offsets are tried for v4 anyway: the alternative is
        // a probe that silently never matches on a platform detail, and trying
        // twice costs a comparison.
        var offsets = [0]
        if !isV6, reply[0] >> 4 == 4 { offsets.insert(Int(reply[0] & 0x0F) * 4, at: 0) }
        return offsets.contains {
            icmpEcho(reply, count: count, at: $0, sequence: sequence, isV6: isV6)
        }
    }

    private static func icmpEcho(
        _ reply: [UInt8], count: Int, at offset: Int, sequence: UInt16, isV6: Bool
    ) -> Bool {
        guard count >= offset + 8 + payload.count else { return false }
        guard reply[offset] == (isV6 ? echoReplyV6 : echoReplyV4) else { return false }
        let seq = UInt16(reply[offset + 6]) << 8 | UInt16(reply[offset + 7])
        guard seq == sequence else { return false }
        return Array(reply[(offset + 8)..<(offset + 8 + payload.count)]) == payload
    }

    /// Whatever family this network can actually reach.
    ///
    /// `AF_UNSPEC`, never `AF_INET`. On an IPv6-only mobile network the phone has
    /// no IPv4 address at all, and an IPv4 socket cannot send anywhere: every
    /// location reported "could not send an echo" while the same nodes answered
    /// fine over Wi-Fi. Asking for one family and getting a network that does not
    /// have it is not a failure worth reporting — it is a question worth not
    /// asking.
    ///
    /// This is also why the address goes through `getaddrinfo` even when it is
    /// already a literal: on a NAT64 network Darwin synthesises an IPv6 address
    /// for an IPv4 literal, so a node with no IPv6 of its own stays reachable.
    /// Apple documents this as the reason not to hardcode `AF_INET` anywhere.
    ///
    /// The first entry wins. `getaddrinfo` returns them in the order the system
    /// would use itself, which already accounts for what the interface can do.
    private static func resolve(_ host: String) -> Target? {
        var hints = addrinfo(
            ai_flags: AI_DEFAULT,
            ai_family: AF_UNSPEC,
            ai_socktype: SOCK_DGRAM,
            ai_protocol: 0,
            ai_addrlen: 0,
            ai_canonname: nil,
            ai_addr: nil,
            ai_next: nil
        )
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else { return nil }
        defer { freeaddrinfo(result) }

        var entry: UnsafeMutablePointer<addrinfo>? = first
        while let candidate = entry {
            let family = candidate.pointee.ai_family
            if family == AF_INET || family == AF_INET6, let addr = candidate.pointee.ai_addr {
                var storage = sockaddr_storage()
                memcpy(&storage, addr, Int(candidate.pointee.ai_addrlen))
                return Target(
                    storage: storage,
                    length: candidate.pointee.ai_addrlen,
                    family: family
                )
            }
            entry = candidate.pointee.ai_next
        }
        return nil
    }
}

/// Kotlin's callback, in terms Swift's concurrency checking accepts.
///
/// Objects crossing from Kotlin/Native carry no Sendable annotation, but they
/// are safe to call from any thread under its memory model. Saying so once here
/// is better than an `@unchecked` at every use.
private struct SendableCallback: @unchecked Sendable {
    let callback: IosBridgeCallback
}
