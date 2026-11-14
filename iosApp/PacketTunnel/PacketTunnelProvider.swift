import Cores
import Network
import NetworkExtension
import os

/// The tunnel extension: one tun, and behind it one of three arrangements
/// (`TunArrangement`, decided once per start from the files the app wrote).
///
///   * Reality, Hysteria2 and WebTunnel are sing-box's own transports. Since
///     2026-11-13 sing-box speaks them on a loopback SOCKS port and
///     hev-socks5-tunnel owns the tun and forwards every connection to it.
///     sing-box still answers the tun's DNS, sniffs, and applies the Bypass
///     Russia rules; it no longer runs a network stack of its own.
///   * xhttp and olcRTC are spoken by another engine — Xray, olcRTC — which
///     listens on a loopback SOCKS port; hev owns the tun the same way, and
///     sing-box is not started at all.
///   * The way back for the first: when config.json's inbound is a tun,
///     sing-box owns the tun itself (libbox, gVisor) and does the whole job,
///     as it did for Reality and Hysteria2 until 2026-11-13.
///
/// hev replaced sing-box-in-front-of-the-engine in 1.0.426: two Go network
/// stacks in a process killed at about 50 MB was the shape of every
/// speed-test death. sing-box's own gVisor tun went for the same budget: in
/// the lab, about 20 MB of the runtime (docs/ios-one-go-runtime.md).
class PacketTunnelProvider: NEPacketTunnelProvider {

    private let log = Logger(subsystem: "org.proofkit.app", category: "tunnel")

    /// Shared with the app, which writes the selected location's core config here.
    /// Nothing reads it yet; it is checked now because a missing App Group is
    /// invisible until the day the config matters, and then looks like a bug in
    /// the tunnel.
    private static let appGroup = "group.org.proofkit.app"

    /// Temporary bisection switch.
    ///
    /// The extension logs nothing at all and the system reports "VPN is inactive",
    /// so it is dying before our first line runs. With this false the framework is
    /// still linked but never called: if the tunnel then comes up, the fault is in
    /// starting libbox; if it still does not, the fault is in loading the framework
    /// at all, and no amount of reordering our own calls will help.
    private static let useLibbox = true

    /// 1.13 has no `LibboxNewService`: the command server owns the engine, and
    /// starting sing-box means asking it to. See `LibboxCommandHandler`.
    private var commandServer: LibboxCommandServer?

    /// Held because libbox only keeps a reference from the Go side, and a
    /// handler collected here would leave the engine calling into nothing.
    private var commandHandler: LibboxCommandHandler?

    /// Watches for the device changing network underneath the tunnel.
    ///
    /// Without this a Wi-Fi to cellular handover is noticed only when a
    /// connection finally fails, which to a user looks like the VPN randomly
    /// breaking. sing-box knows how to rebuild its sockets; it just has to be
    /// told the ground moved, whether it owns the tun or sits behind hev.
    ///
    /// It also says which interface the system routes through, and every
    /// outbound socket is pinned to that one when it can be (#58), so it runs
    /// from the first line of `startTunnel`, before any engine dials.
    private let pathMonitor = NWPathMonitor()

