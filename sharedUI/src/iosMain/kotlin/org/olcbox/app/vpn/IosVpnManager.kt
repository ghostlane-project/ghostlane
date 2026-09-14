package org.olcbox.app.vpn

import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.ktor.client.request.get
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ios.IosBridgeCallback
import org.olcbox.app.ios.IosBridgeResult
import org.olcbox.app.ios.IosLogWriter
import org.olcbox.app.ios.IosPacketTunnelBridge
import org.olcbox.app.ios.IosPacketTunnelStartRequest
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.Routing
import org.olcbox.app.net.RuleSets
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.ios.IosOlcRtcBridge
import org.olcbox.app.ios.IosOlcRtcCheckRequest
import org.olcbox.app.ios.IosOlcRtcStartRequest
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import org.olcbox.app.util.nowMillis
import platform.Foundation.NSUserDefaults
import org.olcbox.app.log.LogScrubber

class IosVpnManager(
    private val locationsRepository: LocationsRepository,
    private val olcRtcBridge: IosOlcRtcBridge,
    private val packetTunnelBridge: IosPacketTunnelBridge
) : VpnManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    override val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    private val _traffic = MutableStateFlow<TrafficCounters?>(null)
    override val traffic: StateFlow<TrafficCounters?> = _traffic.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(loadSocksProxySettings())
    val socksProxySettings: StateFlow<ApplicationSocksProxySettings> = _socksProxySettings.asStateFlow()

    private var operationJob: Job? = null
    private var generation = 0L

    // Auto-reconnect state. The iOS transport (Go/WebRTC) does not restart ICE on
    // its own, so when the underlying connection drops (network migration, the app
    // being briefly suspended, TURN failures) we detect it and rebuild the SOCKS
    // session ourselves — mirroring what OlcboxVpnService does on Android.
    private var desiredConnected = false
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private val timeSource = TimeSource.Monotonic
    private var lastReadyMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastStopMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var systemSyncJob: Job? = null

    /**
     * The location the running tunnel was built from, which is not always the
     * one the list has selected — a tunnel outlives the app, and the one adopted
     * on launch was started by a process that is gone.
     */
    private var activeConfig: LocationConfig? = null

    init {
        startSystemStateSync()
        olcRtcBridge.setLogWriter(object : IosLogWriter {
            override fun writeLog(message: String) {
                message
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        addLog("rtc: $it")
                        handleRtcLine(it)
                    }
            }
        })
    }

    override fun needsPermission(): Boolean = false

    override fun diagnosticsLog(): String = packetTunnelBridge.engineLog()

    override fun startVpn() {
        desiredConnected = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        val requestedGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestedGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                    _status.value is VpnStatus.Connecting ||
                    _status.value is VpnStatus.Reconnecting ||
                    packetTunnelBridge.isRunning()

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting the packet tunnel")
                    packetTunnelBridge.stop()
                    if (requestedGeneration != generation) return@withLock
                }

                startActiveLocation(requestedGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        desiredConnected = false
        activeConfig = null
        lastStopMark = timeSource.markNow()
        watchdogJob?.cancel()
        reconnectJob?.cancel()
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                setStatus(VpnStatus.Stopping)
                // The in-app SOCKS provider is no longer part of connecting —
                // olcRTC runs in the extension like everything else — but a build
                // installed over one that did start it would otherwise leave it
                // running, and stopping something already stopped costs nothing.
                stopOlcRtc()
                packetTunnelBridge.stop()
                setStatus(VpnStatus.Disconnected)
                addLog("Stopped")
            }
        }
    }

    /**
     * Whether a latency figure can honestly be produced for this location.
     *
     * Two cases, and nothing else:
     *
     *  * **The live one.** While the tunnel is up, every request this app makes
     *    already goes through it, so timing one measures the real path — for
     *    Reality, Hysteria2, XHTTP and olcRTC alike. That is the number a user
     *    actually wants, and the only one that is about the connection they
     *    have rather than one they might have.
     *  * **An olcRTC room while nothing is connected.** The prober can join it
     *    and time a request. Expensive — a whole second session — but real.
     *
     * Everything else is unmeasurable from here: the cores for Reality,
     * Hysteria2 and XHTTP live in the tunnel extension, which runs one location
     * at a time. Probing them anyway produced a null, which the list drew as
     * **Offline** — working exits, marked dead, by a check that never had a way
     * to succeed.
     */
    override fun canPing(locationConfig: LocationConfig): Boolean {
        val config = locationConfig.normalized()
        if (!config.isComplete()) return false
        // Connected, the active location is still measurable: that path times a request
        // through the tunnel that is already up and joins nothing.
        if (_status.value is VpnStatus.Connected) return config == activeConfig
        // Disconnected, olcRTC is not. The only way to time a room is `mobile.Ping`,
        // which JOINS it as a real client, waits for the session to be ready and tears
        // it down — a genuine peer occupying a node whose entire capacity is single
        // digits, to produce a time-to-join that is not comparable with the ICMP numbers
        // on the rows beside it. A button that says "measure" and quietly connects is
        // worth less than no button.
        if (config.kind == LocationKind.Olcrtc) return false
        return serverHost(config) != null
    }

    /**
     * The address a location's traffic is actually sent to, when it has one.
     *
     * olcRTC does not: it is addressed by a room on somebody else's SFU, so
     * there is no host to reach and its own prober is the only measurement.
     */
    private fun serverHost(config: LocationConfig): String? =
        config.rawLink
            ?.let { LinkParser.parse(it) }
            ?.host
            ?.takeIf { it.isNotBlank() }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        val config = locationConfig.normalized()
        if (_status.value is VpnStatus.Connected) {
            // Never the olcRTC prober while connected: it would open a second
            // session to the same room from the same device, which costs the
            // operator and has confused this before.
            return if (config == activeConfig) measureThroughTunnel() else null
        }
        if (config.kind == LocationKind.Olcrtc) {
            return runCheck(config) { request -> olcRtcBridge.ping(request) }
        }
        // Reality, Hysteria2 and XHTTP: no core here to negotiate with, so the
        // measurement is of the path. Hysteria2 in particular refuses every
        // other probe going — it answers no TCP, and its UDP is obfuscated —
        // which is why a subscription full of it had nothing to show at all.
        val label = config.displayName().ifBlank { "location" }
        val host = serverHost(config) ?: run {
            addLog("ping $label: no server address in the link")
            return null
        }
        val result = withContext(Dispatchers.Default) {
            packetTunnelBridge.icmpLatencyMs(host, ICMP_TIMEOUT_MS)
        }
        // Said out loud, because this has now been guessed at twice. Whatever
        // comes back, the log names the host and what happened to it.
        addLog(
            when {
                result >= 0 -> "ping $label: $host answered in ${result}ms"
                result == ICMP_UNRESOLVED -> "ping $label: could not resolve $host"
                result == ICMP_SOCKET_REFUSED ->
                    "ping $label: this device would not open an ICMP socket"
                result == ICMP_SEND_FAILED -> "ping $label: could not send an echo to $host"
                else -> "ping $label: $host did not answer within ${ICMP_TIMEOUT_MS}ms"
            }
        )
        return result.takeIf { it >= 0 }
    }

    /**
     * Times one request through whatever is carrying traffic right now.
     *
     * A 204 is chosen so nothing is downloaded and no proxy is tempted to cache
     * it. A failure here means the tunnel is up and not carrying — which is
     * worth showing as such, and is exactly the state a user calls "connected
     * but nothing loads".
     */
    private suspend fun measureThroughTunnel(): Long? = withContext(Dispatchers.Default) {
        val client = createProxyHttpClient(
            subscriptionProxy = null,
            connectTimeoutMs = TUNNEL_PROBE_TIMEOUT_MS,
            requestTimeoutMs = TUNNEL_PROBE_TIMEOUT_MS,
            socketTimeoutMs = TUNNEL_PROBE_TIMEOUT_MS
        )
        try {
            val started = timeSource.markNow()
            val status = client.get(HTTP_PING_URL).status.value
            if (status !in 200..399) return@withContext null
            started.elapsedNow().inWholeMilliseconds
        } catch (_: Exception) {
            null
        } finally {
            runCatching { client.close() }
        }
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return runCheck(locationConfig) { request -> olcRtcBridge.check(request) }
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = ApplicationSocksProxySettings(
            port = sanitizePort(port),
            username = username.trim().take(MAX_CREDENTIAL_LENGTH).ifBlank { generateCredential(USERNAME_LENGTH) },
            password = password.trim().take(MAX_CREDENTIAL_LENGTH).ifBlank { generateCredential(PASSWORD_LENGTH) }
        )
        _socksProxySettings.value = settings
        saveSocksProxySettings(settings)
    }

    fun regenerateSocksProxyPassword() {
        val current = _socksProxySettings.value
        updateSocksProxySettings(
            username = current.username,
            password = generateCredential(PASSWORD_LENGTH),
            port = current.port
        )
    }

    fun close() {
        desiredConnected = false
        systemSyncJob?.cancel()
        watchdogJob?.cancel()
        reconnectJob?.cancel()
        generation++
        runCatching { olcRtcBridge.setLogWriter(null) }
        runCatching { olcRtcBridge.stop() }
        scope.cancel()
    }

    /**
     * One path for every transport.
     *
     * olcRTC used to run inside the app as a SOCKS provider that other apps had
     * to be pointed at by hand, kept alive in the background by playing silent
     * audio. It runs in the extension now, like the other three, so there is one
     * mechanism to reason about and the device's traffic goes through it.
     */
    private suspend fun startActiveLocation(requestedGeneration: Long, isRestart: Boolean) {
        val location = locationsRepository.getActiveLocation()?.location?.normalized()
        if (location == null) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a location before connecting")
            return
        }
        startPacketTunnel(location, requestedGeneration, isRestart)
    }

    private suspend fun startPacketTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isRestart: Boolean
    ) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val request = packetTunnelRequest(location) ?: return
        // What the extension actually runs: xhttp is Xray behind
        // hev-socks5-tunnel since 1.0.425 (docs/ios-one-go-runtime.md); olcRTC
        // still sits behind sing-box; the rest is sing-box alone.
        val engine = when {
            request.olcrtc != null -> "olcrtc+sing-box"
            request.xrayConfig != null -> "xray+hev"
            else -> "sing-box"
        }
        addLog("Starting packet tunnel, transport=${location.kind}, engine=$engine")

        val result = startTunnel(request)
        if (requestedGeneration != generation) return

        if (result.success) {
            activeConfig = location
            setStatus(VpnStatus.Connected)
            addLog("Packet tunnel up")
            reconnectAttempt = 0
            lastReadyMark = timeSource.markNow()
            startWatchdog()
        } else {
            val message = result.message ?: "packet tunnel start failed"
            setStatus(VpnStatus.Error(message))
            addLog("Packet tunnel start failed: $message")
            // The whole engine log, not the few lines the screen can hold. This
            // is what makes "share logs" worth asking anyone for: the last line
            // says a start timed out, and the lines above it say what it was
            // doing for those eight seconds.
            packetTunnelBridge.engineLog()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { addLog("engine: $it") }
            packetTunnelBridge.stop()
        }
    }

    /**
     * What the extension needs for this location, or null once the reason it
     * cannot be built has been reported.
     */
    private suspend fun packetTunnelRequest(
        location: LocationConfig
    ): IosPacketTunnelStartRequest? {
        val routing = routing()
        val ruleSets = ruleSetsFor(routing)

        // olcRTC has no link to parse — a room and a key address it — so it is
        // read off the location rather than through LinkParser.
        if (location.kind == LocationKind.Olcrtc) {
            if (!location.isComplete()) {
                setStatus(VpnStatus.Error("No active location"))
                addLog("Add a valid location before connecting")
                return null
            }
            // The port the user can set belongs to the in-app proxy, which no
            // longer carries the connection. Inside the extension this is
            // loopback between two of our own cores, so it is fixed, like Xray's.
            val settings = _socksProxySettings.value
                .copy(port = SingBoxConfig.SINGBOX_SOCKS_PORT)
            return IosPacketTunnelStartRequest(
                // The same credentials olcRTC is about to be started with. It
                // demands them, and sing-box is the only thing that connects.
                config = SingBoxConfig.buildTunSocks(
                    SingBoxConfig.SINGBOX_SOCKS_PORT,
                    username = settings.username,
                    password = settings.password,
                    // olcRTC does relay UDP, but over a lossy video carrier.
                    // Calls and games want that path; name resolution does
                    // not, so sing-box answers DNS itself and asks upstream
                    // over TCP.
                    upstreamUdpIsLossy = true,
                    routing = routing
                ),
                xrayConfig = null,
                olcrtc = location.startRequest(locationsRepository.getDeviceIdentity(), settings),
                ruleSets = ruleSets
            )
        }

        val spec = location.rawLink?.let { LinkParser.parse(it) }
        if (spec == null) {
            setStatus(VpnStatus.Error("Location cannot be parsed"))
            addLog("No usable link on the active location")
            return null
        }

        // Same builders Android and desktop use, so a transport gaining a field
        // reaches iOS without anyone remembering to update a second copy.
        //
        // xhttp is the one transport sing-box does not implement, so it runs on
        // Xray and sing-box becomes the tun front-end for it. Reality and
        // hysteria2 are native sing-box outbounds with no second core involved.
        val vless = spec as? OutboundSpec.Vless
        val xrayConfig = if (vless != null && vless.transport is TransportSpec.Xhttp) {
            XrayConfig.buildXhttp(vless)
        } else {
            null
        }
        return IosPacketTunnelStartRequest(
            config = if (xrayConfig != null) {
                SingBoxConfig.buildTunSocks(XrayConfig.XRAY_SOCKS_PORT, routing = routing)
            } else {
                SingBoxConfig.buildTun(spec, routing = routing)
            },
            xrayConfig = xrayConfig,
            olcrtc = null,
            ruleSets = ruleSets
        )
    }

    /**
     * The persisted routing choice, resolved for the extension: rule files by
     * the relative path libbox resolves against its working directory, and a
     * placeholder where the direct resolver goes, because only the extension
     * can read the network's own before the tunnel replaces it.
     */
    private suspend fun routing(): Routing =
        when (val mode = locationsRepository.getRoutingSettings().mode) {
            RoutingMode.Global -> Routing.Global
            RoutingMode.BypassRussia -> {
                addLog("Routing: ${mode.hubSummary()}")
                Routing.BypassRussia(RuleSets.IOS_RELATIVE_DIR, DirectDns.Placeholder)
            }
        }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun ruleSetsFor(routing: Routing): Map<String, String> =
        if (routing is Routing.BypassRussia) {
            RuleSets.all.associate { it.name to Base64.encode(RuleSets.bytes(it)) }
        } else {
            emptyMap()
        }

    /**
     * Turns the extension's callback back into a suspending call.
     *
     * Nothing blocks while the tunnel settles, which is the whole point: the
     * shape this replaces held a thread on a semaphore for those seconds. The
     * resume is guarded because a second one is a crash, and the caller of this
     * callback is Swift.
     */
    private suspend fun startTunnel(request: IosPacketTunnelStartRequest): IosBridgeResult =
        suspendCancellableCoroutine { continuation ->
            packetTunnelBridge.start(
                request,
                object : IosBridgeCallback {
                    override fun onResult(result: IosBridgeResult) {
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            )
        }

    private suspend fun runCheck(
        locationConfig: LocationConfig,
        block: (IosOlcRtcCheckRequest) -> org.olcbox.app.ios.IosLongResult
    ): Long? = withContext(Dispatchers.Default) {
        val config = locationConfig.normalized()
        if (!config.isComplete()) return@withContext null
        val request = IosOlcRtcCheckRequest(
            carrierName = config.bypassProvider,
            transportName = config.transport,
            roomId = config.id,
            clientId = locationsRepository.getDeviceIdentity(),
            keyHex = config.key,
            timeoutMillis = CHECK_TIMEOUT_MS,
            pingUrl = HTTP_PING_URL,
            vp8Fps = config.vp8Fps,
            vp8BatchSize = config.vp8Batch
        )
        val result = block(request)
        if (result.success && result.valueMillis >= 0L) result.valueMillis else null
    }

    private fun stopOlcRtc(): IosBridgeResult {
        return runCatching {
            olcRtcBridge.stop()
            IosBridgeResult(success = true, message = null)
        }.getOrElse {
            IosBridgeResult(success = false, message = it.message)
        }
    }

    /**
     * Parses olcRTC log lines to detect transport health. The native layer never
     * performs an ICE restart, so a dropped connection stays dead until we rebuild
     * it. We treat "connected/listening" markers as healthy and "failed/closed/
     * broken pipe" markers as a lost transport that must be reconnected.
     */
    private fun handleRtcLine(line: String) {
        if (!desiredConnected) return
        val lower = line.lowercase()

        if (lower.contains("socks5 server listening") ||
            lower.contains("ice connection state changed: connected") ||
            lower.contains("peer connection state changed: connected")
        ) {
            reconnectAttempt = 0
            lastReadyMark = timeSource.markNow()
            return
        }

        val transportLost = lower.contains("ice connection state changed: failed") ||
            lower.contains("peer connection state changed: failed") ||
            lower.contains("ice connection state changed: closed") ||
            lower.contains("peer connection state changed: closed") ||
            lower.contains("read/write on closed pipe") ||
            lower.contains("use of closed network connection")

        if (transportLost) {
            // Ignore teardown noise that immediately follows a fresh (re)connect.
            val recentlyReady = lastReadyMark
                ?.elapsedNow()
                ?.inWholeMilliseconds
                ?.let { it < POST_CONNECT_GRACE_MS }
                ?: false
            if (recentlyReady) return
            scheduleReconnect("RTC transport lost")
        }
    }

    /**
     * Adopts a tunnel that is already running.
     *
     * The extension is a separate process with a life of its own. iOS suspends
     * and then terminates a backgrounded app routinely, while the tunnel keeps
     * carrying traffic — so the next launch starts from `Disconnected` with a
     * VPN plainly working on the device. Nothing here ever asked the system
     * otherwise, and the app showed "relay idle" over a live tunnel until the
     * user tapped START, which then restarted a tunnel that was fine.
     *
     * This runs whether or not the app believes it is connected, because the
     * case it exists for is precisely the one where it believes nothing.
     */
    private fun startSystemStateSync() {
        systemSyncJob?.cancel()
        systemSyncJob = scope.launch {
            while (isActive) {
                adoptRunningTunnelIfAny()
                sampleTraffic()
                delay(SYSTEM_SYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * Reads the tunnel interface's counters. Cheap — a `getifaddrs` walk — and
     * on the same tick as the state sync so there is one timer, not two.
     */
    private fun sampleTraffic() {
        if (!packetTunnelBridge.isRunning()) {
            _traffic.value = null
            return
        }
        _traffic.value = TrafficCounters(
            bytesIn = packetTunnelBridge.tunnelBytesIn(),
            bytesOut = packetTunnelBridge.tunnelBytesOut()
        )
    }

    private suspend fun adoptRunningTunnelIfAny() {
        if (!canAdopt()) return
        // Under the same lock as start and stop, so a tick that coincides with a
        // tap cannot interleave with it. Re-checked inside, because the tap may
        // have been what was holding the lock.
        mutex.withLock {
            if (!canAdopt()) return@withLock

            desiredConnected = true
            reconnectAttempt = 0
            lastReadyMark = timeSource.markNow()
            // Started by a process that no longer exists, so the best available
            // answer to "what is this tunnel carrying" is what the app would
            // start today. Wrong only if the selection changed while the app was
            // dead, and then the next connect corrects it.
            activeConfig = locationsRepository.getActiveLocation()?.location?.normalized()
            setStatus(VpnStatus.Connected)
            addLog("Adopted a packet tunnel that was already running")
            startWatchdog()
        }
    }

    private fun canAdopt(): Boolean {
        // While the user wants a connection, the watchdog owns the state.
        if (desiredConnected) return false
        if (_status.value !is VpnStatus.Disconnected) return false
        // A stop takes a moment to reach the extension, and the system reports
        // the tunnel as up throughout it. Adopting in that window would undo the
        // tap that asked for the stop.
        val stoppedRecently = lastStopMark
            ?.elapsedNow()
            ?.inWholeMilliseconds
            ?.let { it < ADOPT_AFTER_STOP_GRACE_MS }
            ?: false
        if (stoppedRecently) return false
        return packetTunnelBridge.isRunning()
    }

    /**
     * Periodically verifies the tunnel is still up while the user wants to stay
     * connected, catching silent deaths that produce no log marker.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && desiredConnected) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!desiredConnected) break
                val stalled = _status.value is VpnStatus.Connected &&
                    reconnectJob?.isActive != true &&
                    !packetTunnelBridge.isRunning()
                if (stalled) {
                    addLog("Watchdog: the packet tunnel is down")
                    scheduleReconnect("transport stopped")
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!desiredConnected) return
        if (reconnectJob?.isActive == true) return
        val status = _status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        reconnectJob = scope.launch {
            setStatus(VpnStatus.Reconnecting)
            addLog("Auto-reconnect requested ($reason)")

            // Keep retrying with exponential backoff until we reconnect or the user
            // turns the connection off. A single failed attempt (e.g. no network yet)
            // must not give up — that is what left the transport dead before.
            while (desiredConnected && isActive) {
                val delayMs = nextReconnectDelay()
                addLog("Reconnecting in ${delayMs / 1000}s")
                delay(delayMs)
                if (!desiredConnected) return@launch

                val requestedGeneration = ++generation
                val reconnected = mutex.withLock {
                    if (requestedGeneration != generation || !desiredConnected) return@withLock false
                    packetTunnelBridge.stop()
                    startActiveLocation(requestedGeneration, isRestart = true)
                    _status.value is VpnStatus.Connected
                }

                if (reconnected || !desiredConnected) return@launch
                // startActiveLocation reports failure via Error status; keep the
                // user-facing state as Reconnecting so the retry loop stays coherent.
                if (_status.value !is VpnStatus.Reconnecting) setStatus(VpnStatus.Reconnecting)
            }
        }
    }

    private fun nextReconnectDelay(): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (RECONNECT_BASE_DELAY_MS * multiplier).coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        _connectedSince.value = when (status) {
            // The system's own establishment date first: after the app has been
            // killed and relaunched over a live tunnel, a clock started when
            // *this process* noticed would read minutes for a session hours old.
            // Only the first Connected of a session stamps it — a reconnect
            // passes through Reconnecting and back, and must not restart it.
            VpnStatus.Connected ->
                packetTunnelBridge.connectedSinceEpochMs().takeIf { it > 0L }
                    ?: _connectedSince.value
                    ?: nowMillis()

            VpnStatus.Reconnecting -> _connectedSince.value
            else -> null
        }
    }

    private fun addLog(message: String) {
        // After handleRtcLine, never before it: that matcher reads the raw text.
        _logs.value =
            (_logs.value + LogScrubber.default.scrub(message)).takeLast(MAX_LOG_LINES)
    }

    private fun LocationConfig.startRequest(
        deviceId: String,
        settings: ApplicationSocksProxySettings
    ): IosOlcRtcStartRequest {
        val config = normalized()
        return IosOlcRtcStartRequest(
            carrierName = config.bypassProvider,
            transportName = config.transport,
            roomId = config.id,
            clientId = deviceId,
            keyHex = config.key,
            socksPort = settings.port,
            socksUser = settings.username,
            socksPass = settings.password,
            vp8Fps = config.vp8Fps,
            vp8BatchSize = config.vp8Batch
        )
    }

    private fun loadSocksProxySettings(): ApplicationSocksProxySettings {
        val defaults = NSUserDefaults.standardUserDefaults
        val port = sanitizePort(defaults.integerForKey(KEY_SOCKS_PORT).toInt())
        val username = defaults.stringForKey(KEY_SOCKS_USERNAME)
            ?.takeIf { it.isNotBlank() }
            ?: generateCredential(USERNAME_LENGTH)
        val password = defaults.stringForKey(KEY_SOCKS_PASSWORD)
            ?.takeIf { it.isNotBlank() }
            ?: generateCredential(PASSWORD_LENGTH)
        return ApplicationSocksProxySettings(
            port = port,
            username = username,
            password = password
        ).also { saveSocksProxySettings(it) }
    }

    private fun saveSocksProxySettings(settings: ApplicationSocksProxySettings) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(settings.port.toLong(), KEY_SOCKS_PORT)
        defaults.setObject(settings.username, KEY_SOCKS_USERNAME)
        defaults.setObject(settings.password, KEY_SOCKS_PASSWORD)
    }

    private fun sanitizePort(port: Int): Int {
        return if (ApplicationSocksProxySettings.isValidPort(port)) {
            port
        } else {
            ApplicationSocksProxySettings.DEFAULT_PORT
        }
    }

    private fun generateCredential(length: Int): String {
        val boundedLength = min(max(length, 1), MAX_CREDENTIAL_LENGTH)
        return buildString(boundedLength) {
            repeat(boundedLength) {
                append(CREDENTIAL_ALPHABET[Random.nextInt(CREDENTIAL_ALPHABET.length)])
            }
        }
    }

    private companion object {
        const val KEY_SOCKS_PORT = "ios_socks_port"
        const val KEY_SOCKS_USERNAME = "ios_socks_username"
        const val KEY_SOCKS_PASSWORD = "ios_socks_password"
        const val USERNAME_LENGTH = 12
        const val PASSWORD_LENGTH = 24
        const val MAX_CREDENTIAL_LENGTH = 64
        const val MAX_LOG_LINES = 500
        // Twenty, not eight: joining an olcRTC room and timing a request through
        // it is the same negotiation the tunnel makes, and eight seconds was
        // measured to be short for it. At eight this probe could not succeed,
        // and every olcRTC row it touched was drawn Offline.
        const val CHECK_TIMEOUT_MS = 20_000L
        /** One request through a tunnel that is already up; nothing to negotiate. */
        const val TUNNEL_PROBE_TIMEOUT_MS = 6_000L
        /** One echo and back. Anything slower than this is not a usable exit. */
        const val ICMP_TIMEOUT_MS = 3_000L

        // Mirrors IcmpProbe.Failure on the Swift side.
        const val ICMP_UNRESOLVED = -2L
        const val ICMP_SOCKET_REFUSED = -3L
        const val ICMP_SEND_FAILED = -4L
        const val HTTP_PING_URL = "https://www.google.com/generate_204"
        const val WATCHDOG_INTERVAL_MS = 10_000L
        const val SYSTEM_SYNC_INTERVAL_MS = 3_000L
        const val ADOPT_AFTER_STOP_GRACE_MS = 10_000L
        const val RECONNECT_BASE_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val MAX_RECONNECT_BACKOFF_POWER = 3
        const val POST_CONNECT_GRACE_MS = 4_000L
        const val CREDENTIAL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
