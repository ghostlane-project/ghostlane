package org.olcbox.app.vpn


import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.DesktopChannelProbe
import org.olcbox.app.net.DesktopSingBoxController
import org.olcbox.app.net.DesktopXrayController
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.Routing
import org.olcbox.app.vpn.desktop.TunnelDaemonProtocol
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.MacOsTunController
import org.olcbox.app.vpn.desktop.MacOsTunnelDaemon
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.WindowsTunController
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.olcbox.app.log.LogScrubber

class DesktopVpnManager private constructor(
    private val locationsRepository: LocationsRepository,
    private val proxyController: DesktopProxyController = DesktopProxyController.current(),
    private val pacServer: PacServer = PacServer()
) : VpnManager {

    constructor(locationsRepository: LocationsRepository) : this(
        locationsRepository = locationsRepository,
        proxyController = DesktopProxyController.current(),
        pacServer = PacServer()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    override val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    // Desktop runs sing-box and Xray as separate processes behind a tun it does
    // not own, so there is no counter here to read. Null, not zeroes.
    override val traffic: StateFlow<TrafficCounters?> = MutableStateFlow(null).asStateFlow()

    private val _socksProxySettings = MutableStateFlow(DesktopSocksProxySettings())
    val socksProxySettings: StateFlow<DesktopSocksProxySettings> = _socksProxySettings.asStateFlow()
    private val lanProxy = DesktopLanProxy(::addLog)
    private var lanWatchJob: Job? = null
    private var lanControlJob: Job? = null
    private val _lanProxyEndpoint = MutableStateFlow<String?>(null)
    val lanProxyEndpoint: StateFlow<String?> = _lanProxyEndpoint.asStateFlow()
    private val _lanProxyHealth = MutableStateFlow<String?>(null)
    val lanProxyHealth: StateFlow<String?> = _lanProxyHealth.asStateFlow()
    private val _lanAddresses = MutableStateFlow(
        runCatching { DesktopLanProxy.privateAddresses() }.getOrDefault(emptyList())
    )
    val lanAddresses: StateFlow<List<String>> = _lanAddresses.asStateFlow()
    val lanSecurityNotice: String? = when (DesktopPaths.os) {
        DesktopOs.Windows ->
            "The Windows firewall rule accepts Private-network LocalSubnet clients and is removed when sharing stops."
        DesktopOs.MacOS, DesktopOs.Linux ->
            "The proxy binds only to the selected private interface; your system firewall still controls which local devices can reach it."
        DesktopOs.Other -> null
    }
    private val lanShutdownHook = Thread({ lanProxy.stop() }, "ghostlane-lan-cleanup")

    /** Where traffic actually comes out, as measured after connecting. */
    private val _exitInfo = MutableStateFlow<org.olcbox.app.net.TunnelExit?>(null)
    val exitInfo: StateFlow<org.olcbox.app.net.TunnelExit?> = _exitInfo.asStateFlow()

    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var tunLogJob: Job? = null
    private var processWatchJob: Job? = null
    private var tunProcessWatchJob: Job? = null
    private var macTunWatchJob: Job? = null
    private var process: Process? = null
    private var tunProcess: Process? = null
    private var olcRtcConfigPath: Path? = null
    private var generation = 0L
    /** Separates adapter names from adapters retained by an earlier app process. */
    private val windowsTunSessionId = UUID.randomUUID().toString().take(8)
    private val linuxTunController = LinuxTunController(::addLog)
    private val windowsTunController = WindowsTunController(::addLog)
    private val macOsTunController = MacOsTunController(::addLog)

    /** The daemon's own socks inbound, which is what a green light is measured through. */
    private var macTunVerifyPort: Int? = null
    private var windowsTunExit: org.olcbox.app.net.TunnelExit? = null
    private val windowsTunCore = org.olcbox.app.net.DesktopSingBoxController(onOutput = ::addLog)

    /**
     * Whether this session started a macOS tunnel at all.
     *
     * Without it, every disconnect on a Mac where the daemon was never installed
     * would log that stopping the tunnel failed — a line that is true, useless,
     * and alarming.
     */
    private var macTunActive = false

    // Unified client: sing-box / Xray cores for vless/hy2/xhttp locations (exec'd
    // bundled binaries). The tun/PAC targets `activeCorePort` when a core is active
    // (null = olcrtc's own SOCKS port).
    // Forward the cores' own output into the app log: when an outbound fails the
    // core says why on its first lines, and that used to go to a temp file nobody read.
    private val singBoxCore = org.olcbox.app.net.DesktopSingBoxController(
        onOutput = { line -> addLog(line) }
    )
    private val xrayCore = org.olcbox.app.net.DesktopXrayController(
        onOutput = { line -> addLog(line) }
    )
    private var activeCorePort: Int? = null

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                        _status.value is VpnStatus.Connecting ||
                        _status.value is VpnStatus.Reconnecting ||
                        process != null ||
                        tunProcess != null

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting desktop VPN for selected location")
                    stopDesktopMode(finalStatus = false)

                    if (requestGeneration != generation) return@withLock
                }

                startDesktopMode(requestGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }
        }
    }

    /**
     * olcRTC is addressed by a room on somebody else's SFU and has no host to
     * reach, so its own prober is the only measurement. Everything else names a
     * server in its link, and [DesktopChannelProbe] can measure real HTTP
     * through an isolated instance of that outbound.
     *
     * Until this existed the base implementation answered for olcRTC alone, and
     * a subscription of Reality and Hysteria2 met "Nothing here can be measured"
     * — true of the old code and of nothing else.
     */
    @Volatile private var channelProbe: org.olcbox.app.net.ChannelLatency.Session? = null
    private var connectedLocation: LocationConfig? = null
    @Volatile private var channelProxy: SubscriptionFetchProxy? = null
    @Volatile private var windowsTunVerifyProxy: SubscriptionFetchProxy? = null

    override fun canPing(locationConfig: LocationConfig): Boolean {
        val config = locationConfig.normalized()
        if (!config.isComplete()) return false
        if (status.value is VpnStatus.Connected && config == connectedLocation) return true
        // Disconnected olcRTC rooms are deliberately not probed.
        //
        // There is no host to probe: a room is a meeting, not an address, so the only
        // way to time one is `mobile.Ping`, which JOINS the room as a real client,
        // waits for the session to become ready and tears it down. That is a genuine
        // peer occupying a node whose whole capacity is single digits, for a number
        // that is time-to-join rather than latency and is not comparable with the ICMP
        // figures on the rows beside it. A button that says "measure" and quietly
        // connects is worth less than no button.
        if (config.kind == LocationKind.Olcrtc) return false
        return serverEndpoint(config) != null
    }

    private fun serverEndpoint(config: LocationConfig): Pair<String, Int>? =
        config.rawLink
            ?.let { LinkParser.parse(it) }
            ?.takeIf { it.host.isNotBlank() }
            ?.let { it.host to it.port }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        // Other entries retain their address probes. Only the active entry
        // measures HTTP through the existing tunnel; never join a spare room.
        if (status.value is VpnStatus.Connected && locationConfig.normalized() == connectedLocation) {
            return measureCurrentChannel()
        }
        val config = locationConfig.normalized()
        if (config.kind != LocationKind.Olcrtc) {
            val spec = config.rawLink?.let(LinkParser::parse) ?: return null
            return DesktopChannelProbe.measure(spec)
        }
        return OlcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override val canProbeTransports: Boolean get() = true

    // Smart connect: the location's core, alone, on its own port and config.
    override suspend fun probeTransport(locationConfig: LocationConfig): Boolean? =
        TransportProbe.passes(locationConfig) { spec, config ->
            if (TransportProbe.usesXray(spec)) {
                val xray = DesktopXrayController()
                xray.start(config)
                object : TransportProbe.Core {
                    override fun isRunning(): Boolean = xray.isRunning()
                    override suspend fun stop() = xray.stop()
                }
            } else {
                val singBox = DesktopSingBoxController()
                singBox.start(config)
                object : TransportProbe.Core {
                    override fun isRunning(): Boolean = singBox.isRunning()
                    override suspend fun stop() = singBox.stop()
                }
            }
        }

    override suspend fun measureCurrentChannel(): Long? {
        if (status.value !is VpnStatus.Connected) return null
        val session = channelProbe ?: return null
        val measured = session.measure()
        return measured.takeIf { status.value is VpnStatus.Connected && channelProbe === session }
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? =
        channelProxy.takeIf { status.value is VpnStatus.Connected }

    override suspend fun diagnosticsLog(): String = if (DesktopPaths.os == DesktopOs.MacOS) {
        kotlinx.coroutines.withTimeoutOrNull(DIAGNOSTICS_TIMEOUT_MS) {
            macOsTunController.diagnostics()
        } ?: "tunnel daemon diagnostics timed out"
    } else {
        ""
    }


    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = DesktopSocksProxySettings(
            port = port,
            username = username,
            password = password
        ).normalized()
        _socksProxySettings.value = settings
        pacServer.updateSocksTarget(
            socksHost = settings.host,
            socksPort = settings.port,
            socksUsername = settings.username,
            socksPassword = settings.password
        )
    }

    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
        pacServer.updateSocksTarget(
            socksHost = normalized.host,
            socksPort = normalized.port,
            socksUsername = normalized.username,
            socksPassword = normalized.password
        )
    }

    fun refreshLanAddresses() {
        _lanAddresses.value = runCatching { DesktopLanProxy.privateAddresses() }.getOrDefault(emptyList())
    }

    fun withTrustedLanAddress(settings: DesktopSocksProxySettings, address: String): DesktopSocksProxySettings =
        settings.copy(
            lanAddress = address,
            lanNetworkId = DesktopLanProxy.networkIdentity(address).orEmpty()
        ).normalized()

    fun isTrustedLanNetwork(settings: DesktopSocksProxySettings): Boolean =
        settings.lanAddress in _lanAddresses.value &&
            settings.lanNetworkId.isNotBlank() &&
            settings.lanNetworkId == DesktopLanProxy.networkIdentity(settings.lanAddress)

    /** Apply LAN-only changes without tearing down and rebuilding the VPN tunnel. */
    fun applyLanSharingSettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        updateSocksProxySettings(normalized)
        lanControlJob?.cancel()
        lanWatchJob?.cancel()
        lanWatchJob = null
        lanControlJob = scope.launch {
            lanProxy.stop()
            _lanProxyEndpoint.value = null
            _lanProxyHealth.value = null
            if (!normalized.shareOnLan) return@launch
            val upstream = channelProxy
            val requestGeneration = generation
            if (_status.value !is VpnStatus.Connected || upstream == null) return@launch
            startLanSharing(normalized, upstream, requestGeneration)
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(lanShutdownHook)
        runCatching { lanProxy.cleanupStaleFirewallRules() }
            .onFailure { addLog("LAN sharing: stale firewall cleanup failed: ${it.message}") }
        // A tunnel outlives the process that asked for it: the daemon keeps the
        // tun after the app is killed, so the app has to ask what is true rather
        // than assume it starts from idle. Assuming idle is the iOS bug that
        // showed "relay idle" over a live tunnel and then tore it down.
        //
        // Stopped rather than adopted into Connected, deliberately: this manager
        // cannot say which location an orphaned tun belongs to, and a connection
        // it cannot describe is worse than a clean restart. Giving the daemon a
        // location tag to hand back is the fix if that proves annoying.
        if (DesktopPaths.os == DesktopOs.MacOS) {
            scope.launch {
                if (!macOsTunController.isRunning()) return@launch
                addLog("a tunnel from a previous run was still up; stopping it")
                macOsTunController.stop()
            }
        }
    }

    fun close() {
        runBlocking {
            generation++

            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }

            scope.cancel()
        }
        runCatching { Runtime.getRuntime().removeShutdownHook(lanShutdownHook) }
    }

    private suspend fun startDesktopMode(requestGeneration: Long, isRestart: Boolean) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.getActiveLocation()
        val location = active?.location?.normalized()

        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting desktop proxy")
            return
        }

        try {
            val ready = CompletableDeferred<Unit>()
            val startupFailure = CompletableDeferred<String>()
            val desktopMode = DesktopMode.current()
            val socksSettings = _socksProxySettings.value.normalized()

            // Where a bypass can apply here: the proxy, whose core's direct
            // sockets are ordinary ones, and the macOS tunnel, whose daemon binds
            // them to the physical interface. The Linux and Windows tunnels route
            // by policy and by metric, and a direct socket from the core would
            // enter them; they stay global until they have a way out.
            val routingSettings = locationsRepository.getRoutingSettings()
            val verboseLogs = routingSettings.verboseDebugLogs
            // Only a regional bypass needs rule-set routing. Global retains
            // the existing desktop core and tunnel configuration.
            val rulesRequested = routingSettings.mode.region != null
            val rulesApply = rulesRequested &&
                (desktopMode == DesktopMode.SystemProxy || desktopMode == DesktopMode.MacTun)
            if (rulesRequested && !rulesApply) {
                addLog("Routing: $desktopMode keeps everything through the tunnel for now")
            } else if (rulesApply) {
                addLog("Routing: ${routingSettings.mode.hubSummary()}")
            }
            // In the proxy the rules live in the core; in the macOS tunnel they
            // live in the daemon, and the core stays as it was.
            val coreRouting: Routing = if (rulesApply && desktopMode == DesktopMode.SystemProxy) {
                val routing = Routing.Rules(
                    DesktopPaths.appDataDir().resolve("rulesets").toString(),
                    DirectDns.System,
                    requireNotNull(routingSettings.mode.region)
                )
                installRuleSets(routing)
                routing
            } else {
                Routing.Global
            }
            var frontPort: Int? = null

            if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.ensureAdministratorOrRequestRestart()
            }

            // Branch on location kind: olcrtc uses the existing engine path
            // (unchanged); vless/hy2/xhttp start a sing-box/Xray core on the core
            // SOCKS port. The tun/PAC then targets whichever port is active.
            connectedLocation = location.normalized()
            val isOlcrtc = location.kind == org.olcbox.app.net.LocationKind.Olcrtc
            val effectiveSocksPort =
                if (isOlcrtc) {
                    socksSettings.port
                } else {
                    // Stop first: on a reconnect the previous core still holds the
                    // port, and allocating before that made every restart fall back
                    // to a random port for no reason.
                    stopDesktopCores()
                    allocateCorePort()
                }
            activeCorePort = if (isOlcrtc) null else effectiveSocksPort

            if (isOlcrtc) {
                process = startOlcRtcProcessWithFallback(
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = true,
                    privileged = desktopMode == DesktopMode.LinuxTun
                )
                val olcRtcProcess = process ?: error("olcRTC process is missing")
                waitForOlcRtcReady(
                    process = olcRtcProcess,
                    ready = ready,
                    startupFailure = startupFailure,
                    socksPort = socksSettings.port,
                    requestGeneration = requestGeneration
                )
                if (coreRouting is Routing.Rules) {
                    frontPort = startOlcRtcFront(socksSettings, coreRouting, verboseLogs)
                }
            } else {
                startDesktopCore(location, effectiveSocksPort, coreRouting, verboseLogs)
            }

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            when (desktopMode) {
                DesktopMode.LinuxTun -> startLinuxTun(effectiveSocksPort, requestGeneration)
                DesktopMode.WindowsTun -> startWindowsTun(
                    effectiveSocksPort,
                    requestGeneration,
                    location,
                    isOlcrtc,
                    socksSettings
                )
                DesktopMode.MacTun -> {
                    val daemonRouting = if (rulesApply) {
                        Routing.Rules(
                            TunnelDaemonProtocol.RULES_DIR,
                            DirectDns.System,
                            requireNotNull(routingSettings.mode.region)
                        )
                    } else {
                        Routing.Global
                    }
                    startMacTun(
                        corePort = effectiveSocksPort,
                        isOlcrtc = isOlcrtc,
                        socksSettings = socksSettings,
                        location = location,
                        routing = daemonRouting,
                        verboseLogs = verboseLogs,
                        ruleFiles = if (daemonRouting is Routing.Rules) {
                            daemonRuleFiles(daemonRouting)
                        } else {
                            emptyMap()
                        }
                    )
                }
                DesktopMode.SystemProxy ->
                    startSystemProxy(
                        // The cores and the front listen without authentication;
                        // only the olcRTC engine, reached directly, uses the
                        // stored credentials.
                        if (isOlcrtc && frontPort == null) {
                            socksSettings.copy(port = effectiveSocksPort)
                        } else {
                            socksSettings.copy(
                                port = frontPort ?: effectiveSocksPort, username = "", password = ""
                            )
                        },
                        requestGeneration
                    )
            }

            if (isOlcrtc) {
                val olcRtcProcess = process ?: error("olcRTC process is missing")
                if (!olcRtcProcess.isAlive) {
                    error("olcRTC exited before desktop proxy was enabled")
                }
                startProcessExitWatchers(
                    desktopMode = desktopMode,
                    olcRtcProcess = olcRtcProcess,
                    currentTunProcess = tunProcess,
                    requestGeneration = requestGeneration
                )
            } else if (!singBoxCore.isRunning() && !xrayCore.isRunning()) {
                error("core exited before desktop proxy was enabled")
            }
            if (frontPort != null && !singBoxCore.isRunning()) {
                error("sing-box front exited before desktop proxy was enabled")
            }

            if (desktopMode == DesktopMode.MacTun) {
                startMacTunWatcher(requestGeneration)
            }

            // Prove it before claiming it. Every failure in the field so far reported
            // "connected" — a port collision, a rejected certificate, a browser that
            // never used the proxy — because the status meant "we ran the steps", not
            // "traffic reaches the internet".
            // In TUN mode the probe goes through the daemon's own inbound, so a
            // green light means the tun carried the request — not merely that the
            // core would have. That inbound has no auth; only the olcRTC core's has.
            val tunVerifyPort = if (desktopMode == DesktopMode.MacTun) macTunVerifyPort else null
            val verifiedThroughTun = tunVerifyPort != null
            // Through the front when there is one: it has no auth, and a green
            // light has to be about the chain the traffic actually takes.
            val directToOlcrtc = isOlcrtc && !verifiedThroughTun && frontPort == null
            channelProxy = if (desktopMode == DesktopMode.WindowsTun) windowsTunVerifyProxy else {
                SubscriptionFetchProxy(
                    socksSettings.host,
                    tunVerifyPort ?: (frontPort ?: effectiveSocksPort),
                    if (directToOlcrtc) socksSettings.username else "",
                    if (directToOlcrtc) socksSettings.password else ""
                )
            }
            val exit = if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunExit
            } else {
                org.olcbox.app.net.TunnelVerifier.verify(
                    socksHost = socksSettings.host,
                    socksPort = tunVerifyPort ?: (frontPort ?: effectiveSocksPort),
                    username = if (directToOlcrtc) socksSettings.username else "",
                    password = if (directToOlcrtc) socksSettings.password else ""
                )
            }
            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            val transport = when (desktopMode) {
                DesktopMode.LinuxTun -> "Desktop Linux TUN"
                DesktopMode.WindowsTun -> "Desktop Windows TUN"
                DesktopMode.MacTun -> "Desktop macOS TUN"
                DesktopMode.SystemProxy -> "Desktop proxy"
            }
            if (exit == null) {
                // The tunnel is up as far as we could set it up, but nothing came
                // back. Say so plainly rather than showing a green light.
                error("$transport is up but no traffic reached the internet through it")
            }
            _exitInfo.value = exit
            // LAN settings may change while the primary tunnel is still being
            // verified. Read the current value here so a regenerated password or
            // an off toggle cannot start a listener with the stale snapshot.
            val currentLanSettings = _socksProxySettings.value
            if (currentLanSettings.shareOnLan) {
                startLanSharing(currentLanSettings, channelProxy!!, requestGeneration)
            }
            setStatus(VpnStatus.Connected)
            addLog("$transport connected — exit ${exit.label()}")
        } catch (e: Exception) {
            if (e is CancellationException) {
                addLog("Desktop start cancelled")
            } else {
                addLog("Desktop start failed: ${e.message}")
            }

            stopDesktopMode(finalStatus = false)

            if (e !is CancellationException && requestGeneration == generation) {
                setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
            }
        }
    }

    private suspend fun startLinuxTun(socksPort: Int, requestGeneration: Long) {
        val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
        tunProcess = linuxTunController.start(hevBinary, socksPort)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
    }

    private suspend fun startWindowsTun(
        socksPort: Int,
        requestGeneration: Long,
        location: LocationConfig,
        isOlcrtc: Boolean,
        socksSettings: DesktopSocksProxySettings
    ) {
        val physicalInterface = windowsTunController.physicalInterface()
        DesktopNativeAssets.ensureWintunRuntime()
        val childProcesses = listOfNotNull(process, singBoxCore.runningProcess(), xrayCore.runningProcess())
        // ProcessHandle.Info.command() may be empty on Windows. Exact process
        // paths are useful when present; known resolved binaries are the safe
        // fallback for the process bypass rule.
        val resolvedPaths = buildList {
            add(DesktopNativeAssets.resolveSingBoxBinary().toString())
            val parsed = location.rawLink?.let(LinkParser::parse)
            if (parsed is org.olcbox.app.net.OutboundSpec.Vless &&
                parsed.transport is org.olcbox.app.net.TransportSpec.Xhttp
            ) add(DesktopNativeAssets.resolveXrayBinary().toString())
            if (isOlcrtc) addAll(DesktopNativeAssets.resolveOlcRtcBinaryCandidates().map(Path::toString))
        }
        val bypassPaths = (
            childProcesses.mapNotNull { it.info().command().orElse(null) } + resolvedPaths
        ).distinct()
        require(bypassPaths.isNotEmpty()) { "No VPN core to route outside the TUN" }

        val carrier = windowsCarrierRoute(location)
        var ready = false
        val overallDeadline = System.currentTimeMillis() + WINDOWS_TUN_TOTAL_TIMEOUT_MS
        for (attempt in 0 until WINDOWS_TUN_START_ATTEMPTS) {
            if (System.currentTimeMillis() >= overallDeadline) break
            val verifyPort = allocateVerifyPort(socksPort)
            val verifyUsername = UUID.randomUUID().toString()
            val verifyPassword = UUID.randomUUID().toString()
            val interfaceName = windowsTunInterfaceName(windowsTunSessionId, requestGeneration, attempt)
            windowsTunCore.start(
                org.olcbox.app.net.SingBoxConfig.buildDesktopTun(
                    corePort = socksPort,
                    verifyPort = verifyPort,
                    verifyUsername = verifyUsername,
                    verifyPassword = verifyPassword,
                    username = if (isOlcrtc) socksSettings.username else "",
                    password = if (isOlcrtc) socksSettings.password else "",
                    upstreamUdpIsLossy = isOlcrtc,
                    excludeAddresses = carrier.addresses,
                    directDnsDomains = carrier.domains,
                    routing = Routing.Global,
                    bindInterface = physicalInterface,
                    bypassProcessPaths = bypassPaths,
                    cacheFilePath = DesktopPaths.appDataDir().resolve("windows-tun-cache.db").toString(),
                    interfaceName = interfaceName
                )
            )
            windowsTunExit = awaitWindowsTunTraffic(
                requestGeneration, verifyPort, verifyUsername, verifyPassword,
                interfaceName, overallDeadline
            )
            ready = windowsTunExit != null && windowsTunCore.isRunning()
            if (ready) {
                windowsTunVerifyProxy = SubscriptionFetchProxy(
                    "127.0.0.1", verifyPort, verifyUsername, verifyPassword
                )
                break
            }
            windowsTunCore.stopNow()
            if (requestGeneration != generation) throw CancellationException("Desktop start superseded")
            if (attempt + 1 < WINDOWS_TUN_START_ATTEMPTS) {
                addLog("Windows TUN adapter was not ready; retrying with a fresh adapter")
                delay(WINDOWS_TUN_RETRY_DELAY_MS)
            }
        }
        if (!ready) error("Windows TUN did not carry its HTTPS verification request after retries; see the core log")
        tunProcess = windowsTunCore.runningProcess() ?: error("Windows TUN core exited")
        if (requestGeneration != generation) throw CancellationException("Desktop start superseded")
        startTunExitWatcher(tunProcess!!, requestGeneration)
        addLog("Windows TUN ready; carrier processes bypass via $physicalInterface")
    }

    private suspend fun awaitWindowsTunTraffic(
        requestGeneration: Long,
        verifyPort: Int,
        verifyUsername: String,
        verifyPassword: String,
        interfaceName: String,
        overallDeadline: Long
    ): org.olcbox.app.net.TunnelExit? {
        val deadline = minOf(overallDeadline, System.currentTimeMillis() + WINDOWS_TUN_READY_TIMEOUT_MS)
        while (System.currentTimeMillis() < deadline && windowsTunCore.isRunning()) {
            if (requestGeneration != generation) throw CancellationException("Desktop start superseded")
            val exit = org.olcbox.app.net.TunnelVerifier.verify(
                socksHost = "127.0.0.1",
                socksPort = verifyPort,
                username = verifyUsername,
                password = verifyPassword,
                timeoutMs = WINDOWS_TUN_PROBE_TIMEOUT_MS
            )
            if (exit != null && windowsTunController.ownsDefaultRoutes(interfaceName)) return exit
            delay(WINDOWS_TUN_RETRY_DELAY_MS)
        }
        return null
    }

    private suspend fun windowsCarrierRoute(location: LocationConfig): WindowsCarrierRoute {
        if (location.kind == LocationKind.Olcrtc) return WindowsCarrierRoute()
        val host = location.rawLink?.let(LinkParser::parse)?.host ?: return WindowsCarrierRoute()
        val addresses = withContext(Dispatchers.IO) {
            runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        }.mapNotNull { address ->
            when (address) {
                is java.net.Inet4Address -> "${address.hostAddress}/32"
                is java.net.Inet6Address -> "${address.hostAddress.substringBefore('%')}/128"
                else -> null
            }
        }.distinct()
        if (addresses.isEmpty()) addLog("Windows TUN: could not resolve carrier $host; using process bypass")
        val literal = runCatching { java.net.InetAddress.getByName(host).hostAddress == host }.getOrDefault(false)
        return WindowsCarrierRoute(addresses, if (literal) emptyList() else listOf(host))
    }

    private data class WindowsCarrierRoute(
        val addresses: List<String> = emptyList(),
        val domains: List<String> = emptyList()
    )

    private suspend fun startSystemProxy(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long
    ) {
        pacServer.start(
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password
        )
        proxyController.enable(
            org.olcbox.app.vpn.desktop.DesktopProxyTarget(
                pacUrl = pacServer.url,
                socksHost = socksSettings.host,
                socksPort = socksSettings.port,
                username = socksSettings.username,
                password = socksSettings.password
            )
        )

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }
    }

    /**
     * Start the macOS tunnel: the daemon runs a sing-box whose tun feeds the core
     * this manager has already started on localhost.
     *
     * olcRTC has no server host in a link — it is addressed by a room on somebody
     * else's SFU — so there is nothing to exclude from the tunnel for it, and
     * [serverEndpoint] returning null is the honest answer rather than a gap.
     */
    private suspend fun startMacTun(
        corePort: Int,
        isOlcrtc: Boolean,
        socksSettings: DesktopSocksProxySettings,
        location: LocationConfig,
        routing: Routing,
        verboseLogs: Boolean,
        ruleFiles: Map<String, String>
    ) {
        val verifyPort = allocateVerifyPort(corePort)
        macOsTunController.start(
            corePort = corePort,
            verifyPort = verifyPort,
            // Only olcRTC enforces them; the cores' own inbounds have no auth.
            username = if (isOlcrtc) socksSettings.username else "",
            password = if (isOlcrtc) socksSettings.password else "",
            serverHost = serverEndpoint(location)?.first,
            // olcRTC relays UDP over a lossy video carrier, so DNS takes the
            // reliable path. The native transports carry UDP themselves.
            upstreamUdpIsLossy = isOlcrtc,
            routing = routing,
            verboseLogs = verboseLogs,
            ruleFiles = ruleFiles
        )
        macTunVerifyPort = verifyPort
        macTunActive = true
    }

    /**
     * A port for the TUN process's own verification inbound, never the carrier core's.
     *
     * Verifying through the core's port would prove the core works and say
     * nothing about the tun in front of it — which is the half that is new, so it
     * is the half a green light has to be about.
     */
    private fun allocateVerifyPort(corePort: Int): Int {
        val preferred = corePort + 1
        if (isLocalPortFree(preferred)) return preferred
        return runCatching {
            java.net.ServerSocket().use { socket ->
                socket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                socket.localPort
            }
        }.getOrNull() ?: preferred
    }

    /** Start a sing-box (reality/hy2) or Xray (xhttp) core on the core SOCKS port. */
    private suspend fun startDesktopCore(
        location: LocationConfig,
        port: Int,
        routing: Routing,
        verboseLogs: Boolean
    ) {
        val raw = location.rawLink ?: error("core location has no link")
        val spec = org.olcbox.app.net.LinkParser.parse(raw) ?: error("unparseable core link")
        stopDesktopCores()
        val xhttp = (spec as? org.olcbox.app.net.OutboundSpec.Vless)
            ?.takeIf { it.transport is org.olcbox.app.net.TransportSpec.Xhttp }
        // Which processes must be alive once the port answers. A port that
        // answers proves nothing about who answers.
        val alive: () -> Boolean
        if (xhttp != null && routing is Routing.Rules) {
            // Xray does not route; sing-box does, so it goes in front.
            val xrayPort = allocateVerifyPort(port)
            xrayCore.start(
                org.olcbox.app.net.XrayConfig.buildXhttp(
                    xhttp,
                    socksPort = xrayPort,
                    verboseLogs = verboseLogs
                )
            )
            singBoxCore.start(
                org.olcbox.app.net.SingBoxConfig.buildSocksChain(
                    xrayPort,
                    socksPort = port,
                    routing = routing,
                    verboseLogs = verboseLogs
                )
            )
            addLog("Xray/xhttp core on 127.0.0.1:$xrayPort behind a sing-box front on 127.0.0.1:$port")
            alive = { singBoxCore.isRunning() && xrayCore.isRunning() }
        } else if (xhttp != null) {
            xrayCore.start(
                org.olcbox.app.net.XrayConfig.buildXhttp(
                    xhttp,
                    socksPort = port,
                    verboseLogs = verboseLogs
                )
            )
            addLog("Xray/xhttp core starting on 127.0.0.1:$port")
            alive = xrayCore::isRunning
        } else {
            singBoxCore.start(
                org.olcbox.app.net.SingBoxConfig.build(
                    spec,
                    socksPort = port,
                    routing = routing,
                    verboseLogs = verboseLogs
                )
            )
            addLog("sing-box core (${location.kind}) starting on 127.0.0.1:$port")
            alive = singBoxCore::isRunning
        }
        if (!waitForCoreSocks(port) || !alive()) {
            val exit = if (xhttp != null) xrayCore.exitCodeOrNull() else singBoxCore.exitCodeOrNull()
            error(
                "core SOCKS not ready on 127.0.0.1:$port" +
                    (exit?.let { " (core exited with code $it — see the lines above)" } ?: "")
            )
        }
        addLog("core ready on 127.0.0.1:$port")
    }

    /**
     * sing-box between the proxy and olcRTC, so the routing rules see every
     * connection before the relay does. The engine keeps its own port and its
     * credentials; the front listens without any, like the cores do.
     */
    private suspend fun startOlcRtcFront(
        socksSettings: DesktopSocksProxySettings,
        routing: Routing.Rules,
        verboseLogs: Boolean
    ): Int {
        stopDesktopCores()
        val port = allocateCorePort()
        singBoxCore.start(
            org.olcbox.app.net.SingBoxConfig.buildSocksChain(
                upstreamPort = socksSettings.port,
                socksPort = port,
                username = socksSettings.username,
                password = socksSettings.password,
                routing = routing,
                verboseLogs = verboseLogs
            )
        )
        addLog("sing-box front for olcRTC starting on 127.0.0.1:$port")
        if (!waitForCoreSocks(port) || !singBoxCore.isRunning()) {
            error(
                "sing-box front not running on 127.0.0.1:$port" +
                    (singBoxCore.exitCodeOrNull()?.let { " (exited with code $it — see the lines above)" } ?: "")
            )
        }
        activeCorePort = port
        addLog("sing-box front ready on 127.0.0.1:$port")
        return port
    }

    /**
     * The rule-set files, written under the app's data directory for the
     * proxy's core. Rewritten on every start: 59 KB, and the alternative is
     * a version check that can be wrong.
     */
    private suspend fun installRuleSets(routing: Routing.Rules) {
        val dir = Path.of(routing.ruleSetDir)
        Files.createDirectories(dir)
        for (file in org.olcbox.app.net.RuleSets.selected(routing)) {
            Files.write(dir.resolve(file.name), org.olcbox.app.net.RuleSets.bytes(file))
        }
    }

    /** The same files for the macOS daemon, which writes them itself, root-owned. */
    private suspend fun daemonRuleFiles(routing: Routing.Rules): Map<String, String> =
        org.olcbox.app.net.RuleSets.selected(routing).associate {
            it.name to java.util.Base64.getEncoder().encodeToString(org.olcbox.app.net.RuleSets.bytes(it))
        }

    /**
     * Port for the sing-box/Xray SOCKS listener.
     *
     * Prefers the well-known core port so logs stay predictable, but never insists
     * on it: the PAC server, an olcRTC session or an unrelated app on the user's
     * machine may already hold it, and binding a taken port used to fail the whole
     * connect with a bare "Address already in use".
     */
    private suspend fun allocateCorePort(): Int {
        val preferred = org.olcbox.app.net.SingBoxConfig.SINGBOX_SOCKS_PORT
        // A core that was just told to stop can hold its listener for a moment;
        // wait that out before giving up on the predictable port.
        val deadline = System.currentTimeMillis() + CORE_PORT_RELEASE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isLocalPortFree(preferred)) return preferred
            delay(CORE_PORT_RELEASE_POLL_MS)
        }
        val fallback = runCatching {
            java.net.ServerSocket().use { socket ->
                socket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                socket.localPort
            }
        }.getOrNull() ?: preferred
        addLog("core port $preferred is busy, using $fallback")
        return fallback
    }

    private fun isLocalPortFree(port: Int): Boolean = runCatching {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)

    private fun stopDesktopCores() {
        singBoxCore.stopNow()
        xrayCore.stopNow()
        activeCorePort = null
    }

    private suspend fun startLanSharing(
        settings: DesktopSocksProxySettings,
        upstream: SubscriptionFetchProxy,
        requestGeneration: Long
    ) {
        try {
            _lanProxyHealth.value = "Checking"
            val endpoint = lanProxy.start(settings, upstream)
            val exit = org.olcbox.app.net.TunnelVerifier.verify(
                socksHost = settings.lanAddress,
                socksPort = settings.lanPort,
                username = settings.lanUsername,
                password = settings.lanPassword
            ) ?: error("the LAN listener did not carry traffic through the VPN")
            if (requestGeneration != generation) throw CancellationException("Desktop start superseded")
            _lanProxyEndpoint.value = endpoint
            _lanProxyHealth.value = "Healthy · ${exit.label()}"
            addLog("LAN sharing ready on the selected private interface; authenticated tunnel check passed")
            lanWatchJob?.cancel()
            lanWatchJob = scope.launch {
                while (isActive && requestGeneration == generation) {
                    delay(LAN_HEALTH_INTERVAL_MS)
                    val healthy = lanProxy.isRunning() && org.olcbox.app.net.TunnelVerifier.verify(
                        socksHost = settings.lanAddress,
                        socksPort = settings.lanPort,
                        username = settings.lanUsername,
                        password = settings.lanPassword,
                        timeoutMs = LAN_HEALTH_TIMEOUT_MS
                    ) != null
                    if (!healthy) {
                        lanProxy.stop()
                        _lanProxyEndpoint.value = null
                        _lanProxyHealth.value = "Stopped · tunnel health check failed"
                        addLog("LAN sharing stopped because its tunnel health check failed")
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            lanProxy.stop()
            throw e
        } catch (e: Exception) {
            lanProxy.stop()
            _lanProxyEndpoint.value = null
            _lanProxyHealth.value = "Unavailable · ${e.message ?: "startup failed"}"
            addLog("LAN sharing could not start: ${e.message}")
        }
    }

    private suspend fun waitForCoreSocks(
        port: Int,
        timeoutMs: Long = CORE_SOCKS_READY_TIMEOUT_MS,
        isAlive: () -> Boolean = { true }
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (canConnectToSocks(port)) return true
            if (!isAlive()) return false
            delay(CORE_SOCKS_POLL_MS)
        }
        return canConnectToSocks(port)
    }

    private fun startOlcRtcProcessWithFallback(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        val dnsServer = DesktopDnsResolver.current()
        var lastException: Exception? = null

        addLog("Using DNS server $dnsServer for olcRTC")

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    binary = binary,
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = logOutput,
                    privileged = privileged,
                    dnsServer = dnsServer
                )
            } catch (e: Exception) {
                lastException = e

                if (binary == binaries.last()) break

                addLog("olcRTC start failed for ${binary.fileName}: ${e.message}. Retrying with fallback binary.")
            }
        }

        throw lastException ?: error("olcRTC binary failed to start")
    }

    private suspend fun stopDesktopMode(finalStatus: Boolean) {
        // The LAN listener is externally reachable. Close it before changing the
        // tunnel it chains to, including partially started and already-disconnected paths.
        lanWatchJob?.cancel()
        lanWatchJob = null
        lanControlJob?.cancel()
        lanControlJob = null
        lanProxy.stop()
        _lanProxyEndpoint.value = null
        _lanProxyHealth.value = null
        // macTunActive belongs in this guard: on macOS the cores are owned by
        // their own controllers and the tun by the daemon, so both `process` and
        // `tunProcess` are null while a tunnel is very much up. Without it a stop
        // arriving in any non-Connected state would return here and leave the
        // machine routed through a tunnel nothing is feeding.
        if (_status.value is VpnStatus.Disconnected &&
            process == null &&
            tunProcess == null &&
            !macTunActive
        ) {
            cancelProcessJobs()
            return
        }

        val wasMacTun = macTunActive
        setStatus(VpnStatus.Stopping)
        cancelProcessJobs()

        when (DesktopPaths.os) {
            DesktopOs.Linux -> {
                runCatching {
                    linuxTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Linux TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopOs.Windows -> {
                runCatching {
                    windowsTunCore.stopNow()
                    windowsTunExit = null
                    windowsTunVerifyProxy = null
                }.onFailure {
                    addLog("Windows TUN stop failed: ${it.message}")
                }
                runCatching { proxyController.restore() }
                    .onFailure { addLog("Windows proxy restore failed: ${it.message}") }
                tunProcess = null
            }
            DesktopOs.MacOS,
            DesktopOs.Other -> {
                // Both, and in this order. A session may have used either mode —
                // the daemon can be approved between one connect and the next —
                // and restoring a proxy that was never set is a no-op, while
                // leaving a tun up is a Mac with no network.
                if (macTunActive) {
                    runCatching {
                        macOsTunController.stop()
                    }.onFailure {
                        addLog("macOS TUN stop failed: ${it.message}")
                    }
                    macTunActive = false
                    macTunVerifyPort = null
                }
                runCatching {
                    proxyController.restore()
                }.onFailure {
                    addLog("Proxy restore failed: ${it.message}")
                }
            }
        }

        pacServer.stop()

        stopDesktopCores()
        stopProcess(process)
        process = null
        deleteOlcRtcConfig()

        if (finalStatus) {
            _exitInfo.value = null
        setStatus(VpnStatus.Disconnected)
            addLog(
                when (DesktopPaths.os) {
                    DesktopOs.Linux -> "Desktop Linux TUN stopped"
                    DesktopOs.Windows -> "Desktop Windows TUN stopped"
                    DesktopOs.MacOS -> if (wasMacTun) "Desktop macOS TUN stopped" else "Desktop proxy stopped"
                    DesktopOs.Other -> "Desktop proxy stopped"
                }
            )
        }
    }

    private fun cancelProcessJobs() {
        macTunWatchJob?.cancel()
        macTunWatchJob = null
        processWatchJob?.cancel()
        processWatchJob = null

        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = null

        logJob?.cancel()
        logJob = null

        tunLogJob?.cancel()
        tunLogJob = null
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean,
        dnsServer: String
    ): Process {
        val config = location.normalized()
        val provider = OlcRtcCommand.desktopProviderArg(config.bypassProvider)
        val olcRtcCommand = OlcRtcCommand(
            binary = binary,
            location = config,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            dnsServer = dnsServer
        )
        val configPath = writeOlcRtcClientConfig(olcRtcCommand)
        val command = olcRtcCommand.args(configPath)

        // The room id is a capability, not a name. Left out of the message rather
        // than left to the scrubber's UUID rule — that is one rule away from a leak.
        addLog("Starting olcRTC provider=$provider, transport=${config.transport}, port=${socksSettings.port}")

        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command) else command
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            if (olcRtcConfigPath == configPath) {
                olcRtcConfigPath = null
            }
            throw e
        }

        val readerJob = scope.launch {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        if (logOutput) {
                            val message = "rtc: $line"
                            addLog(message)
                            // stdout is a second sink and bypasses addLog. Scrubbing an
                            // already-scrubbed line is a no-op, so this stays simple.
                            println(LogScrubber.default.scrub(message))
                        }

                        if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                            ready.complete(Unit)
                        }

                        if (isFatalOlcRtcStartupLine(line)) {
                            startupFailure.complete(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // Process stdout may close while stopping or after a remote disconnect.
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        return startedProcess
    }

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, "olcrtc-client-", ".yaml")
        Files.writeString(path, command.yaml(), StandardCharsets.UTF_8)
        deleteOlcRtcConfig()
        olcRtcConfigPath = path
        return path
    }

    private fun deleteOlcRtcConfig() {
        olcRtcConfigPath?.let { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        olcRtcConfigPath = null
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()

        tunLogJob = scope.launch {
            try {
                target.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        val message = "tun: $line"
                        addLog(message)
                        // Same reason as the rtc reader above: stdout bypasses addLog.
                        println(LogScrubber.default.scrub(message))
                    }
                }
            } catch (_: IOException) {
                // TUN stdout may close while the process is being stopped.
            }
        }
    }

    private fun startProcessExitWatchers(
        desktopMode: DesktopMode,
        olcRtcProcess: Process,
        currentTunProcess: Process?,
        requestGeneration: Long
    ) {
        startOlcRtcExitWatcher(olcRtcProcess, requestGeneration)

        when (desktopMode) {
            DesktopMode.LinuxTun,
            DesktopMode.WindowsTun -> startTunExitWatcher(
                currentTunProcess ?: error("TUN process is missing"),
                requestGeneration
            )
            // Neither has a tun process in *this* JVM to watch: the proxy has no
            // tun at all, and on macOS the tun belongs to the root daemon's child.
            // A daemon-side death therefore goes unnoticed here until the next
            // command — noted in docs/macos-tunnel-daemon.md rather than papered
            // over with a poll that would still be a guess.
            DesktopMode.MacTun,
            DesktopMode.SystemProxy -> {
                tunProcessWatchJob?.cancel()
                tunProcessWatchJob = null
            }
        }
    }

    private fun startOlcRtcExitWatcher(target: Process, requestGeneration: Long) {
        processWatchJob?.cancel()
        processWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || process !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "olcRTC process exited unexpectedly with code $exitCode",
                        errorMessage = "olcRTC exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    /**
     * Notices when the daemon's sing-box dies.
     *
     * Linux and Windows own their tun process and learn of its death from the
     * OS. Here the tun belongs to a root daemon, this process holds no handle on
     * it, and without this the app would keep showing a green light over a
     * tunnel that stopped carrying anything — the machine still routed into a
     * tun with nothing behind it, which is silent rather than obviously broken.
     *
     * Asked rather than pushed, deliberately. A notification would mean a
     * long-lived connection and the state to manage it inside the one component
     * that runs as root, and that component is worth keeping as small as it is.
     * A question every few seconds over a unix socket costs a few bytes and
     * bounds the delay at one interval.
     */
    private fun startMacTunWatcher(requestGeneration: Long) {
        macTunWatchJob?.cancel()
        macTunWatchJob = scope.launch {
            while (isActive) {
                delay(MAC_TUN_WATCH_INTERVAL_MS)
                if (requestGeneration != generation || !macTunActive) return@launch
                if (macOsTunController.isRunning()) continue

                // Asked twice, because "not running" also comes back when the
                // daemon is busy or the socket blinked, and tearing a working
                // tunnel down over one unanswered question is worse than
                // noticing a real death a few seconds later.
                delay(MAC_TUN_WATCH_INTERVAL_MS)
                if (requestGeneration != generation || !macTunActive) return@launch
                if (macOsTunController.isRunning()) continue

                // In a coroutine of its own, exactly as the process watchers do
                // it: the handler calls stopDesktopMode, stopDesktopMode cancels
                // this job, and cleanup running inside the job being cancelled
                // would stop at its first suspension point — with the tunnel
                // still up and the status still wrong.
                scope.launch {
                    mutex.withLock {
                        if (requestGeneration != generation) return@withLock
                        handleUnexpectedProcessExit(
                            logMessage = "the tunnel daemon is no longer running sing-box",
                            errorMessage = "the system-wide tunnel stopped unexpectedly",
                            requestGeneration = requestGeneration
                        )
                    }
                }
                return@launch
            }
        }
    }

    private fun startTunExitWatcher(target: Process, requestGeneration: Long) {
        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || tunProcess !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "TUN process exited unexpectedly with code $exitCode",
                        errorMessage = "TUN process exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private suspend fun handleUnexpectedProcessExit(
        logMessage: String,
        errorMessage: String,
        requestGeneration: Long
    ) {
        addLog(logMessage)
        stopDesktopMode(finalStatus = false)

        if (requestGeneration == generation) {
            setStatus(VpnStatus.Error(errorMessage))
        }
    }

    private fun waitForProcessExit(target: Process): Int? {
        return try {
            target.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        socksPort: Int,
        requestGeneration: Long? = null
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (ready.isCompleted || canConnectToSocks(socksPort)) {
                waitForOlcRtcStartupStability(process, startupFailure, requestGeneration)
                return
            }

            if (!process.isAlive) {
                error("olcRTC exited before SOCKS5 was ready")
            }

            delay(READY_POLL_INTERVAL_MS)
        }

        error("olcRTC start timed out")
    }

    private suspend fun waitForOlcRtcStartupStability(
        process: Process,
        startupFailure: CompletableDeferred<String>,
        requestGeneration: Long?
    ) {
        val deadline = System.currentTimeMillis() + OLC_STARTUP_STABILITY_MS
        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (!process.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private fun canConnectToSocks(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port),
                    TCP_CONNECT_TIMEOUT_MS.toInt()
                )
            }
        }.isSuccess
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (!target.isAlive) return

        target.toHandle().descendants().forEach {
            it.destroy()
        }

        target.destroy()

        if (!target.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            target.toHandle().descendants().forEach {
                it.destroyForcibly()
            }

            target.destroyForcibly()
            target.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun setStatus(status: VpnStatus) {
        if (status is VpnStatus.Connected && channelProbe == null) {
            channelProbe = channelProxy?.let { org.olcbox.app.net.ChannelLatency.Session(it) }
        } else if (status !is VpnStatus.Connected) {
            channelProbe?.close()
            channelProbe = null
        }
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        _connectedSince.value = when (status) {
            // Only the first Connected of a session stamps the clock; a
            // reconnect passes through Reconnecting and back, and must not
            // restart it.
            VpnStatus.Connected -> _connectedSince.value ?: nowMillis()
            VpnStatus.Reconnecting -> _connectedSince.value
            else -> null
        }
    }

    private fun addLog(message: String) {
        // Here and not one line earlier: the raw core output is matched for transport
        // state (see waitForCoreSocks's neighbour at the SOCKS5-listening check), and
        // a scrubbed line reaching that matcher would break reconnect silently.
        val safe = LogScrubber.default.scrub(message)
        _logs.update {
            (it + safe).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private companion object {
        const val DIAGNOSTICS_TIMEOUT_MS = 2_000L
        const val MAX_LOG_ENTRIES = 5_000
        const val CORE_SOCKS_READY_TIMEOUT_MS = 10_000L
        const val WINDOWS_TUN_READY_TIMEOUT_MS = 45_000L
        const val WINDOWS_TUN_TOTAL_TIMEOUT_MS = 60_000L
        const val WINDOWS_TUN_START_ATTEMPTS = 3
        const val WINDOWS_TUN_RETRY_DELAY_MS = 1_200L
        const val WINDOWS_TUN_PROBE_TIMEOUT_MS = 5_000L
        /** How long a stopped core may keep holding its port before we move on. */
        const val CORE_PORT_RELEASE_TIMEOUT_MS = 1_500L
        const val CORE_PORT_RELEASE_POLL_MS = 100L
        const val CORE_SOCKS_POLL_MS = 200L
        /** As on the phones: a WB Stream join alone may take the engine's 25 s. */
        const val OLC_READY_TIMEOUT_MS = 35_000L
        const val OLC_STARTUP_STABILITY_MS = 1_500L
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        /** Two of these is the worst-case delay before a dead tunnel is reported. */
        const val MAC_TUN_WATCH_INTERVAL_MS = 4_000L
        const val LAN_HEALTH_INTERVAL_MS = 15_000L
        const val LAN_HEALTH_TIMEOUT_MS = 5_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4

        internal fun isFatalOlcRtcStartupLine(line: String): Boolean {
            val text = line.lowercase()
            return "failed to connect link" in text ||
                    "join room failed" in text ||
                    "get room token" in text && "failed" in text ||
                    "transport connect" in text && "failed" in text ||
                    "incompatible olcrtc protocol" in text ||
                    "did not answer the handshake" in text ||
                    "key does not match the peer" in text ||
                    "no peer in room" in text
        }
    }
}

internal fun windowsTunInterfaceName(
    sessionId: String,
    requestGeneration: Long,
    attempt: Int
): String = "Ghostlane-$sessionId-${requestGeneration.toString(16)}-${attempt + 1}"

/**
 * How this desktop puts traffic through the tunnel.
 *
 * Top-level rather than nested in the manager so that the one decision worth
 * testing — which mode a Mac gets — can be tested without standing up a manager,
 * its coroutine scope and its two cores.
 */
internal enum class DesktopMode {
    LinuxTun,
    WindowsTun,
    MacTun,
    SystemProxy;

    companion object {
        /**
         * The platform decides what is possible, the person decides among what is
         * left. Linux has no system-proxy implementation, so its answer does not
         * depend on the preference at all.
         */
        fun current(): DesktopMode {
            val wantsProxy = DesktopConnectionModePreference.selected() == DesktopConnectionMode.Proxy
            return when (DesktopPaths.os) {
                DesktopOs.Linux -> LinuxTun
                DesktopOs.Windows -> if (wantsProxy) SystemProxy else WindowsTun
                DesktopOs.MacOS ->
                    if (wantsProxy) SystemProxy else macOsModeFor(MacOsTunnelDaemon.status())
                DesktopOs.Other -> SystemProxy
            }
        }
    }
}

/**
 * Only an approved daemon earns TUN mode.
 *
 * Every other state — not installed, waiting for approval, missing from the
 * build, a macOS too old to have SMAppService — keeps the SOCKS proxy that has
 * always worked. A connect is the worst possible moment to discover that a root
 * component needs a trip to System Settings, and a user who never installs the
 * daemon should see no change at all.
 */
internal fun macOsModeFor(daemon: MacOsTunnelDaemon.Registration): DesktopMode =
    if (daemon == MacOsTunnelDaemon.Registration.Enabled) DesktopMode.MacTun else DesktopMode.SystemProxy