    /// Progress written where the app can read it.
    ///
    /// The extension's log lines never reach the person debugging this, so a
    /// breadcrumb that survives the process dying is worth more than a perfect
    /// log nobody sees: whatever stage is on screen when it dies is the stage
    /// that killed it.
    private func mark(_ stage: String) {
        log.info("stage: \(stage, privacy: .public)")
        // Stamped onto the memory trace too, so the footprint curve can be read
        // against the stage it was climbing during.
        MemoryWatch.mark(stage)
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroup
        ) else { return }
        try? Data(stage.utf8).write(to: container.appendingPathComponent("stage.txt"))
    }

    /// The soft ceiling handed to the Go runtime. See its use in startTunnel.
    ///
    /// 40 MiB, raised from 28 once the trace could say what 28 was doing.
    ///
    /// `debug.SetMemoryLimit` is measured against everything the runtime holds
    /// and has not returned, not against the live heap. On a phone running
    /// olcRTC that came to 33.8 MB — 45.6 MB taken from the OS, 11.8 MB handed
    /// back — while the live heap was only 19 MB. So the runtime sat 5.8 MB
    /// above a limit it could never get under, and did the only thing it can:
    /// collect, without stopping. The trace counted 3797 collections at one
    /// sample and 4287 less than five seconds later, about a hundred a second.
    /// Go caps that work at half the process's CPU, which is what the tunnel
    /// was paying, continuously, for the whole session. It did not prevent the
    /// death either.
    ///
    /// So the number has to clear what the runtime genuinely needs and still
    /// sit under the point where the process is killed. Measured: 33.8 MB in
    /// normal operation, deaths at 46.0, 47.5 and 47.5 MB of footprint. 40 MiB
    /// leaves the collector idle in the steady state and still catches the one
    /// thing a limit is for, a heap that doubles between collections.
    ///
    /// Reading the next trace: `gc` climbing by more than a few per second
    /// means this is too low again, and `sys` minus `rel` is the figure to
    /// compare against, never `heap`.
    private static let goMemoryLimit: Int64 = 40 * 1024 * 1024

    private static func failure(_ reason: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 10,
                userInfo: [NSLocalizedDescriptionKey: reason])
    }

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        log.info("startTunnel")
        NetworkDiagnostics.reset()
        NetworkDiagnostics.record("start os=\(ProcessInfo.processInfo.operatingSystemVersionString)")
        // A process can outlive a session: nothing from the last one's path
        // or pins is evidence about this one.
        LibboxPlatform.noteSystemPath([])
        LibboxPlatform.invalidatePinCache()
        // Before any engine starts, not once it is ready: the path's first
        // answer is what pins the engine's first dials (#58). It arrives on
        // the monitor's queue while the tunnel settings are being applied.
        // Until it has, the pin falls back to its own probe.
        startWatchingNetworkChanges()
        LibboxPlatform.tracePhysicalInterfaces("before-tun")
        // First thing, so the sentinel the app leaves in stage.txt is replaced
        // the moment this process runs a line of its own. Anything the app reads
        // back after this belongs to this attempt; the sentinel surviving means
        // the extension never got here at all, which is a different fault with a
        // different fix.
        mark("startTunnel")

        if let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroup
        ) {
            log.info("app group reachable at \(container.path, privacy: .public)")
        } else {
            // Not fatal here — the passthrough needs no config — but it would be
            // fatal later, so say so while the cause is still obvious.
            log.error("app group \(Self.appGroup, privacy: .public) is NOT reachable")
        }

        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: Self.appGroup
        ) else {
            completionHandler(Self.failure("app group unavailable"))
            return
        }

        // Clear the previous detailed log before every transport path. XHTTP
        // and olcRTC return through the hev branch below and used to skip the
        // reset, leaving a stale Reality session in the next export.
        SingBoxDebugLog.reset(workingPath: container.appendingPathComponent("libbox/work").path)

        // Before any core is loaded, so the first sample is the extension with
        // nothing in it and the climb afterwards is attributable. The record
        // outlives the process; that is the whole point of it. See MemoryWatch.
        MemoryWatch.start(container: container)
        MemoryPressure.start()

        let configURL = container.appendingPathComponent("config.json")
        guard let config = try? String(contentsOf: configURL, encoding: .utf8), !config.isEmpty else {
            completionHandler(Self.failure("no config in the shared container"))
            return
        }

        // Each is present only for the one transport that needs it, and the app
        // deletes the other, so a stale file cannot start a second core behind a
        // tunnel that does not use one.
        let xrayURL = container.appendingPathComponent("xray.json")
        let xrayConfig = try? String(contentsOf: xrayURL, encoding: .utf8)

        let olcrtcURL = container.appendingPathComponent("olcrtc.json")
        let olcrtc = (try? Data(contentsOf: olcrtcURL))
            .flatMap { try? JSONDecoder().decode(OlcrtcEngine.Parameters.self, from: $0) }

        // Before the tunnel's settings go on: from then on the system resolver
        // is our own tun, and the servers of the network underneath can no
        // longer be read. For every transport now, not only olcRTC — under
        // Bypass Russia the config wants one of them too. Counted rather than
        // listed in the trace — they are the network's addresses, and the
        // trace keeps to interface names.
        let resolvers = ResolverSnapshot.servers()
        NetworkDiagnostics.record("resolvers from the network: \(resolvers.count)")

        // What owns the tun and what stands behind it, decided once: the
        // resolvers the system is told about and the start path both come
        // from this, so the two cannot disagree. sing-box answers 172.19.0.2
        // itself, on the tun or behind hev; behind Xray or olcRTC hev forwards
        // to resolvers on the internet. See LibboxPlatform.Tun.
        let arrangement = TunArrangement.decide(
            config: config,
            xrayConfig: xrayConfig,
            olcrtcSocks: olcrtc.map {
                LoopbackSocks(port: $0.socksPort, username: $0.socksUser, password: $0.socksPass)
            }
        )
        NetworkDiagnostics.record("arrangement: \(arrangement.engineLabel)")
        let dns = Self.tunResolvers(for: arrangement)
        // Applied before the engine starts: libbox asks for the descriptor
        // synchronously and complains if answering takes long.
        setTunnelNetworkSettings(LibboxPlatform.tunnelSettings(dns: dns)) { [weak self] error in
            if let error {
                NetworkDiagnostics.record("tunnel settings failed domain=\((error as NSError).domain) code=\((error as NSError).code)")
                self?.log.error("tunnel settings rejected: \(error.localizedDescription, privacy: .public)")
                completionHandler(error)
                return
            }
            guard Self.useLibbox else {
                self?.log.info("tun established, libbox deliberately not started")
                completionHandler(nil)
                return
            }
            LibboxPlatform.tracePhysicalInterfaces("after-tun")
            self?.startEngine(
                arrangement: arrangement,
                config: config,
                xrayConfig: xrayConfig?.isEmpty == false ? xrayConfig : nil,
                olcrtc: olcrtc,
                resolvers: resolvers,
                container: container,
                completionHandler: completionHandler
            )
        }
    }

    private func startEngine(
        arrangement: TunArrangement,
        config: String,
        xrayConfig: String?,
        olcrtc: OlcrtcEngine.Parameters?,
        resolvers: [String],
        container: URL,
        completionHandler: @escaping (Error?) -> Void
    ) {
        do {
            mark("setup")
            // libbox keeps its state on disk; inside the group so the app can
            // read logs and caches too.
            let setup = LibboxSetupOptions()
            setup.basePath = container.appendingPathComponent("libbox").path
            setup.workingPath = container.appendingPathComponent("libbox/work").path
            setup.tempPath = NSTemporaryDirectory()
            setup.fixAndroidStack = false
            // 0 means the command server would listen on a unix socket rather
            // than a port — moot either way, because it is never started.
            setup.commandServerListenPort = 0
            setup.commandServerSecret = ""
            setup.logMaxLines = 100
            setup.debug = false
            try? FileManager.default.createDirectory(
                atPath: setup.workingPath, withIntermediateDirectories: true
            )
            // Plain C functions, not Objective-C methods, so Swift leaves their
            // NSError** as an argument rather than turning it into `throws`.
            var setupError: NSError?
            LibboxSetup(setup, &setupError)
            if let setupError { throw setupError }

            // Before the command server is built: it reads this to decide whether
            // to run its own OOM killer.
            //
            // A packet tunnel provider gets about 50 MB and is killed without
            // ceremony for exceeding it — the app is told nothing, the system
            // simply reports the VPN as inactive. This puts the Go runtime under
            // a 45 MB ceiling and makes its collector aggressive, which matters
            // more now than it did with one core: sing-box, Xray and olcRTC share
            // that one runtime, and WebRTC is not the cheap one.
            LibboxSetMemoryLimit(true)

            // Setup initializes libbox's UID/GID. Redirecting before it attempts
            // chown with zero-valued IDs and fails with EPERM on iOS. In this
            // libbox version RedirectStderr configures Go crash output; regular
            // interface diagnostics use their own shared-container file.
            var logError: NSError?
            LibboxRedirectStderr(container.appendingPathComponent("engine.log").path, &logError)
            if let logError {
                // Not fatal: losing the log is worse for the next bug than for
                // this connection.
                log.error("engine log unavailable: \(logError.localizedDescription, privacy: .public)")
                NetworkDiagnostics.record("crash log failed domain=\(logError.domain) code=\(logError.code)")
            }

            switch arrangement {
            case .engineBehindHev(let engine, let socks):
                // The engine that speaks the transport first, on its SOCKS
                // port: hev forwards to that port, and a connection to a port
                // nobody listens on fails rather than waits. No libbox service
                // on this path: one Go engine, and no network stack in Go.
                switch engine {
                case .xray:
                    guard let xrayConfig else { throw Self.failure("no Xray config for an xhttp start") }
                    mark("xray")
                    // Under Bypass Russia the Xray config carries the same direct
                    // resolver placeholder the sing-box one does; only this
                    // process can fill it in. A no-op for a config without it.
                    try XrayEngine.start(configJSON: DirectResolver.substitute(in: xrayConfig, resolvers: resolvers))
                case .olcrtc:
                    guard let olcrtc else { throw Self.failure("no olcRTC parameters for an olcRTC start") }
                    mark("olcrtc")
                    try OlcrtcEngine.start(olcrtc, resolvers: resolvers)
                }
                applyGoMemoryLimit()
                try startHev(socks: socks, arrangement: arrangement)

            case .singBoxBehindHev(let socks):
                // sing-box first, on its SOCKS port, for the same reason. It
                // is the same libbox service as on the tun, with a socks
                // inbound in place of the tun one, so nothing calls openTun.
                try startSingBox(config: config, resolvers: resolvers)
                // After the service, which is this path's only engine. Should
                // the limit or hev fail, the catch below closes the service
                // that is already running.
                applyGoMemoryLimit()
                try startHev(socks: socks, arrangement: arrangement)

            case .singBoxTun:
                // The way back: sing-box owns the tun and does the whole job.
                // The limit goes on before its service starts, as it always
                // did on this path; starting the service sets no limit.
                applyGoMemoryLimit()
                try startSingBox(config: config, resolvers: resolvers)
                mark("ready")
            }
            completionHandler(nil)
        } catch {
            mark("failed: \(error.localizedDescription)")
            // The tun's owner first, as in stopTunnel: hev stops reading the
            // descriptor before the engine it forwards to goes away.
            HevTunnel.stop()
            closeSingBox()
            XrayEngine.stop()
            OlcrtcEngine.stop()
            completionHandler(error)
        }
    }

    /// The soft ceiling on the Go runtime, after every engine has started,
    /// never before. Two of them set a ceiling of their own on the one
    /// runtime they share, and the last call wins:
    ///
    ///   * LibboxSetMemoryLimit in startEngine sets 45 MB. That is above where
    ///     this process actually dies — three traces from a phone show the
    ///     kill at 46.0, 47.5 and 47.5 MB of footprint, since the footprint
    ///     counts what the Go heap does not. A ceiling above the kill is not
    ///     a ceiling.
    ///   * libXray's `runXrayFromJson` calls its `memory.InitForceFree`, which
    ///     sets 30 MiB and GC percent 10, and starts a goroutine that returns
    ///     memory to the OS once a second. 30 MiB sits below what the runtime
    ///     genuinely holds under a speed test (44.6 MB of `sys` minus `rel` in
    ///     the 1.0.423 trace), so every xhttp session ran on the treadmill
    ///     1.0.416 was meant to end: `heap 26.9/30.0`, fifty collections a
    ///     second, and the kill at 46.8 MB anyway. Nothing stops that
    ///     goroutine; the limit it set is what this call replaces.
    ///
    /// Starting a libbox service sets no limit, so on the tun the call can
    /// come before it; behind hev it comes after it, with the rest.
    ///
    /// The trace prints the limit in force on every line, so a build that
    /// gets this order wrong says so in its first sample.
    private func applyGoMemoryLimit() {
        MobileSetMemoryLimit(Self.goMemoryLimit)
        NetworkDiagnostics.record(
            "go memory limit \(MobileMemoryLimit() / 1_048_576) MB in force (libbox and libXray defaults replaced)"
        )
    }

    /// Builds the command server and has it start sing-box with `config`, on
    /// the tun or on its SOCKS port, whichever inbound the config has. The
    /// server and its handler are held from the moment the service runs, so
    /// a later failure and stopTunnel both close them.
    private func startSingBox(config: String, resolvers: [String]) throws {
        mark("service")
        // The platform object is what libbox calls back into. On the tun,
        // openTun is where it hands over the descriptor (the system settings
        // are already applied); behind hev nothing opens a tun, and the
        // platform still answers whatever else sing-box asks of the system.
        let platform = LibboxPlatform(provider: self)
        let handler = LibboxCommandHandler(provider: self)
        var serverError: NSError?
        guard let server = LibboxNewCommandServer(handler, platform, &serverError) else {
            throw serverError ?? Self.failure("libbox would not build a command server")
        }
        mark("starting")
        // Bypass Russia: the config names a placeholder where the direct
        // resolver goes, because only this process could read it. A Global
        // config carries no placeholder and passes through untouched.
        let substituted = DirectResolver.substitute(in: config, resolvers: resolvers)
        // Deliberately not `server.start()`: that binds the gRPC command
        // socket for an app that talks to us through handleAppMessage
        // instead. Starting the engine is a separate call, and this is it.
        try server.startOrReloadService(substituted, options: LibboxOverrideOptions())
        self.commandHandler = handler
        self.commandServer = server
        log.info("sing-box started, config \(substituted.count, privacy: .public) bytes")
    }

    /// Closes the sing-box service and the server that owns it, if one runs.
    /// On the tun, closing the service is what releases the descriptor;
    /// skipping it leaves the next start fighting this one for it. Two
    /// calls: one stops the engine, the other tears down the server.
    private func closeSingBox() {
        try? commandServer?.closeService()
        commandServer?.close()
        commandServer = nil
        commandHandler = nil
    }

    /// hev takes the tun and forwards everything to `socks`, where the engine
    /// is already listening. Last on every hev path, after the memory limit.
    private func startHev(socks: LoopbackSocks, arrangement: TunArrangement) throws {
        mark("hev")
        guard let fd = TunDescriptor.find(in: packetFlow) else {
            throw Self.failure("no descriptor behind packetFlow")
        }
        try HevTunnel.start(socks: socks, mtu: LibboxPlatform.Tun.mtu, tunFd: fd)
        let engine = arrangement.engineLabel
        let dns = Self.tunResolvers(for: arrangement).joined(separator: ",")
        NetworkDiagnostics.record("tun up: engine=\(engine) socks=127.0.0.1:\(socks.port) fd=\(fd) dns=\(dns)")
        mark("ready")
        log.info("hev-socks5-tunnel up, engine=\(engine, privacy: .public)")
    }

    /// The resolvers the tun advertises to the system for an arrangement:
    /// 172.19.0.2, which only sing-box answers, wherever sing-box runs;
    /// resolvers on the internet, reached through the SOCKS engine, behind
    /// Xray or olcRTC. See TunArrangement.singBoxAnswersDns.
    ///
    /// olcRTC waited one release for the second: with hev the system's DNS
    /// queries travel through the engine as UDP, and on olcRTC that was the
    /// relay's datagram lane, which loses packets under load. Since engine
    /// aac553b8 the client answers a port-53 datagram over a smux stream
    /// instead (TCP DNS to the resolver the tun advertises, the server dials
    /// it like any CONNECT), so the lane no longer carries the resolver.
    private static func tunResolvers(for arrangement: TunArrangement) -> [String] {
        arrangement.singBoxAnswersDns ? LibboxPlatform.Tun.dns : LibboxPlatform.Tun.dnsThroughSocks
    }

    private func startWatchingNetworkChanges() {
        var lastInterface: String?
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // Record before the existing early return: availableInterfaces.first
            // can stay unchanged even when the usable path or family changes.
            let interfaces = path.availableInterfaces.map { "\($0.name):\($0.type)" }.joined(separator: ",")
            NetworkDiagnostics.record("path status=\(path.status) wifi=\(path.usesInterfaceType(.wifi)) cellular=\(path.usesInterfaceType(.cellular)) ipv4=\(path.supportsIPv4) ipv6=\(path.supportsIPv6) interfaces=\(interfaces)")
            // Before anything else: whatever moved, the next socket should look
            // at the interfaces afresh rather than trust a pin from before it,
            // and should prefer the one the system itself now routes through.
            LibboxPlatform.noteSystemPath(path.status == .satisfied ? path.availableInterfaces.map(\.name) : [])
            LibboxPlatform.invalidatePinCache()
            let current = path.availableInterfaces.first?.name
            guard current != lastInterface else { return }
            lastInterface = current
            guard let self else { return }
            let server = self.commandServer
            if HevTunnel.isRunning, server == nil {
                // Xray or olcRTC behind hev: hev keeps its tun and its
                // sessions, and the engine re-dials through the pin on its
                // own, as it did before.
                NetworkDiagnostics.record("network changed; hev keeps its tun, the engine re-dials")
                return
            }
            guard let server else { return }
            // sing-box, on the tun or behind hev, has to be told: it closes
            // what it holds and rebuilds its sockets on the new interface.
            // Behind hev the tun stays up; the connections sing-box closes
            // end at hev, which closes them toward the apps, and they redial.
            self.log.info("network changed to \(current ?? "none", privacy: .public), resetting")
            server.resetNetwork()
            NetworkDiagnostics.record(HevTunnel.isRunning
                ? "network changed; hev keeps its tun, sing-box resetNetwork"
                : "network changed; sing-box resetNetwork")
        }
        pathMonitor.start(queue: DispatchQueue(label: "org.proofkit.path"))
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("stopTunnel reason=\(reason.rawValue, privacy: .public)")
        NetworkDiagnostics.record("stop reason=\(reason.rawValue)")
        pathMonitor.cancel()
        // The tun's owner first, on every path: hev stops reading the
        // descriptor before the engine it forwards to goes away, sing-box
        // included. On the tun sing-box is the owner, and closing its
        // service is what releases the descriptor (closeSingBox).
        HevTunnel.stop()
        closeSingBox()
        XrayEngine.stop()
        OlcrtcEngine.stop()
        MemoryPressure.stop()
        // Last, so a stop that is itself slow or fatal is still on the trace.
        MemoryWatch.stop()
        completionHandler()
    }

    /// The app can talk to a running tunnel through this. One message so far:
    /// the room list of the olcRTC location, when a subscription refresh in the
    /// app changed it (see RoomKeeper), with the carrier it is for. Anything
    /// else - a list without a carrier included - is answered with nothing.
    override func handleAppMessage(
        _ messageData: Data,
        completionHandler: ((Data?) -> Void)?
    ) {
        guard let message = try? JSONSerialization.jsonObject(with: messageData) as? [String: Any],
              message["type"] as? String == "olcrtc-rooms",
              let carrier = message["carrierName"] as? String, !carrier.isEmpty,
              let primary = message["primaryRoom"] as? String, !primary.isEmpty
        else {
            completionHandler?(nil)
            return
        }
        let extras = message["failoverRooms"] as? [String] ?? []
        RoomKeeper.shared.apply(primary: primary, extras: extras, carrier: carrier, source: "app")
        completionHandler?(Data("ok".utf8))
    }
}

