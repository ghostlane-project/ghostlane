import Darwin
import Network
import NetworkExtension
import os

/// The tunnel extension: one tun, owned by hev-socks5-tunnel, and behind it
/// one engine of ghostlane-core on a loopback SOCKS port (`TunArrangement`,
/// decided once per start from the files the app wrote):
///
///   * core.json: xray-rust's core (`CoreEngine`) runs Reality, Reality
///     gRPC, WebTunnel, Hysteria2 and XHTTP, answers the tun's DNS through
///     its own `dns` outbound and applies the Bypass Russia rules;
///   * olcrtc.json: the Rust olcRTC client (`OlcrtcEngine`).
///
/// No Go since step C of ghostlane-rust-core: no sing-box, no Xray, no Go
/// olcRTC, one Rust library with one runtime (one worker on iOS), one
/// allocator and one byte budget. A start whose files only Go could run is
/// refused with the reason in stage.txt.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private let log = Logger(subsystem: "org.proofkit.app", category: "tunnel")

    /// Shared with the app, which writes the selected location's core config here.
    /// Nothing reads it yet; it is checked now because a missing App Group is
    /// invisible until the day the config matters, and then looks like a bug in
    /// the tunnel.
    private static let appGroup = "group.org.proofkit.app"

    /// Watches for the device changing network underneath the tunnel.
    ///
    /// Without this a Wi-Fi to cellular handover is noticed only when a
    /// connection finally fails, which to a user looks like the VPN randomly
    /// breaking. The core rebuilds its connections once told the ground
    /// moved (`CoreEngine.resetNetwork`); olcRTC re-dials on its own.
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
        TunPlatform.noteSystemPath([])
        TunPlatform.invalidatePinCache()
        // Before any engine starts, not once it is ready: the path's first
        // answer is what pins the engine's first dials (#58). It arrives on
        // the monitor's queue while the tunnel settings are being applied.
        // Until it has, the pin falls back to its own probe.
        startWatchingNetworkChanges()
        TunPlatform.tracePhysicalInterfaces("before-tun")
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
        // tunnel that does not use one. xray.json is Go's Xray's: read only to
        // say why such a start is refused.
        let coreConfig = try? String(contentsOf: container.appendingPathComponent("core.json"), encoding: .utf8)
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

        // What stands behind the tun, decided once: hev's UDP mode and the
        // start path both come from this, so the two cannot disagree.
        let arrangement = TunArrangement.decide(
            coreConfig: coreConfig,
            olcrtcSocks: olcrtc.map {
                LoopbackSocks(port: $0.socksPort, username: $0.socksUser, password: $0.socksPass)
            },
            xrayConfig: xrayConfig,
            config: config
        )
        NetworkDiagnostics.record("arrangement: \(arrangement.engineLabel)")
        if case .needsGo(let reason) = arrangement {
            mark("failed: \(reason)")
            completionHandler(Self.failure(reason))
            return
        }
        // Applied before the engine starts, so hev has a descriptor to take.
        setTunnelNetworkSettings(TunPlatform.tunnelSettings()) { [weak self] error in
            if let error {
                NetworkDiagnostics.record("tunnel settings failed domain=\((error as NSError).domain) code=\((error as NSError).code)")
                self?.log.error("tunnel settings rejected: \(error.localizedDescription, privacy: .public)")
                completionHandler(error)
                return
            }
            TunPlatform.tracePhysicalInterfaces("after-tun")
            self?.startEngine(
                arrangement: arrangement,
                coreConfig: coreConfig,
                olcrtc: olcrtc,
                resolvers: resolvers,
                container: container,
                completionHandler: completionHandler
            )
        }
    }

    private func startEngine(
        arrangement: TunArrangement,
        coreConfig: String?,
        olcrtc: OlcrtcEngine.Parameters?,
        resolvers: [String],
        container: URL,
        completionHandler: @escaping (Error?) -> Void
    ) {
        do {
            // hev logs to stderr; so does the core's runtime when it panics.
            // engine.log in the group is where the app reads both.
            let enginelog = container.appendingPathComponent("engine.log").path
            if freopen(enginelog, "w", stderr) == nil {
                NetworkDiagnostics.record("engine log unavailable errno=\(errno)")
            }
            switch arrangement {
            case .engineBehindHev(.core, let socks):
                // The engine first, on its SOCKS port: hev forwards to that
                // port, and a connection to a port nobody listens on fails
                // rather than waits.
                guard let coreConfig, !coreConfig.isEmpty else { throw Self.failure("no core.json for a Rust core start") }
                mark("core")
                // Under Bypass Russia core.json carries the direct resolver
                // placeholder; only this process can fill it in. A no-op for
                // a config without it.
                try CoreEngine.start(configJSON: DirectResolver.substitute(in: coreConfig, resolvers: resolvers))
                try startHev(socks: socks, arrangement: arrangement)
            case .engineBehindHev(.olcrtc, let socks):
                guard let olcrtc else { throw Self.failure("no olcRTC parameters for an olcRTC start") }
                mark("olcrtc")
                try OlcrtcEngine.start(olcrtc, resolvers: resolvers)
                try startHev(socks: socks, arrangement: arrangement)
            case .needsGo(let reason):
                throw Self.failure(reason)
            }
            NetworkDiagnostics.record("core memory at ready: \(memoryStats())")
            completionHandler(nil)
        } catch {
            mark("failed: \(error.localizedDescription)")
            // The tun's owner first, as in stopTunnel: hev stops reading the
            // descriptor before the engine it forwards to goes away.
            HevTunnel.stop()
            CoreEngine.stop()
            OlcrtcEngine.stop()
            completionHandler(error)
        }
    }

    /// hev takes the tun and forwards everything to `socks`, where the engine
    /// is already listening. Last on every path.
    private func startHev(socks: LoopbackSocks, arrangement: TunArrangement) throws {
        mark("hev")
        guard let fd = TunDescriptor.find(in: packetFlow) else {
            throw Self.failure("no descriptor behind packetFlow")
        }
        try HevTunnel.start(socks: socks, mtu: TunPlatform.Tun.mtu, udp: arrangement.hevUdp, tunFd: fd)
        let engine = arrangement.engineLabel
        let dns = TunPlatform.Tun.dnsThroughSocks.joined(separator: ",")
        NetworkDiagnostics.record("tun up: engine=\(engine) socks=127.0.0.1:\(socks.port) udp=\(arrangement.hevUdp) fd=\(fd) dns=\(dns)")
        mark("ready")
        log.info("hev-socks5-tunnel up, engine=\(engine, privacy: .public)")
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
            TunPlatform.noteSystemPath(path.status == .satisfied ? path.availableInterfaces.map(\.name) : [])
            TunPlatform.invalidatePinCache()
            let current = path.availableInterfaces.first?.name
            guard current != lastInterface else { return }
            lastInterface = current
            guard self != nil else { return }
            if CoreEngine.isRunning {
                // The core closes what it holds on the old path; hev keeps
                // its tun, the apps' connections end at hev and redial.
                let closed = CoreEngine.resetNetwork()
                NetworkDiagnostics.record("network changed; hev keeps its tun, the core reset \(closed) connection(s)")
            } else if HevTunnel.isRunning {
                // olcRTC behind hev re-dials through the pin on its own.
                NetworkDiagnostics.record("network changed; hev keeps its tun, the engine re-dials")
            }
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
        // The tun's owner first: hev stops reading the descriptor before the
        // engine it forwards to goes away.
        HevTunnel.stop()
        CoreEngine.stop()
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
