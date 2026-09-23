import Cores
import Network
import NetworkExtension
import os

/// The tunnel extension: one tun, and behind it one of two arrangements.
///
///   * Reality and Hysteria2 are sing-box's own transports, so sing-box owns
///     the tun (libbox) and does the whole job.
///   * xhttp and olcRTC are spoken by another engine — Xray, olcRTC — which
///     listens on a loopback SOCKS port; hev-socks5-tunnel owns the tun and
///     forwards every connection to that port. sing-box is not started at all.
///
/// The second arrangement replaced sing-box-in-front-of-the-engine in 1.0.426:
/// two Go network stacks in a process killed at about 50 MB was the shape of
/// every speed-test death (docs/ios-one-go-runtime.md).
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
    /// told the ground moved.
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
        LibboxPlatform.invalidatePinCache()
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

        // Which engine owns the tun decides which resolvers the system is told
        // about: sing-box answers 172.19.0.2 itself; hev forwards to resolvers
        // on the internet. See LibboxPlatform.Tun.
        let hevOwnsTun = Self.hevOwnsTun(xrayConfig: xrayConfig, olcrtc: olcrtc)
        let dns = hevOwnsTun ? LibboxPlatform.Tun.dnsThroughSocks : LibboxPlatform.Tun.dns
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

            // The borrowed core first, whichever it is: sing-box's outbound
            // points at its SOCKS port, and a sing-box that starts against a
            // port nobody is listening on fails every connection rather than
            // waiting. Never both — a location is one transport.
            if let xrayConfig {
                mark("xray")
                // Under Bypass Russia the Xray config carries the same direct
                // resolver placeholder the sing-box one does; only this
                // process can fill it in. A no-op for a config without it.
                try XrayEngine.start(configJSON: DirectResolver.substitute(in: xrayConfig, resolvers: resolvers))
            }
            if let olcrtc {
                mark("olcrtc")
                try OlcrtcEngine.start(olcrtc, resolvers: resolvers)
            }

            // After every engine has started, never before. Two of them set a
            // ceiling of their own on the one runtime they share, and the last
            // call wins:
            //
            //   * LibboxSetMemoryLimit above sets 45 MB. That is above where
            //     this process actually dies — three traces from a phone show
            //     the kill at 46.0, 47.5 and 47.5 MB of footprint, since the
            //     footprint counts what the Go heap does not. A ceiling above
            //     the kill is not a ceiling.
            //   * libXray's `runXrayFromJson` calls its `memory.InitForceFree`,
            //     which sets 30 MiB and GC percent 10, and starts a goroutine
            //     that returns memory to the OS once a second. 30 MiB sits
            //     below what the runtime genuinely holds under a speed test
            //     (44.6 MB of `sys` minus `rel` in the 1.0.423 trace), so
            //     every xhttp session ran on the treadmill 1.0.416 was meant
            //     to end: `heap 26.9/30.0`, fifty collections a second, and
            //     the kill at 46.8 MB anyway. Nothing stops that goroutine;
            //     the limit it set is what this call replaces.
            //
            // The trace prints the limit in force on every line, so a build
            // that gets this order wrong says so in its first sample.
            MobileSetMemoryLimit(Self.goMemoryLimit)
            NetworkDiagnostics.record(
                "go memory limit \(MobileMemoryLimit() / 1_048_576) MB in force (libbox and libXray defaults replaced)"
            )

            // The engine that speaks the transport is up on its SOCKS port;
            // hev takes the tun and forwards to it. No libbox service on this
            // path: sing-box was the second Go stack, and the point is that
            // there is only one.
            if let socks = Self.hevSocks(xrayConfig: xrayConfig, olcrtc: olcrtc) {
                mark("hev")
                guard let fd = TunDescriptor.find(in: packetFlow) else {
                    throw Self.failure("no descriptor behind packetFlow")
                }
                try HevTunnel.start(socks: socks, mtu: LibboxPlatform.Tun.mtu, tunFd: fd)
                let engine = xrayConfig != nil ? "xray" : "olcrtc"
                NetworkDiagnostics.record(
                    "tun up: engine=\(engine)+hev socks=127.0.0.1:\(socks.port) fd=\(fd) dns=\(LibboxPlatform.Tun.dnsThroughSocks.joined(separator: ","))"
                )
                mark("ready")
                startWatchingNetworkChanges()
                log.info("hev-socks5-tunnel started in front of \(engine, privacy: .public)")
                completionHandler(nil)
                return
            }
            mark("service")

            // The platform object is what libbox calls back into; openTun is where
            // the system settings get applied, so there is no separate call here.
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
            let config = substituted
            // Deliberately not `server.start()`: that binds the gRPC command
            // socket for an app that talks to us through handleAppMessage
            // instead. Starting the engine is a separate call, and this is it.
            try server.startOrReloadService(config, options: LibboxOverrideOptions())
            mark("ready")
            startWatchingNetworkChanges()
            self.commandHandler = handler
            self.commandServer = server

            log.info("sing-box started, config \(config.count, privacy: .public) bytes")
            completionHandler(nil)
        } catch {
            mark("failed: \(error.localizedDescription)")
            HevTunnel.stop()
            XrayEngine.stop()
            OlcrtcEngine.stop()
            completionHandler(error)
        }
    }

    /// Whether hev-socks5-tunnel, rather than sing-box, owns the tun for
    /// this start: for both engines that speak their transport behind a
    /// SOCKS port, xhttp (Xray) and olcRTC.
    ///
    /// olcRTC waited one release: with hev the system's DNS queries travel
    /// through the engine as UDP, and on olcRTC that was the relay's
    /// datagram lane, which loses packets under load. Since engine
    /// aac553b8 the client answers a port-53 datagram over a smux stream
    /// instead (TCP DNS to the resolver the tun advertises, the server dials
    /// it like any CONNECT), so the lane no longer carries the resolver.
    /// One predicate decides both the tun's resolvers and the engine
    /// arrangement, so the two cannot disagree.
    private static func hevOwnsTun(xrayConfig: String?, olcrtc: OlcrtcEngine.Parameters?) -> Bool {
        olcrtc != nil || xrayConfig?.isEmpty == false
    }

    /// The SOCKS server hev forwards to on the paths it owns, or nil on the
    /// paths sing-box owns. olcRTC's port and credentials come from its
    /// parameters; Xray's port is read from the config the app wrote (the
    /// inbound it built).
    private static func hevSocks(xrayConfig: String?, olcrtc: OlcrtcEngine.Parameters?) -> HevTunnel.Socks? {
        guard hevOwnsTun(xrayConfig: xrayConfig, olcrtc: olcrtc) else { return nil }
        if let olcrtc {
            return HevTunnel.Socks(port: olcrtc.socksPort, username: olcrtc.socksUser, password: olcrtc.socksPass)
        }
        guard let xrayConfig else { return nil }
        return HevTunnel.Socks(port: xraySocksPort(in: xrayConfig), username: nil, password: nil)
    }

    /// The port Xray's SOCKS inbound listens on, or 10810 when the config
    /// cannot be read — the constant XrayConfig.kt builds with.
    static func xraySocksPort(in configJSON: String) -> Int {
        guard let data = configJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let inbounds = object["inbounds"] as? [[String: Any]]
        else { return 10810 }
        for inbound in inbounds where inbound["protocol"] as? String == "socks" {
            if let port = inbound["port"] as? Int { return port }
            if let port = inbound["port"] as? NSNumber { return port.intValue }
        }
        return 10810
    }

    private func startWatchingNetworkChanges() {
        var lastInterface: String?
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // Record before the existing early return: availableInterfaces.first
            // can stay unchanged even when the usable path or family changes.
            let interfaces = path.availableInterfaces.map { "\($0.name):\($0.type)" }.joined(separator: ",")
            NetworkDiagnostics.record("path status=\(path.status) wifi=\(path.usesInterfaceType(.wifi)) cellular=\(path.usesInterfaceType(.cellular)) ipv4=\(path.supportsIPv4) ipv6=\(path.supportsIPv6) interfaces=\(interfaces)")
            // Before anything else: whatever moved, the next socket should look
            // at the interfaces afresh rather than trust a pin from before it.
            LibboxPlatform.invalidatePinCache()
            let current = path.availableInterfaces.first?.name
            guard current != lastInterface else { return }
            lastInterface = current
            guard let self else { return }
            if HevTunnel.isRunning {
                // hev keeps its tun and its sessions; the engine behind it
                // re-dials through the pin on its own, as it did before.
                NetworkDiagnostics.record("network changed; hev keeps its tun, the engine re-dials")
                return
            }
            guard let server = self.commandServer else { return }
            self.log.info("network changed to \(current ?? "none", privacy: .public), resetting")
            server.resetNetwork()
            NetworkDiagnostics.record("sing-box resetNetwork; olcrtc not explicitly restarted")
        }
        pathMonitor.start(queue: DispatchQueue(label: "org.proofkit.path"))
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("stopTunnel reason=\(reason.rawValue, privacy: .public)")
        NetworkDiagnostics.record("stop reason=\(reason.rawValue)")
        // Closing the service is what releases the tun descriptor; skipping it
        // leaves the next start fighting the previous one for it. Two calls now:
        // one stops the engine, the other tears down the server that owns it.
        pathMonitor.cancel()
        // The tun's owner first, on either path: hev stops reading the
        // descriptor before the engine it forwards to goes away.
        HevTunnel.stop()
        try? commandServer?.closeService()
        commandServer?.close()
        commandServer = nil
        commandHandler = nil
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
