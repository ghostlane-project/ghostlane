package org.olcbox.app.vpn


import org.olcbox.app.net.toRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.DesktopChannelProbe
import org.olcbox.app.net.DesktopSingBoxController
import org.olcbox.app.net.DesktopXrayController
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcDirectRules
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.desktop.TunnelDaemonProtocol
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.desktop.CoreShape
import org.olcbox.app.vpn.desktop.DeadProcess
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.LineChange
import org.olcbox.app.vpn.desktop.LineConfigs
import org.olcbox.app.vpn.desktop.LineSupervision
import org.olcbox.app.vpn.desktop.LinuxKillSwitch
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.MacOsTunController
import org.olcbox.app.vpn.desktop.MacOsTunnelDaemon
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.SessionBuild
import org.olcbox.app.vpn.desktop.SessionEndpoint
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
    private var coreWatchJob: Job? = null

    /**
     * The restart of a dead core or engine, from the moment the death is noticed
     * until the session is verified again; and the same for a line that was
     * changed to, from the change until that line is verified.
     *
     * Non-null is the whole meaning of "a restart is running", and it is set and
     * cleared under [mutex] rather than read off the job's own state: a job that
     * has just verified the session stays active for a moment after it lets the
     * mutex go, and a death reported in that moment would be taken for one it is
     * still handling, and left to nobody.
     */
    private var lineRestartJob: Job? = null
    private var process: Process? = null

    /**
     * The engine process [processWatchJob] waits on. A restart that left the
     * engine as it was (only the front had died) must not put a second waiter
     * on it, and one that replaced it, in whichever of its attempts, must not
     * leave the new one unwatched.
     */
    private var watchedEngine: Process? = null
    private var tunProcess: Process? = null
    private var olcRtcConfigPath: Path? = null
    private var olcRtcDirectRulesPath: Path? = null
    private var generation = 0L

    /**
     * The generation of the request whose line is running or being started:
     * the connect's, and after it that of every change of location made behind
     * the tun.
     *
     * The waiter on the tun's own process compares this and not the generation
     * it was armed with. A change of location moves [generation] and leaves
     * the tun where it is, and the waiter with it: armed with the connect's
     * generation it would take the tun's death, after any change of location,
     * for one that a newer request is already dealing with, and report it to
     * nobody. It cannot be armed again instead, as the daemon's watcher is: it
     * blocks a thread on the process until the process exits, and one more
     * would be left blocked there by every change. Written and read under
     * [mutex].
     */
    private var lineGeneration = 0L
    /** Separates adapter names from adapters retained by an earlier app process. */
    private val windowsTunSessionId = UUID.randomUUID().toString().take(8)
    private val linuxTunController = LinuxTunController(::addLog) { olcRtcConfigNaming() }
    private val windowsTunController = WindowsTunController(::addLog)
    private val macOsTunController = MacOsTunController(::addLog)

    /** The daemon's own socks inbound, which is what a green light is measured through. */
    private var macTunVerifyPort: Int? = null
    private var windowsTunExit: org.olcbox.app.net.TunnelExit? = null

    /**
     * The adapter of the Windows tun that passed its check. A check made later
     * in the session asks about the same adapter's routes, and the name is drawn
     * per attempt, so it cannot be worked out again.
     */
    private var windowsTunInterface: String? = null
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

    /**
     * What a session holds from its first verified connection until it is
     * stopped, as far as a line that comes later has to know it. The tun, or
     * in proxy mode the system's proxy setting, is built once, for the first
     * line, and no change of location touches it. So every line after the
     * first is made to fit what is written here.
     */
    private data class HeldSession(
        /** What it was built with; another location is judged against it ([LineSupervision.changeOfLine]). */
        val built: SessionBuild,
        /** What the tun, or the system's proxy setting, points at. Every line listens there. */
        val endpoint: SessionEndpoint,
        /**
         * The tun's own inbound, where the tun has one for the check to go
         * through (Windows and macOS). Null where the check goes to the
         * endpoint itself.
         */
        val tunListener: SubscriptionFetchProxy?,
        /**
         * The machine's own resolvers as they were read before the tun came
         * up, for a core started beside it later. Null in proxy mode, where
         * the system's resolver is behind nothing.
         */
        val resolvers: List<String>?,
        /** Whether the first line was a room: the tun was told so, and goes on believing it. */
        val startedInRoom: Boolean
    )

    /**
     * What the line of a connected session was started with, so that a core or
     * an engine that dies can be started again exactly as it was.
     *
     * Remembered, not read again, because every value here was built into
     * something that outlives the process it describes. The tun's upstream is
     * [corePort], with the login in [socksSettings] for olcRTC, for as long as
     * the tun lives; the system proxy points at [frontPort]; the engine's yaml
     * names [engineRules]. The settings behind them can change while a session
     * runs (the SOCKS port, the routing, the handshake), and a process brought
     * back with today's values would listen where the tun no longer looks.
     *
     * After a change of location it describes the line that was changed to,
     * which was not started with its own port and login but with the
     * session's ([session], [lineBehindTun]).
     */
    private data class SessionLine(
        /** What the session holds; the same for every line of it. */
        val session: HeldSession,
        val location: LocationConfig,
        val isOlcrtc: Boolean,
        val socksSettings: DesktopSocksProxySettings,
        /** Where the line listens: the core's port, or for olcRTC the engine's own. */
        val corePort: Int,
        val coreRouting: Routing,
        val verboseLogs: Boolean,
        /** The engine's direct-rules file, written at connect and kept until the session stops. */
        val engineRules: Path?,
        val dtlsProfile: String,
        val desktopMode: DesktopMode,
        /** The sing-box front before the engine, in proxy mode under rules; null otherwise. */
        val frontPort: Int?,
        /**
         * The listener the session was verified through, and its login: the
         * tun's own inbound where the tun has one, else the front, else the
         * line. A restart is verified through the same one, so that Connected
         * keeps meaning what it meant at connect.
         */
        val verifiedThrough: SubscriptionFetchProxy,
        /** On Windows the check also asks whose the default routes are; null elsewhere. */
        val windowsTunInterface: String?,
        /**
         * Whether the sing-box and Xray processes of this line are all running,
         * as whoever started them tells it; null for a room reached directly,
         * which has none.
         */
        val coresAlive: (() -> Boolean)?,
        /**
         * The login the line's core, or the front before its engine, demands
         * of whoever connects to it: the endpoint's, for a line that came
         * later. Null for the first line, whose listener is the endpoint as it
         * stands. An engine reached directly takes its login from
         * [socksSettings].
         */
        val login: SocksLogin? = null,
        /**
         * For a line that came later in a tun session: the resolvers its core
         * asks for the server's name, and its engine for the meeting
         * service's. Null for the first line, which is started as the connect
         * started it.
         */
        val resolvers: List<String>? = null
    )

    /**
     * Null until a session is verified, and again once it is stopped: a connect
     * that never carried traffic fails as it always did, and only a session that
     * did has a line worth bringing back.
     */
    @Volatile private var sessionLine: SessionLine? = null

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
                    // Inside a session another location is another line behind
                    // what the session holds, which is not taken down for it.
                    if (changeLineBehindTun(requestGeneration)) return@withLock
                    // Superseded while that was being decided. The newer
                    // request decides for itself, and has to find the session
                    // as it is, not torn down for a request nobody wants any
                    // more.
                    if (requestGeneration != generation) return@withLock

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
            deviceId = locationsRepository.getDeviceIdentity(),
            dtlsProfile = OlcrtcDtls.profile(locationsRepository.getRoutingSettings().olcrtcChromeDtls)
        )
    }

    // Not while the Linux kill switch holds traffic: a probe's core goes out as
    // any program of the user's does, the block refuses it, and smart connect
    // would take every transport for blocked and move the line to its last resort.
    override val canProbeTransports: Boolean get() = !linuxTunController.holdsTraffic

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
            deviceId = locationsRepository.getDeviceIdentity(),
            dtlsProfile = OlcrtcDtls.profile(locationsRepository.getRoutingSettings().olcrtcChromeDtls)
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


    /**
     * Stores the SOCKS settings, and nothing else.
     *
     * It used to point the PAC at the port in the settings as well, at once.
     * Inside a session that is the wrong target more often than not: the PAC
     * was given the session's endpoint when the session started
     * ([startSystemProxy]), and that is a core's port, or the front's,
     * whenever the line is not a room reached directly. It went unnoticed
     * because every save was followed by a full restart, which set the PAC
     * again. A full restart no longer always follows: when the settings are,
     * by the time the request is looked at, what the session started with
     * again, only the line is restarted, and the PAC would have stayed where
     * the save put it. So the PAC is the session's, set when the session
     * starts and not touched until it stops. A change of the port or the
     * login is still a full restart ([LineSupervision.changeOfLine]), and
     * that is what applies it.
     */
    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        _socksProxySettings.value = DesktopSocksProxySettings(
            port = port,
            username = username,
            password = password
        ).normalized()
    }

    /** As above: stored, and the PAC left to the session. */
    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        _socksProxySettings.value = settings.normalized()
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

    /**
     * Apply LAN-only changes without tearing down and rebuilding the VPN tunnel.
     *
     * The settings are stored and nothing else: the system proxy keeps the
     * target the session gave it. This used to go through
     * [updateSocksProxySettings], which pointed the PAC at the port in the
     * settings. That is the olcRTC engine's port, and the session's target is
     * another one whenever a core carries the line or a front stands before the
     * engine (see where [startSystemProxy] is called). So switching LAN sharing
     * on or off in proxy mode left the browser asking a port nobody listened
     * on, or the engine past the front and its routing rules, until the next
     * connect.
     */
    fun applyLanSharingSettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
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

    /**
     * The Linux tunnel's kill switch ([LinuxKillSwitch]). It is read when the
     * tunnel starts, so a change applies at the next connect.
     *
     * Switching it off also ends a hold, and is the one thing besides a new
     * verified session that does. While the block stands with no tunnel, the
     * stop that follows removes it, cleanup and its password included: the
     * screen has no Disconnect while it shows an error, so this is the way
     * out that does not start a connection. Under a running tunnel nothing
     * can be taken away without root, so the block is only no longer kept,
     * and the session ends as it does without the switch.
     */
    fun setKillSwitch(enabled: Boolean) {
        _socksProxySettings.update { it.copy(killSwitch = enabled) }
        if (enabled) return
        val holding = linuxTunController.holdsTraffic
        linuxTunController.endHold()
        if (holding) stopVpn()
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
        // The Linux kill switch's block outlives the app as it outlives the
        // tunnel. Found at start, it is a machine with no network and an app
        // that says "not connected": setStatus says what holds the traffic and
        // how to let it out. Not removed here, which would be the cleanup
        // nobody asked for, with its password dialog.
        if (DesktopPaths.os == DesktopOs.Linux) {
            scope.launch {
                mutex.withLock {
                    if (_status.value is VpnStatus.Disconnected && linuxTunController.findLeftoverBlock()) {
                        setStatus(VpnStatus.Disconnected)
                    }
                }
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
        lineGeneration = requestGeneration
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

            val routingSettings = locationsRepository.getRoutingSettings()
            val verboseLogs = routingSettings.verboseDebugLogs
            val isOlcrtc = location.kind == org.olcbox.app.net.LocationKind.Olcrtc
            // Where the rules apply, if anywhere (desktopRulesHome). Global with
            // no rules of the user's keeps every core and tunnel as it was.
            val rulesHome = desktopRulesHome(desktopMode, isOlcrtc, routingSettings.needsRules)
            if (routingSettings.needsRules && rulesHome == DesktopRulesHome.Nowhere) {
                addLog("Routing: $desktopMode carries everything for this server")
            } else if (rulesHome != DesktopRulesHome.Nowhere) {
                addLog("Routing: ${routingSettings.mode.hubSummary()}")
            }
            // In the proxy the rules live in the core, or for olcRTC in the front
            // before it; in the macOS tunnel they live in the daemon, and the core
            // stays as it was.
            val coreRouting: Routing = if (rulesHome == DesktopRulesHome.Core) {
                val routing = routingSettings.toRules(
                    DesktopPaths.appDataDir().resolve("rulesets").toString(),
                    DirectDns.System
                )
                installRuleSets(routing)
                routing
            } else {
                Routing.Global
            }
            // In the Linux tunnel the engine takes them itself, from a file its
            // yaml names.
            val engineRules = if (rulesHome == DesktopRulesHome.Engine) {
                writeOlcRtcDirectRules(routingSettings)
            } else {
                null
            }
            val dtlsProfile = OlcrtcDtls.profile(routingSettings.olcrtcChromeDtls)
            var frontPort: Int? = null
            // Which sing-box and Xray processes the line cannot do without, as
            // whoever starts them says it; none for a room reached directly.
            var coresAlive: (() -> Boolean)? = null

            if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.ensureAdministratorOrRequestRestart()
            }

            // Branch on location kind: olcrtc uses the existing engine path
            // (unchanged); vless/hy2/xhttp start a sing-box/Xray core on the core
            // SOCKS port. The tun/PAC then targets whichever port is active.
            connectedLocation = location.normalized()
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
                    privileged = desktopMode == DesktopMode.LinuxTun,
                    directRulesFile = engineRules,
                    dtlsProfile = dtlsProfile
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
                    coresAlive = singBoxCore::isRunning
                }
            } else {
                coresAlive = startDesktopCore(
                    location, effectiveSocksPort, coreRouting, verboseLogs,
                    besideTun = desktopMode == DesktopMode.LinuxTun
                )
            }

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            // Read here, before the tun comes up, and kept for the session.
            // Once it is up the system's list is the tun's as well, and the
            // core of another location, started beside the tun later, needs
            // the resolvers the machine really has.
            val ownResolvers = if (desktopMode == DesktopMode.SystemProxy) null else DesktopDnsResolver.ownServers()
            var letsEveryLineOut = true

            when (desktopMode) {
                DesktopMode.LinuxTun -> startLinuxTun(
                    effectiveSocksPort,
                    requestGeneration,
                    // What hev has to send: the session's endpoint, as the tun's
                    // outbound sends it on the other systems.
                    login = LineSupervision.endpointOf(
                        isOlcrtc = isOlcrtc,
                        linePort = effectiveSocksPort,
                        frontPort = frontPort,
                        username = socksSettings.username,
                        password = socksSettings.password
                    ).login
                )
                DesktopMode.WindowsTun -> startWindowsTun(
                    effectiveSocksPort,
                    requestGeneration,
                    location,
                    isOlcrtc,
                    socksSettings
                )
                DesktopMode.MacTun -> {
                    val daemonRouting = if (rulesHome == DesktopRulesHome.Daemon) {
                        routingSettings.toRules(TunnelDaemonProtocol.RULES_DIR, DirectDns.System)
                    } else {
                        Routing.Global
                    }
                    letsEveryLineOut = startMacTun(
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
            } else {
                if (!singBoxCore.isRunning() && !xrayCore.isRunning()) {
                    error("core exited before desktop proxy was enabled")
                }
                // hev is watched behind a core line as it is behind a room.
                // Nothing did: its death would have left the status Connected
                // with no tunnel under it.
                if (desktopMode == DesktopMode.LinuxTun) {
                    startTunExitWatcher(tunProcess ?: error("TUN process is missing"))
                }
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
            // The session holds its tunnel from here, the first verified
            // connection: a core or an engine that dies after this is started
            // again behind it with what is written down now, and another
            // location is started behind it as a line that fits it.
            val line = SessionLine(
                session = HeldSession(
                    built = SessionBuild(
                        mode = desktopMode,
                        routing = routingSettings,
                        socks = socksSettings,
                        letsEveryLineOut = letsEveryLineOut
                    ),
                    endpoint = LineSupervision.endpointOf(
                        isOlcrtc = isOlcrtc,
                        linePort = effectiveSocksPort,
                        frontPort = frontPort,
                        username = socksSettings.username,
                        password = socksSettings.password
                    ),
                    tunListener = channelProxy.takeIf {
                        desktopMode == DesktopMode.WindowsTun || verifiedThroughTun
                    },
                    resolvers = ownResolvers,
                    startedInRoom = isOlcrtc
                ),
                location = location,
                isOlcrtc = isOlcrtc,
                socksSettings = socksSettings,
                corePort = effectiveSocksPort,
                coreRouting = coreRouting,
                verboseLogs = verboseLogs,
                engineRules = engineRules,
                dtlsProfile = dtlsProfile,
                desktopMode = desktopMode,
                frontPort = frontPort,
                verifiedThrough = channelProxy ?: error("the session has no listener to verify it through"),
                windowsTunInterface = if (desktopMode == DesktopMode.WindowsTun) windowsTunInterface else null,
                coresAlive = coresAlive
            )
            sessionLine = line
            setStatus(VpnStatus.Connected)
            addLog("$transport connected — exit ${exit.label()}")
            startCoreWatcher(line, requestGeneration)
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

    private suspend fun startLinuxTun(socksPort: Int, requestGeneration: Long, login: SocksLogin?) {
        val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
        tunProcess = linuxTunController.start(
            hevBinary,
            socksPort,
            killSwitch = _socksProxySettings.value.killSwitch,
            username = login?.username.orEmpty(),
            password = login?.password.orEmpty()
        )

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
        val bypassPaths = lineBinaryPaths()
        require(bypassPaths.isNotEmpty()) { "No VPN core to route outside the TUN" }

        // The carrier's addresses and name are this line's, and stay in the
        // tun after another location has taken its place. They only spare the
        // first line's packets a pass through the tun's own process; what lets
        // any line out, this one included when its name does not resolve, is
        // the rule on the binaries above.
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
                    // The first line's, for the whole session (lineBehindTun
                    // says what that costs a line that comes later).
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
                windowsTunInterface = interfaceName
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
        startTunExitWatcher(tunProcess!!)
        addLog("Windows TUN ready; carrier processes bypass via $physicalInterface")
    }

    /**
     * Every binary a line can run, as a tun's process rule names them: the two
     * cores and the olcRTC engine, whichever of them the line at hand runs.
     *
     * The rule is the session's and the line is not. Naming only the binaries
     * of the line a session started with let that line out and no other, which
     * is one of the reasons another location had to be another tun. A binary
     * this build does not carry is left out and is not an error: a connect
     * that needs none of it worked before it was asked about here.
     */
    private fun lineBinaryPaths(): List<String> {
        // ProcessHandle.Info.command() may be empty on Windows. Exact process
        // paths are useful when present; known resolved binaries are the safe
        // fallback for the process bypass rule.
        val running = listOfNotNull(process, singBoxCore.runningProcess(), xrayCore.runningProcess())
            .mapNotNull { it.info().command().orElse(null) }
        val bundled = buildList<Path> {
            runCatching { DesktopNativeAssets.resolveSingBoxBinary() }.getOrNull()?.let { add(it) }
            runCatching { DesktopNativeAssets.resolveXrayBinary() }.getOrNull()?.let { add(it) }
            addAll(runCatching { DesktopNativeAssets.resolveOlcRtcBinaryCandidates() }.getOrDefault(emptyList()))
        }
        return LineConfigs.processPaths(running, bundled)
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
     *
     * The exclusion is the first line's and stays in the tun for as long as it
     * lives. What lets a line for another location out, and an engine that has
     * to sign in again, is the rule on the binaries a line can run
     * ([lineBinaryPaths]), which the Windows tun has always had. True when the
     * tun was started with that rule ([MacOsTunController.start]).
     */
    private suspend fun startMacTun(
        corePort: Int,
        isOlcrtc: Boolean,
        socksSettings: DesktopSocksProxySettings,
        location: LocationConfig,
        routing: Routing,
        verboseLogs: Boolean,
        ruleFiles: Map<String, String>
    ): Boolean {
        val verifyPort = allocateVerifyPort(corePort)
        val letsEveryLineOut = macOsTunController.start(
            corePort = corePort,
            verifyPort = verifyPort,
            // Only olcRTC enforces them; the cores' own inbounds have no auth.
            username = if (isOlcrtc) socksSettings.username else "",
            password = if (isOlcrtc) socksSettings.password else "",
            serverHost = serverEndpoint(location)?.first,
            // olcRTC relays UDP over a lossy video carrier, so DNS takes the
            // reliable path. The native transports carry UDP themselves. It is
            // the first line's answer for the whole session (lineBehindTun
            // says what that costs a line that comes later).
            upstreamUdpIsLossy = isOlcrtc,
            routing = routing,
            verboseLogs = verboseLogs,
            ruleFiles = ruleFiles,
            bypassProcessPaths = lineBinaryPaths()
        )
        macTunVerifyPort = verifyPort
        macTunActive = true
        return letsEveryLineOut
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

    /**
     * Start a sing-box (reality/hy2) or Xray (xhttp) core on the core SOCKS port.
     *
     * Returns the question of whether what it started is still running. It is
     * handed back rather than worked out again by the session's watcher because
     * the answer depends on the shape chosen here: XHTTP under rules is two
     * processes, and asking whether either core runs would call that line alive
     * with half of it gone.
     *
     * [besideTun] is the Linux tunnel. Its rule sends every user's traffic into
     * the tun and lets only root keep the main table, and a core runs as the
     * user: its own connection to the server went into the tun with the rest,
     * came back to its own port, and no line a core carries could connect
     * there. So the core is bound to the physical interface, which a socket
     * needs no privilege for since Linux 5.7 and which the tun's route does
     * not match. sing-box finds the interface itself and follows it when it
     * changes; Xray is told its name once. A server that is a name also gets a
     * resolver of its own, because the system's answers with hev's fake
     * addresses for as long as the tun is up; that part exists for sing-box
     * only.
     *
     * [login] and [resolvers] are for a line that comes later in a session,
     * when another location is chosen ([lineBehindTun]). The login is the
     * endpoint's, demanded by whatever listens on [port]. The resolvers are
     * the machine's own, read before the tun came up: with the tun up, a
     * question to the system's resolver goes into the tun, and the tun's way
     * out is this core, which is asking because it is not up yet. sing-box
     * asks them itself, and its own query leaves by the tun's rule on its
     * binary. Xray has no such part, and an XHTTP server that is a name may
     * not come up as a later line. They are for Windows and macOS, where the
     * system's list is the tun's once it is up; under [besideTun] they are
     * read again every time.
     */
    private suspend fun startDesktopCore(
        location: LocationConfig,
        port: Int,
        routing: Routing,
        verboseLogs: Boolean,
        besideTun: Boolean = false,
        login: SocksLogin? = null,
        resolvers: List<String>? = null
    ): () -> Boolean {
        val raw = location.rawLink ?: error("core location has no link")
        val spec = org.olcbox.app.net.LinkParser.parse(raw) ?: error("unparseable core link")
        stopDesktopCores()
        val xhttp = LineConfigs.xhttpOf(spec)
        val boundInterface = if (besideTun && xhttp != null) DesktopDnsResolver.linuxDefaultInterface() else null
        val serverResolver = when {
            xhttp != null -> null
            // In the Linux tunnel the machine's resolvers are read now, for a
            // later line as for the first. The tun does not change what the
            // default interface says of them, so the answer is as good as it
            // was when the session started, and better once the machine has
            // moved to another network inside the session: the ones kept from
            // the start are then resolvers that are no longer there.
            besideTun -> DirectDns.Servers(DesktopDnsResolver.linuxDirectDnsServers())
            resolvers != null -> DirectDns.Servers(resolvers)
            else -> null
        }
        if (besideTun && xhttp != null) {
            if (boundInterface == null) {
                addLog("Linux TUN: no default interface found, so the XHTTP core cannot be led out of the tunnel")
            } else if (!org.olcbox.app.net.XrayConfig.isIpLiteral(xhttp.host)) {
                addLog(
                    "Linux TUN: this XHTTP server is named by hostname, and the system's resolver answers " +
                        "through the tunnel while it is up; it may not connect in this mode"
                )
            }
        } else if (resolvers != null && xhttp != null && !org.olcbox.app.net.XrayConfig.isIpLiteral(xhttp.host)) {
            addLog(
                "This XHTTP server is named by hostname, and Xray asks the system's resolver, whose questions " +
                    "go into the tunnel while it is up; it may not connect until you disconnect and connect"
            )
        }
        // Which processes must be alive once the port answers. A port that
        // answers proves nothing about who answers.
        val shape = LineConfigs.shapeOf(spec, routing)
        val alive = shape.alive()
        // Xray does not route; sing-box does, so under rules it goes in front
        // and Xray takes a port of its own behind it.
        val xrayPort = if (shape == CoreShape.XrayBehindSingBox) allocateVerifyPort(port) else port
        val configs = LineConfigs.core(
            spec,
            port = port,
            xrayPort = xrayPort,
            routing = routing,
            verboseLogs = verboseLogs,
            login = login,
            serverResolver = serverResolver,
            autoDetectInterface = besideTun,
            bindInterface = boundInterface
        )
        configs.xray?.let { xrayCore.start(it) }
        configs.singBox?.let { singBoxCore.start(it) }
        addLog(
            when (shape) {
                CoreShape.XrayBehindSingBox ->
                    "Xray/xhttp core on 127.0.0.1:$xrayPort behind a sing-box front on 127.0.0.1:$port"
                CoreShape.Xray -> "Xray/xhttp core starting on 127.0.0.1:$port"
                CoreShape.SingBox -> "sing-box core (${location.kind}) starting on 127.0.0.1:$port"
            }
        )
        if (!waitForCoreSocks(port) || !alive()) {
            val exit = if (xhttp != null) xrayCore.exitCodeOrNull() else singBoxCore.exitCodeOrNull()
            error(
                "core SOCKS not ready on 127.0.0.1:$port" +
                    (exit?.let { " (core exited with code $it — see the lines above)" } ?: "")
            )
        }
        addLog("core ready on 127.0.0.1:$port")
        return alive
    }

    /**
     * sing-box between the proxy and olcRTC, so the routing rules see every
     * connection before the relay does. The engine keeps its own port and its
     * credentials; the front listens without any, like the cores do.
     *
     * [heldPort] is the port of a front that died in a running session. The
     * system proxy was pointed at it when the session started and still is, so
     * the front comes back there or not at all: on any other port it would be
     * running and unreachable. It is also the session's endpoint, for the
     * front before a room that was changed to, and [login] is what the
     * endpoint demands there.
     */
    private suspend fun startOlcRtcFront(
        socksSettings: DesktopSocksProxySettings,
        routing: Routing.Rules,
        verboseLogs: Boolean,
        heldPort: Int? = null,
        login: SocksLogin? = null
    ): Int {
        stopDesktopCores()
        val port = heldPort ?: allocateCorePort()
        singBoxCore.start(
            LineConfigs.front(
                enginePort = socksSettings.port,
                engineUsername = socksSettings.username,
                enginePassword = socksSettings.password,
                port = port,
                routing = routing,
                verboseLogs = verboseLogs,
                login = login
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

    /** Whether every process of a line of this shape runs. */
    private fun CoreShape.alive(): () -> Boolean {
        val singBox = this != CoreShape.Xray
        val xray = this != CoreShape.SingBox
        return { (!singBox || singBoxCore.isRunning()) && (!xray || xrayCore.isRunning()) }
    }

    /**
     * Gives a line that was just stopped a moment to stop listening on [port].
     * A process that was told to stop can hold its listener a little longer
     * ([allocateCorePort] waits the same out), and the line that follows has
     * no other port to take: started into a port still listened on, it would
     * be a failed attempt and a wait of two seconds where a tenth was enough.
     *
     * Asked by connecting, not by binding as [isLocalPortFree] asks. What the
     * old line had accepted lingers on its port after the line is gone, for
     * as long as a minute: the line closed those connections first, which a
     * full restart never did, since there the tun at their other end went
     * first. A bind that does not reuse the address can be refused for all of
     * that time. A listener that does reuse it, as the cores and the engine
     * do, is not, so the question is only whether anybody still listens.
     */
    private suspend fun awaitPortReleased(port: Int, requestGeneration: Long) {
        val deadline = System.currentTimeMillis() + CORE_PORT_RELEASE_TIMEOUT_MS
        while (
            requestGeneration == generation &&
            canConnectToSocks(port) &&
            System.currentTimeMillis() < deadline
        ) {
            delay(CORE_PORT_RELEASE_POLL_MS)
        }
    }

    /**
     * A line is not started on a port where something still listens.
     *
     * The waits that follow a start take a port that answers for the new
     * process's own. With the line before it still there, they would call a
     * line up that is not, and the session's check would then pass through
     * the old one. A process that was told to stop is not always gone when
     * the call returns: [stopProcess] gives it four seconds and goes on, and
     * on Linux the engine runs as root, which this process does not. Until
     * the old listener is gone the attempt fails here, with a message, and is
     * made again after its wait.
     */
    private fun requireNoListener(port: Int) {
        if (canConnectToSocks(port)) error("port $port still answers to another process")
    }

    /** Closes the LAN listener and forgets what was said about it. Whoever wants it back starts it. */
    private fun stopLanSharing() {
        lanWatchJob?.cancel()
        lanWatchJob = null
        lanControlJob?.cancel()
        lanControlJob = null
        lanProxy.stop()
        _lanProxyEndpoint.value = null
        _lanProxyHealth.value = null
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
        privileged: Boolean,
        directRulesFile: Path?,
        dtlsProfile: String,
        dnsServer: String = DesktopDnsResolver.current()
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        var lastException: Exception? = null

        addLog("olcRTC resolvers: $dnsServer")

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
                    dnsServer = dnsServer,
                    directRulesFile = directRulesFile,
                    dtlsProfile = dtlsProfile
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
        // Whether what is taken down is a session that was verified, asked
        // before the line is forgotten: the Linux kill switch holds for those.
        val hadSession = sessionLine != null
        // First, and on every path out of here: a session that is being stopped
        // has no line to bring back, and a watcher that sees one of its processes
        // go from now on must find nothing to restart.
        sessionLine = null
        // The LAN listener is externally reachable. Close it before changing the
        // tunnel it chains to, including partially started and already-disconnected paths.
        stopLanSharing()
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
                // The kill switch: a tunnel that died by itself is not
                // followed by the cleanup, whoever comes to take the session
                // down. Decided here and not by the tun's watcher alone: a
                // request that moved the generation first (another location,
                // a restart for new settings) makes the watcher stand aside,
                // finds the tun's process dead, and would otherwise remove the
                // block on its way to a new tunnel, by this app's own hand
                // and behind a password dialog that looks like any other.
                // Kept, the restart's tunnel goes back in front of the block
                // without opening it. Not at a Disconnect, which is the user
                // ending the session, block included.
                if (!finalStatus && hadSession && tunProcess?.isAlive == false) {
                    linuxTunController.holdAfterTunDeath(wanted = _socksProxySettings.value.killSwitch)
                }
                runCatching {
                    // The room's engine runs as root here, as hev does, and the
                    // stop further down does not reach it. What the tun's
                    // cleanup runs as root ends it ([olcRtcConfigNaming]).
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
                    windowsTunInterface = null
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
        deleteOlcRtcDirectRules()

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
        watchedEngine = null

        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = null

        coreWatchJob?.cancel()
        coreWatchJob = null

        lineRestartJob?.cancel()
        lineRestartJob = null

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
        dnsServer: String,
        directRulesFile: Path?,
        dtlsProfile: String
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
            dnsServer = dnsServer,
            directRulesFile = directRulesFile,
            dtlsProfile = dtlsProfile
        )
        if (dtlsProfile != OlcrtcDtls.OFF) {
            addLog("olcRTC handshake: $dtlsProfile")
        }
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

    /** Where an engine's config is written: the directory and how its file's name begins. */
    private fun olcRtcConfigDir(): Path = DesktopPaths.appDataDir().resolve("runtime")

    /**
     * What every engine this app starts carries on its command line and
     * nothing else does: its config's directory and the beginning of the
     * file's name ([writeOlcRtcClientConfig]).
     */
    private fun olcRtcConfigNaming(): String = olcRtcConfigDir().resolve(OLCRTC_CONFIG_PREFIX).toString()

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = olcRtcConfigDir()
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, OLCRTC_CONFIG_PREFIX, ".yaml")
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

    /**
     * The engine's direct rules for [settings] ([OlcrtcDirectRules.forRouting], the
     * text Android hands `setDirectRules`), in a file next to the yaml; null when
     * they come to nothing. The engine reads it once, at start, and it goes with the
     * yaml when the session ends.
     */
    private suspend fun writeOlcRtcDirectRules(settings: RoutingSettings): Path? {
        deleteOlcRtcDirectRules()
        // The engine reads no rule-set files; the directory is sing-box's alone.
        val text = OlcrtcDirectRules.forRouting(settings.toRules(ruleSetDir = "", directDns = DirectDns.System))
        if (text.isEmpty()) return null
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, "olcrtc-direct-", ".txt")
        Files.writeString(path, text, StandardCharsets.UTF_8)
        olcRtcDirectRulesPath = path
        return path
    }

    private fun deleteOlcRtcDirectRules() {
        olcRtcDirectRulesPath?.let { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        olcRtcDirectRulesPath = null
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
                currentTunProcess ?: error("TUN process is missing")
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
        watchedEngine = target
        processWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || process !== target) return@withLock

                    handleUnexpectedProcessExit(
                        which = DeadProcess.Engine,
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
                            which = DeadProcess.Tun,
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

    /**
     * Waits on the tun's own process for as long as it lives, which is as long
     * as the session does: a change of location leaves the tun, and this
     * waiter, where they are. So what it compares when the process is gone is
     * [lineGeneration], the generation of the request whose line the tun now
     * carries, and not the one that was current when it was armed.
     */
    private fun startTunExitWatcher(target: Process) {
        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    val requestGeneration = lineGeneration
                    if (requestGeneration != generation || tunProcess !== target) return@withLock

                    handleUnexpectedProcessExit(
                        which = DeadProcess.Tun,
                        logMessage = "TUN process exited unexpectedly with code $exitCode",
                        errorMessage = "TUN process exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    /**
     * Notices when a sing-box or Xray core of the session's line dies.
     *
     * Until this existed nothing did. A core was asked whether it runs once, at
     * connect, and its death after that left the tun up, the status Connected
     * and every connection refused at a port nobody listened on: closed, which
     * is right, and silent, which is not.
     *
     * Asked, not waited on as the engine and the tun are: the controllers own
     * their processes and replace them at every start, a line may run two, and
     * one question covers whichever it runs. And asked once, unlike the
     * daemon's watcher: this is the operating system's answer about our own
     * child, not a reply over a socket that may have blinked.
     */
    private fun startCoreWatcher(line: SessionLine, requestGeneration: Long) {
        coreWatchJob?.cancel()
        coreWatchJob = null
        val coresAlive = line.coresAlive ?: return
        coreWatchJob = scope.launch {
            while (isActive) {
                delay(CORE_WATCH_INTERVAL_MS)
                if (requestGeneration != generation || sessionLine !== line) return@launch
                if (coresAlive()) continue

                // In a coroutine of its own, as the other watchers do it: the
                // restart this begins cancels this job.
                scope.launch {
                    mutex.withLock {
                        if (requestGeneration != generation || sessionLine !== line) return@withLock
                        val exitCode = singBoxCore.exitCodeOrNull() ?: xrayCore.exitCodeOrNull()
                        handleUnexpectedProcessExit(
                            which = DeadProcess.Core,
                            logMessage = "core process exited unexpectedly" +
                                (exitCode?.let { " with code $it" } ?: ""),
                            errorMessage = "the core exited unexpectedly",
                            requestGeneration = requestGeneration
                        )
                    }
                }
                return@launch
            }
        }
    }

    /**
     * One of a session's processes is gone, and which one decides what becomes
     * of the tunnel ([LineSupervision.restartsBehindTun]).
     *
     * A core or the engine of a connected session is started again behind the
     * tunnel, which is not touched. Taking it down here is what used to send
     * the machine's traffic out directly, by the app's own hand. The tun's own
     * death ends the session as it always has: its routes went with it. What
     * is left to hold then is the Linux kill switch's block, where it is on.
     */
    private suspend fun handleUnexpectedProcessExit(
        which: DeadProcess,
        logMessage: String,
        errorMessage: String,
        requestGeneration: Long
    ) {
        addLog(logMessage)
        val line = sessionLine
        if (line != null && LineSupervision.restartsBehindTun(which)) {
            restartLineBehindTun(line, requestGeneration)
            return
        }
        // The Linux kill switch: the block under a verified session's tunnel
        // outlives it. The teardown finds the tun's process dead and keeps
        // the block ([stopDesktopMode]): it stops the line's processes and
        // leaves the block where it is (no cleanup, so no password dialog
        // opening by itself), and setStatus says that traffic is held and how
        // to let it out.
        stopDesktopMode(finalStatus = false)

        if (requestGeneration == generation) {
            setStatus(VpnStatus.Error(errorMessage))
        }
    }

    /**
     * Begins the restart of a dead core or engine behind what the session
     * holds: the tun, or in proxy mode the system's proxy setting. Neither is
     * touched, here or by any attempt, so until the line is back whatever
     * enters the tun has nowhere to go and nothing leaves around it.
     *
     * The attempts run in a job of their own, which waits between them without
     * the mutex. A Disconnect, or another location, moves the generation and
     * then takes the mutex; a backoff slept through under it would make the
     * user's Disconnect wait out up to thirty seconds of a restart nobody wants
     * any more.
     *
     * It goes on for as long as the session does. There is no attempt after
     * which giving up is better: the alternative to waiting behind a closed
     * tunnel is an error over an open one.
     */
    private fun restartLineBehindTun(line: SessionLine, requestGeneration: Long) {
        // One restart per outage. A second death reported while it runs (the
        // front after the engine, the other core of an XHTTP line) needs none
        // of its own: every attempt starts whatever of the line is not running.
        if (lineRestartJob != null) return

        coreWatchJob?.cancel()
        coreWatchJob = null
        setStatus(VpnStatus.Reconnecting)
        addLog(
            "The ${line.heldName()} stays as it is while the line is restarted behind it; " +
                "nothing passes until it is back"
        )
        if (line.isOlcrtc && line.desktopMode == DesktopMode.LinuxTun) {
            // pkexec asks at every launch, and no privileged process of ours
            // lives as long as the session. Said once per outage, where the
            // user looks when a password dialog opens by itself.
            addLog(
                "Linux TUN: olcRTC runs as root to bypass the TUN route, so restarting it needs the " +
                    "administrator again; until the password is given the tunnel stays and nothing passes"
            )
        }
        lineRestartJob = launchLineAttempts(line, requestGeneration)
    }

    /**
     * Another location chosen inside a session: the line is replaced behind
     * what the session holds, the tun or in proxy mode the system's proxy
     * setting, which is not stopped, not reconfigured and not started again.
     * True when that is what was done, or begun; false when the change is the
     * full restart it has always been ([LineSupervision.changeOfLine] says
     * when), which the caller then makes.
     *
     * It used to be a teardown and a start whatever was chosen. Between the
     * two the tun was down and the machine's traffic left directly, with its
     * own address, for as long as the new connect took; on Linux the password
     * for the tun was asked again as well.
     *
     * What makes this possible is that nothing the session holds depends on
     * the line. It points at the session's endpoint, one local port and at
     * most one login, and the new line is started to be exactly that
     * ([lineBehindTun]). So nothing that is held has to learn of the change.
     *
     * The old line is stopped and the new one started in one stretch under the
     * mutex, stop and then start at once. In between, the endpoint's port is
     * free, and another local process could take it: what enters the tun
     * would then be handed to that process, while the new line found the port
     * taken and went on trying. Nothing is built against that. The stretch is
     * as long as a process takes to exit.
     *
     * A line that does not come up is not an error, and does not open the
     * tunnel. The user chose this location, so from here on it is the
     * session's line, and it is tried again behind the tun exactly as a line
     * that died is: the first attempt now, the rest after their waits.
     */
    private suspend fun changeLineBehindTun(requestGeneration: Long): Boolean {
        val current = sessionLine ?: return false
        val session = current.session
        // Anything thrown before the new line is the session's is a full
        // restart, as every change was. The generation has moved already, so
        // a request that simply died here would leave the session with
        // watchers that all take it for superseded, and nobody after it.
        val line = try {
            val location = locationsRepository.getActiveLocation()?.location?.normalized()
            // No location to change to. The full restart ends in the error it
            // always has.
            if (location == null || !location.isComplete()) return false
            val routingSettings = locationsRepository.getRoutingSettings()
            val change = LineSupervision.changeOfLine(
                session = session.built,
                mode = DesktopMode.current(),
                routing = routingSettings,
                socks = _socksProxySettings.value.normalized(),
                tunRunning = tunRunning(current.desktopMode)
            )
            // Whoever moved the generation while that was read is waiting for
            // this mutex, and decides for itself.
            if (requestGeneration != generation) return false
            if (change != LineChange.BehindTun) {
                change.whyFullRestart()?.let(::addLog)
                return false
            }
            val next = lineBehindTun(current, location, routingSettings)
            if (requestGeneration != generation) return false

            lineGeneration = requestGeneration
            setStatus(VpnStatus.Reconnecting)
            addLog(
                "Changing to the selected location behind the ${next.heldName()}, which stays as it is; " +
                    "nothing passes until the new line is up"
            )
            if (next.isOlcrtc && next.desktopMode == DesktopMode.LinuxTun) {
                // As for a restart: pkexec asks at every launch.
                addLog(
                    "Linux TUN: olcRTC runs as root to bypass the TUN route, so starting it for this room needs " +
                        "the administrator again; until the password is given the tunnel stays and nothing passes"
                )
            }
            val tunAnswersNames =
                next.desktopMode == DesktopMode.WindowsTun || next.desktopMode == DesktopMode.MacTun
            if (next.isOlcrtc && !session.startedInRoom && tunAnswersNames) {
                // What lineBehindTun says about `upstreamUdpIsLossy`, where
                // the user will look when pages are slow to start loading.
                addLog(
                    "This tunnel was started for a server that carries UDP, so names are looked up through the " +
                        "room as datagrams, which a room can lose: a lookup may take a retry until you " +
                        "disconnect and connect"
                )
            }

            // Nothing of the old line is left to watch, or to bring back.
            lineRestartJob?.cancel()
            lineRestartJob = null
            coreWatchJob?.cancel()
            coreWatchJob = null
            processWatchJob?.cancel()
            processWatchJob = null
            watchedEngine = null
            // The LAN listener hands what it takes to the session's listener,
            // and is closed for as long as nothing answers there. It comes
            // back with the line, under this request's generation and with the
            // new exit.
            stopLanSharing()
            // The daemon's watcher compares the generation it was armed with,
            // and this request has moved it. Unlike the waiter on a process it
            // can be armed again, so it is.
            if (next.desktopMode == DesktopMode.MacTun) startMacTunWatcher(requestGeneration)

            sessionLine = next
            connectedLocation = next.location
            channelProxy = next.verifiedThrough
            next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog(
                "The selected location could not be changed to behind the ${current.heldName()} " +
                    "(${e.message}), so the session is restarted in full"
            )
            return false
        }

        // From here the line is the session's, and whatever goes wrong with it
        // is a line that is not up: nothing thrown below may leave the session
        // Reconnecting with nobody trying.
        val started = try {
            // Forgotten before it is stopped: an engine still named here is
            // taken for the new line's by every attempt that follows.
            val oldEngine = process
            process = null
            stopDesktopCores()
            stopProcess(oldEngine)
            // A room's yaml names its key, and does not outlive its engine.
            if (!line.isOlcrtc) deleteOlcRtcConfig()
            if (line.engineRules == null) deleteOlcRtcDirectRules()
            for (port in listOfNotNull(line.corePort, line.frontPort).distinct()) {
                awaitPortReleased(port, requestGeneration)
            }
            // Superseded while the port was waited for. Whoever did it finds
            // the session with this line and nothing of it running, and stops
            // it or changes it again.
            if (requestGeneration != generation) return true
            startDeadProcesses(line, requestGeneration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("The line did not come up: ${e.message}")
            false
        }
        if (requestGeneration != generation) return true
        lineRestartJob = launchLineAttempts(line, requestGeneration, startedNow = started)
        return true
    }

    /**
     * Whether what holds the session's tun is running. On macOS the daemon is
     * asked, and asked again a moment later before a no is believed, as its
     * watcher asks twice: a socket that blinked, or a daemon that was busy,
     * must not turn a change of location into the teardown it exists to avoid.
     */
    private suspend fun tunRunning(mode: DesktopMode): Boolean = when (mode) {
        DesktopMode.LinuxTun,
        DesktopMode.WindowsTun -> tunProcess?.isAlive == true
        DesktopMode.MacTun -> macTunActive && macTunAnswers()
        // Nothing of the proxy's stops by itself: the PAC is served from this
        // process, and the setting is the system's.
        DesktopMode.SystemProxy -> true
    }

    private suspend fun macTunAnswers(): Boolean {
        if (macOsTunController.isRunning()) return true
        delay(MAC_TUN_RECHECK_MS)
        return macOsTunController.isRunning()
    }

    /** Why another location is a full restart, as the log says it; null where nothing needs saying. */
    private fun LineChange.whyFullRestart(): String? = when (this) {
        LineChange.BehindTun,
        LineChange.NoSession -> null
        LineChange.ModeChanged ->
            "The connection mode is not the one this session started in, so it is restarted in full"
        LineChange.RoutingChanged ->
            "The routing settings are not the ones this session started with, so it is restarted in full"
        LineChange.SocksChanged ->
            "The SOCKS settings are not the ones this session started with, so it is restarted in full"
        LineChange.TunNotRunning ->
            "The tunnel's own process is not running, so there is nothing to change the location behind"
        LineChange.TunBuiltForOneLine ->
            "This tunnel lets only the server it started with out of itself, so another location is a full restart"
    }

    /**
     * The line for [location] as it has to be started inside the session of
     * [current]: as the session's endpoint, whatever its own port and login
     * would have been.
     *
     * A core listens on the endpoint's port and demands its login. An engine
     * reached directly takes both from the settings it is started with, so it
     * is handed a copy that names the endpoint's. Where a front stands before
     * the engine, in proxy mode under rules, the front is the endpoint, and
     * the engine keeps its own port and login behind it.
     *
     * The rules live where a connect to this location would put them
     * ([desktopRulesHome]), from the routing settings the session started
     * with: had they changed, this would be a full restart. The rule-set files
     * of the proxy's core were written when the session started and are not
     * written again under a core that has them open.
     *
     * How the tun answers names is the first line's for the whole session,
     * since `upstreamUdpIsLossy` is written into its config. A session that
     * started in a room and moves to a core keeps the room's way, names
     * answered by the tun and asked over TCP, at the price of a slower first
     * lookup. One that started on a core and moves into a room keeps the
     * core's: lookups cross the room as the datagrams they are, on a carrier
     * that loses datagrams, so a name can take a retry or several until the
     * next connect builds the tun for a room. Linux has no such setting; hev
     * answers names itself for every line.
     */
    private suspend fun lineBehindTun(
        current: SessionLine,
        location: LocationConfig,
        routingSettings: RoutingSettings
    ): SessionLine {
        val session = current.session
        val endpoint = session.endpoint
        val mode = current.desktopMode
        val socks = session.built.socks
        val isOlcrtc = location.kind == LocationKind.Olcrtc
        val rulesHome = desktopRulesHome(mode, isOlcrtc, routingSettings.needsRules)
        val coreRouting: Routing = if (rulesHome == DesktopRulesHome.Core) {
            routingSettings.toRules(DesktopPaths.appDataDir().resolve("rulesets").toString(), DirectDns.System)
        } else {
            Routing.Global
        }
        val fronted = isOlcrtc && coreRouting is Routing.Rules
        val engineSettings = when {
            !fronted -> LineConfigs.engineSettings(socks, endpoint)
            // Behind a front the engine keeps the port in the settings, unless
            // that is the very port the front has to take.
            socks.port == endpoint.port -> socks.copy(port = allocateVerifyPort(endpoint.port))
            else -> socks
        }
        val coresAlive: (() -> Boolean)? = when {
            fronted -> singBoxCore::isRunning
            isOlcrtc -> null
            else -> {
                val spec = location.rawLink?.let(LinkParser::parse) ?: error("unparseable core link")
                LineConfigs.shapeOf(spec, coreRouting).alive()
            }
        }
        return SessionLine(
            session = session,
            location = location,
            isOlcrtc = isOlcrtc,
            socksSettings = engineSettings,
            corePort = if (fronted) engineSettings.port else endpoint.port,
            coreRouting = coreRouting,
            verboseLogs = routingSettings.verboseDebugLogs,
            engineRules = if (rulesHome == DesktopRulesHome.Engine) writeOlcRtcDirectRules(routingSettings) else null,
            dtlsProfile = OlcrtcDtls.profile(routingSettings.olcrtcChromeDtls),
            desktopMode = mode,
            frontPort = if (fronted) endpoint.port else null,
            // The listener the session was verified through: the tun's own
            // where it has one, and otherwise the endpoint, with the login it
            // demands now.
            verifiedThrough = session.tunListener ?: SubscriptionFetchProxy(
                socks.host,
                endpoint.port,
                endpoint.login?.username.orEmpty(),
                endpoint.login?.password.orEmpty()
            ),
            windowsTunInterface = current.windowsTunInterface,
            coresAlive = coresAlive,
            login = endpoint.login,
            resolvers = session.resolvers
        )
    }

    /**
     * The attempts at a line that is not up, each after its wait
     * ([LineSupervision.backoffMs]), until one of them leaves the session
     * Connected or the session ends.
     *
     * [startedNow] is a change of location. Its line was started already, at
     * once and under the mutex the change was decided under, and what is left
     * of that first attempt is the check.
     */
    private fun launchLineAttempts(
        line: SessionLine,
        requestGeneration: Long,
        startedNow: Boolean = false
    ): Job = scope.launch {
        if (startedNow && lineAttempt { confirmLine(line, requestGeneration) }) return@launch
        var attempt = 0
        while (isActive) {
            val waitMs = LineSupervision.backoffMs(attempt)
            addLog("Restarting the line in ${waitMs / 1_000}s")
            delay(waitMs)
            if (lineAttempt { restoreLine(line, requestGeneration) }) return@launch
            attempt++
        }
    }

    /**
     * Nothing an attempt throws ends the restart; only the end of the session
     * does. A loop that died here would leave the status Reconnecting with
     * nobody trying, and every later death dropped as one already in hand.
     */
    private suspend fun lineAttempt(attempt: suspend () -> Boolean): Boolean = try {
        attempt()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (e: Exception) {
        addLog("The line did not come up: ${e.message}")
        false
    }

    /**
     * One attempt: start whatever of the line is not running, on the port it
     * had, verify the session as the connect did, and only then say Connected.
     * True when the restart has nothing more to do: the session is Connected
     * again, or it ended while this ran.
     *
     * The mutex is taken to start processes and again to say Connected, and let
     * go in between. The check waits on the far end, sixteen seconds when
     * nothing answers, and through an outage it is made again after every
     * backoff: held through that, a Disconnect pressed while a server is down
     * would wait behind it about one time in three. Let go, the stop goes
     * through at once and cancels this job where it waits.
     *
     * Whatever the mutex guarded is asked again once it is taken back: that the
     * session is still this one, and that nothing of the line died while the
     * check was out. A death in that window is reported to nobody, since a
     * restart is running, so this is the one place it can be found.
     */
    private suspend fun restoreLine(line: SessionLine, requestGeneration: Long): Boolean {
        val started = mutex.withLock {
            // The session ended while this waited: a Disconnect or another
            // location moved the generation, and the tun's own death cleared
            // the line without moving it.
            if (requestGeneration != generation || sessionLine !== line) return true
            startDeadProcesses(line, requestGeneration)
        }
        if (requestGeneration != generation) return true
        if (!started) return false
        return confirmLine(line, requestGeneration)
    }

    /**
     * The second half of an attempt, for a line all of whose processes were
     * started: the check, with the mutex let go, and then Connected, with the
     * mutex taken back. True when there is nothing more to do.
     */
    private suspend fun confirmLine(line: SessionLine, requestGeneration: Long): Boolean {
        val exit = try {
            verifyLine(line, requestGeneration)
        } catch (_: CancellationException) {
            // The Windows check says "superseded" by throwing this. This
            // coroutine's own cancellation has to go on up.
            currentCoroutineContext().ensureActive()
            return requestGeneration != generation
        }
        if (requestGeneration != generation) return true
        if (exit == null) {
            addLog("The line is running but no traffic reached the internet through it")
            return false
        }

        return mutex.withLock {
            if (requestGeneration != generation || sessionLine !== line) return true
            if (!line.allRunning()) {
                addLog("The line stopped again while it was being checked")
                return@withLock false
            }
            _exitInfo.value = exit
            val engine = process
            if (line.isOlcrtc && engine != null && watchedEngine !== engine) {
                startOlcRtcExitWatcher(engine, requestGeneration)
            }
            setStatus(VpnStatus.Connected)
            addLog("The line is up behind the ${line.heldName()} — exit ${exit.label()}")
            startCoreWatcher(line, requestGeneration)
            restoreLanSharing(line.verifiedThrough, requestGeneration)
            // Last, with nothing after it that can fail: from here a death is a
            // new outage, and has to find no restart running.
            lineRestartJob = null
            true
        }
    }

    /**
     * Starts whatever of the line is not running, under the mutex. True when
     * all of it runs.
     *
     * What is running is left alone. An engine that came back and then failed
     * the check is not killed for it: what is missing may be the far end, a new
     * join would not bring it back, and on Linux it would be another password.
     * So a line with nothing dead is only checked again.
     *
     * For a line that was changed to, nothing runs the first time, and all of
     * it is started as the session's endpoint: the engine with the settings the
     * line names, the core or the front with the line's login.
     *
     * Whoever moves the generation meanwhile is waiting for this mutex to stop
     * the session, and finds everything started here where a stop looks: the
     * engine in [process] from its first moment, the cores in their controllers.
     */
    private suspend fun startDeadProcesses(line: SessionLine, requestGeneration: Long): Boolean {
        try {
            if (line.isOlcrtc && process?.isAlive != true) {
                requireNoListener(line.socksSettings.port)
                startLineEngine(line, requestGeneration)
                if (requestGeneration != generation) return false
            }
            if (line.coresAlive?.invoke() == false) {
                try {
                    // Whatever of the cores still runs goes first, as each
                    // start below would stop it anyway: it holds the port the
                    // question is about.
                    stopDesktopCores()
                    requireNoListener(line.frontPort ?: line.corePort)
                    val frontRouting = line.coreRouting
                    if (!line.isOlcrtc) {
                        startDesktopCore(
                            line.location, line.corePort, line.coreRouting, line.verboseLogs,
                            besideTun = line.desktopMode == DesktopMode.LinuxTun,
                            login = line.login,
                            resolvers = line.resolvers
                        )
                    } else if (line.frontPort != null && frontRouting is Routing.Rules) {
                        startOlcRtcFront(
                            line.socksSettings, frontRouting, line.verboseLogs, line.frontPort, line.login
                        )
                    }
                } catch (e: Exception) {
                    // Half a start is not left for the next attempt: a core
                    // that runs and never opened its port would be taken for a
                    // line with nothing dead, and checked without end.
                    stopDesktopCores()
                    throw e
                }
            }
            return true
        } catch (_: CancellationException) {
            // The steps shared with the connect say "superseded" by throwing
            // this. This coroutine's own cancellation has to go on up.
            currentCoroutineContext().ensureActive()
            return false
        } catch (e: Exception) {
            addLog("The line did not come up: ${e.message}")
            return false
        }
    }

    /** Whether everything of the line runs: the engine of a room, and the cores it was started with. */
    private fun SessionLine.allRunning(): Boolean =
        (!isOlcrtc || process?.isAlive == true) && coresAlive?.invoke() != false

    /**
     * Starts LAN sharing again after a restart, when it is still wanted and its
     * listener is gone. The listener's own health check stops it within seconds
     * of an outage and nothing else would start it, so a session that came back
     * would come back without it.
     *
     * In a job of its own, as a change of the LAN settings starts it. The
     * session is back whether or not the listener is, and a listener slow to
     * open must hold up neither the mutex nor the status.
     */
    private fun restoreLanSharing(upstream: SubscriptionFetchProxy, requestGeneration: Long) {
        if (!_socksProxySettings.value.shareOnLan || lanProxy.isRunning()) return
        lanControlJob?.cancel()
        lanControlJob = scope.launch {
            // Read here, not before the launch. A switch-off that lands in
            // between starts a job of its own, which the cancel above may have
            // stopped: the listener must not then be opened from what the
            // setting was a moment earlier.
            val settings = _socksProxySettings.value
            if (settings.shareOnLan && !lanProxy.isRunning()) {
                startLanSharing(settings, upstream, requestGeneration)
            }
        }
    }

    /**
     * Starts the engine again as it was: the same room, SOCKS port and login,
     * the same rules file and handshake. The port is the one thing that cannot
     * be otherwise, since the tun's upstream was written with it.
     *
     * On Linux that is root again, as at connect, and so the password dialog;
     * the wait for the room below includes the time the dialog stays open.
     *
     * The engine of a room that was changed to is started here too, for the
     * first time: with the settings its line names, which are the endpoint's,
     * and with the resolvers the machine had before the tun came up, since the
     * system's list, read now, may begin with the tun's own.
     */
    private suspend fun startLineEngine(line: SessionLine, requestGeneration: Long) {
        val ready = CompletableDeferred<Unit>()
        val startupFailure = CompletableDeferred<String>()
        val started = startOlcRtcProcessWithFallback(
            location = line.location,
            socksSettings = line.socksSettings,
            ready = ready,
            startupFailure = startupFailure,
            logOutput = true,
            privileged = line.desktopMode == DesktopMode.LinuxTun,
            directRulesFile = line.engineRules,
            dtlsProfile = line.dtlsProfile,
            dnsServer = line.resolvers?.let(DesktopDnsResolver::engineServers) ?: DesktopDnsResolver.current()
        )
        process = started
        try {
            waitForOlcRtcReady(
                process = started,
                ready = ready,
                startupFailure = startupFailure,
                socksPort = line.socksSettings.port,
                requestGeneration = requestGeneration
            )
        } catch (e: CancellationException) {
            // Superseded: whoever did it stops the session, this process included.
            throw e
        } catch (e: Exception) {
            // An engine that did not come up is not left holding the port, or
            // half joined to the room, against the attempt that follows.
            stopProcess(started)
            throw e
        }
    }

    /**
     * The check the connect made, through the listener it made it through.
     *
     * On Windows that is the tun's own check, not the plain request. The exit
     * the connect remembered would pass a dead line, and a request that comes
     * back says nothing unless the default routes are still the adapter's.
     */
    private suspend fun verifyLine(line: SessionLine, requestGeneration: Long): org.olcbox.app.net.TunnelExit? {
        val through = line.verifiedThrough
        val tunInterface = line.windowsTunInterface
        return if (tunInterface != null) {
            awaitWindowsTunTraffic(
                requestGeneration, through.port, through.username, through.password,
                tunInterface, System.currentTimeMillis() + LINE_VERIFY_WINDOW_MS
            )
        } else {
            org.olcbox.app.net.TunnelVerifier.verify(
                socksHost = through.host,
                socksPort = through.port,
                username = through.username,
                password = through.password
            )
        }
    }

    /** What a restart leaves in place, as the log names it. */
    private fun SessionLine.heldName(): String =
        if (desktopMode == DesktopMode.SystemProxy) "system proxy" else "tunnel"

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

    private fun setStatus(requested: VpnStatus) {
        // The Linux kill switch. A verified session ends a hold: its block is
        // back under a tunnel. And while the block holds traffic with no tunnel
        // up, "disconnected" and every error are shown as that
        // (LinuxKillSwitch.shown).
        if (requested is VpnStatus.Connected) linuxTunController.endHold()
        val status = LinuxKillSwitch.shown(requested, linuxTunController.holdsTraffic)
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
        /**
         * Between the two asks a change of location makes of the daemon. It is
         * waited under the mutex, and only after a first no, so it is short.
         */
        const val MAC_TUN_RECHECK_MS = 1_000L
        /** The longest a dead core goes unnoticed. */
        const val CORE_WATCH_INTERVAL_MS = 2_000L
        /**
         * What the Windows tun is given to carry the check of a restarted line:
         * as long as the one request the other systems make may take, its two
         * addresses at TunnelVerifier's own timeout.
         */
        const val LINE_VERIFY_WINDOW_MS = 16_000L
        const val LAN_HEALTH_INTERVAL_MS = 15_000L
        const val LAN_HEALTH_TIMEOUT_MS = 5_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        /** How the file of an engine's config begins its name; see [olcRtcConfigNaming]. */
        const val OLCRTC_CONFIG_PREFIX = "olcrtc-client-"
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

/** Where a session's routing rules are applied, if anywhere. */
internal enum class DesktopRulesHome {
    /** Everything through the tunnel: no rules asked for, or no way out for a direct socket. */
    Nowhere,

    /** The proxy's core, or for olcRTC the sing-box front before the engine. */
    Core,

    /** The macOS tunnel daemon; the core behind it stays as it was. */
    Daemon,

    /** The olcRTC engine itself, from its direct rules. */
    Engine
}

/**
 * Where [mode] can apply the rules [needsRules] asks for. A rule that sends a
 * connection direct needs a socket that leaves outside the tunnel:
 *
 * - the proxy's sockets are ordinary ones;
 * - the macOS daemon binds its own to the physical interface;
 * - in the Linux tunnel only root's traffic keeps the main table, and of what
 *   runs there only the olcRTC engine runs as root. A core runs as the user and
 *   reaches its server bound to the physical interface (startDesktopCore); its
 *   rules have not been moved there;
 * - in the Windows tunnel nothing does yet.
 *
 * The proxy keeps its front for olcRTC: it also does what the engine cannot,
 * "only blocked sites" and a tunnel rule under a whole-TLD entry.
 */
internal fun desktopRulesHome(mode: DesktopMode, isOlcrtc: Boolean, needsRules: Boolean): DesktopRulesHome =
    when {
        !needsRules -> DesktopRulesHome.Nowhere
        mode == DesktopMode.SystemProxy -> DesktopRulesHome.Core
        mode == DesktopMode.MacTun -> DesktopRulesHome.Daemon
        mode == DesktopMode.LinuxTun && isOlcrtc -> DesktopRulesHome.Engine
        else -> DesktopRulesHome.Nowhere
    }