/// The callbacks libbox 1.13 requires in order to hand out an engine at all.
///
/// `LibboxNewService` is gone: a command server owns the engine now, and one
/// cannot be built without a handler. Its gRPC socket stays unopened — the app
/// reaches the tunnel through `handleAppMessage` — so in practice only the
/// engine itself calls in here, and only to report that it is stopping.
final class LibboxCommandHandler: NSObject, LibboxCommandServerHandlerProtocol {

    private weak var provider: NEPacketTunnelProvider?
    private let log = Logger(subsystem: "org.proofkit.app", category: "command")

    init(provider: NEPacketTunnelProvider) {
        self.provider = provider
        super.init()
    }

    /// The engine deciding to stop — an OOM kill against the extension's memory
    /// cap arrives here and nowhere else. Tearing the tunnel down makes the
    /// system show it as disconnected instead of leaving a VPN icon over a dead
    /// engine, which is the failure that cost a night once already.
    func serviceStop() throws {
        log.error("engine asked to stop")
        // Into the trace as well, not just os_log: this is the difference
        // between "libbox gave up" and "the system took the tunnel away", and
        // the two look identical from the app, which sees only that the
        // session is gone. stopTunnel's own reason will then be providerFailed,
        // which says who asked but not which component.
        NetworkDiagnostics.record("libbox asked the provider to stop")
        provider?.cancelTunnelWithError(nil)
    }

    /// Nothing to reload into: a new location is a new config, and the app
    /// restarts the tunnel for it rather than reloading in place.
    func serviceReload() throws {}

    func getSystemProxyStatus() throws -> LibboxSystemProxyStatus {
        let status = LibboxSystemProxyStatus()
        // A packet tunnel carries every flow already; there is no separate
        // system proxy for iOS to offer.
        status.available = false
        status.enabled = false
        return status
    }

    func setSystemProxyEnabled(_ enabled: Bool) throws {
        throw NSError(domain: "org.proofkit.tunnel", code: 4,
                      userInfo: [NSLocalizedDescriptionKey: "no system proxy on iOS"])
    }

    func writeDebugMessage(_ message: String?) {
        guard let message else { return }
        log.debug("\(message, privacy: .public)")
    }
}
