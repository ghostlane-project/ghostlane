package org.olcbox.app.vpn.service.engine

import android.net.Network
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.log.LogScrubber
import org.olcbox.app.net.IosCore
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcDirectRules
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.core.CoreBlockedOnly
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.lines.Reality
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.OlcRtcUdpRelay
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.olc.OlcSession
import org.olcbox.app.vpn.rooms.OlcrtcRoomKeeper
import org.olcbox.app.vpn.rooms.OlcrtcRoomList
import org.olcbox.app.vpn.rooms.RoomFetchOutcome
import org.olcbox.app.vpn.rooms.RoomMemoryFile
import org.olcbox.app.vpn.rooms.RoomMemoryRecord
import org.olcbox.app.vpn.rooms.fetchRoomList
import org.olcbox.app.vpn.service.RustCore
import org.olcbox.app.vpn.service.RustCoreBackend
import org.olcbox.app.vpn.service.RustOlcParams
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * ghostlane-core behind the seam (RustCore.backend: xray-rust's core through
 * its C ABI, the olcRTC client through UniFFI), as the service ran it behind
 * the developer switch. The code is the service's own, moved; [host] is the
 * service. What is not built on this engine yet is asked of a hook, one file
 * per concern: [RustProxyMode], [RustProbe], [RustLiveness].
 *
 * [go] is the Go session of the same service. While both engines are in the
 * build a Rust start still clears the Go cores, a stop still stops the Go
 * runtime, and what has no Rust form yet (the hooks say which) is the Go
 * session's.
 */
internal class RustEngineSession(
    private val host: EngineHost,
    private val go: GoEngineSession
) : EngineSession, EngineHost by host {

    private var rustRoomsJob: Job? = null
    /**
     * The running olcRTC start's room keeper and its HTTP client (startRustRoomKeeper), swapped
     * under [rustKeeperLock]: a superseded start and the main thread's stop can meet here.
     */
    @Volatile private var rustRoomKeeper: OlcrtcRoomKeeper? = null
    private var rustRoomsHttp: io.ktor.client.HttpClient? = null
    private val rustKeeperLock = Any()
    /** A pending core reset after a network change (resetRustCoreForNetwork). */
    private var rustResetJob: Job? = null
    /** The running core line's config names the network's resolver (a Bypass line). */
    @Volatile private var rustCoreBypass = false

    /** The Go runtime's: it is the one the service's process still carries callbacks for. */
    override fun installCallbacks() = go.installCallbacks()

    /** The Go engines first, as the service stopped them whichever engine ran, then this one's. */
    override fun stop() {
        go.stop()
        stopRustEngines()
    }

    override fun isTransportRunning(): Boolean = RustLiveness.isRunning(RustCore.backend)

    override fun diagnostics(): String = RustLiveness.diagnostics(go)

    override fun networkChanged(): Boolean = resetRustCoreForNetwork()

    override fun underlyingNetworkChanged(network: Network) = go.underlyingNetworkChanged(network)

    override fun trimMemory(level: Int) = relieveRustCore(level)

    /**
     * A verified olcRTC line asks its subscription for the current rooms, as the
     * iPhone app does on Connected (throttled by the keeper: a re-verify does not re-ask).
     */
    override fun connected() {
        rustRoomKeeper?.refreshNow("connected")
    }

    /**
     * The core's password inbound refuses UDP ASSOCIATE for 0.0.0.0:0: a core
     * line's UDP rides hev's FWD UDP lane, as on iOS (TunArrangement.hevUdp).
     */
    override fun hevUdpLane(): String = if (activeCorePort != null) "tcp" else "udp"

    override val probeStarter: TransportProbe.Starter get() = RustProbe.starter(go)

    /**
     * An olcRTC room on the core's olc runtime or a core line on the xray core.
     * The arrangement is iOS's (CoreEngine.swift, OlcrtcEngine.swift): the engine
     * listens on a loopback SOCKS port that demands a login, hev owns the tun in
     * front of it, and every socket the engine opens goes through
     * VpnService.protect first.
     */
    override suspend fun start(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        // Asked here as the service always did before it chose an engine: the rule files are written.
        routingFor(upstream)
        val backend = RustCore.backend
        val isOlc = location.kind == LocationKind.Olcrtc
        val tun = connectionMode == AndroidConnectionMode.Tun
        val port = if (isOlc) socksListenPort else if (tun) sessionCorePort.acquire() else socksListenPort
        return try {
            // A room on a carrier or a lane the Rust client no longer carries is refused before any
            // engine is asked or stopped, in the sentence the iPhone's start gives (IosCore.start).
            if (isOlc) OlcSession.refusal(location.normalized())?.let { error(IosCore.refused(location, it)) }
            checkNotNull(backend) { "the Rust core is not packaged in this build" }
            backend.load()
            RustProxyMode.refusal(tun = tun, isOlc = isOlc, listenHost = socksListenHost)?.let { error(it) }
            stopRustEngines()
            go.stopCores()
            if (isOlc) startRustOlc(backend, location, upstream, requestedGeneration, port) else startRustLine(backend, location, upstream, port, tun)
            coroutineContext.ensureActive()
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("Rust core start canceled")
                stopRustEngines()
                unbindProcessFromNetwork()
            }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Rust core transport failed"
            addLog("Rust core start failed: $msg")
            stopRustEngines()
            unbindProcessFromNetwork()
            if (!isOlc && tun) sessionCorePort.release()
            if (setErrorOnFailure && requestedGeneration == generation) {
                setStatus(VpnStatus.Error(msg))
                updateNotification("Connection failed")
            }
            false
        }
    }

    /**
     * A line of any kind the core takes (VLESS, Reality, Hysteria 2, Trojan, Shadowsocks, VMess) on
     * the xray core, from the config iOS runs (CoreConfig.build). In the tun's session the
     * blocked-only mode is written too, with Re:filter's list in the core's geodata directory
     * ([refilterDir]); proxy mode refuses it.
     */
    private suspend fun startRustLine(backend: RustCoreBackend, location: LocationConfig, upstream: Network, port: Int, tun: Boolean) {
        // A core binds no network of its own; see startTransport.
        unbindProcessFromNetwork()
        // The tun's session demands the app's login; proxy mode runs open, as the Go core line does (activeCoreLogin stays null).
        val login = if (tun) SocksLogin.of(socksUsername, socksPassword) else null
        // Whether core.json names this network's resolver (the Bypass's direct side): such a
        // line is restarted on a network change rather than reset (resetRustCoreForNetwork).
        var namesNetworkResolver = false
        // The one directory the core reads geodata files from, for a config that names one (the
        // blocked-only mode's list); null for every other config, which names none.
        var geodataDir: String? = null
        val json = RustCore.testConfig?.invoke(port, login) ?: run {
            val raw = location.rawLink ?: error("core location has no link")
            var spec = LinkParser.parse(raw) ?: error("unparseable core link")
            // A server named by a hostname is resolved here, on the upstream network: this process is
            // outside the tun, so the question does not loop, and the core is given the address.
            if (!XrayConfig.isIpLiteral(spec.host)) {
                val address = resolveServerAddress(spec.host, upstream)
                addLog("Rust core: the server's name resolved to an address")
                spec = IosCore.pinHost(spec, address)
            }
            IosCore.refusal(spec)?.let { error("the Rust core refuses this line: $it") }
            // The hello the core sends in place of the one the link asks for, said where the start
            // is decided, as the iPhone's start says it (IosStart.Core's notes).
            Reality.helloNote(spec)?.let { addLog("Rust core: $it") }
            // What the core builds: Global, a region's Bypass with its lists inline (as iOS), or only
            // blocked sites with Re:filter's list read from a file (CoreBlockedOnly); each with the
            // user's own direct and tunnel rules ahead of the lists (IosCore.effective: tunnel rules
            // alone under Global are Global).
            val routing = IosCore.effective(routingFor(upstream))
            val rules = routing as? Routing.RuleBased
            namesNetworkResolver = rules != null
            val blockedOnly = rules?.policy == Routing.Policy.BlockedOnly
            val geodata = (rules?.policy as? Routing.Policy.Bypass)?.let { XrayGeodata.lists(it.region) }
            if (tun) {
                if (blockedOnly) geodataDir = refilterDir(backend)
                CoreConfig.build(
                    spec,
                    login ?: error("the Rust core needs the tun's SOCKS login"),
                    routing = routing,
                    geodata = geodata,
                    verboseLogs = verboseDebugLogs,
                    socksPort = port,
                    refilterDat = geodataDir != null
                )
            } else {
                // Proxy mode: the phone's other apps use the port, open as on the Go core line (no
                // login). Its config (RustProxyMode) is not handed the list's file yet.
                check(!blockedOnly) { PROXY_BLOCKED_ONLY_REFUSAL }
                RustProxyMode.config(spec, port, socksListenHost, routing, geodata, verboseDebugLogs)
            }
        }
        waitForSocksPortReleased(port, socksReleaseQuickTimeoutMs)
        // No tun in proxy mode: nothing of the core's is looped back into it, so no socket needs protecting.
        val protectFd: (Int) -> Boolean = { fd -> !tun || host.protect(fd) }
        val coreLog: (Int, String) -> Unit = { level, line ->
            addLog("core: $line")
            if (level <= 2) Log.w("ghostlane-core", LogScrubber.default.scrub(line))
        }
        val dir = geodataDir
        if (dir != null) {
            // refilterDir has asked that the engine is one.
            (backend as RustGeodataBackend).startCoreWithGeodata(json, dir, protectFd, coreLog)
        } else {
            backend.startCore(json, protectFd, coreLog)
        }
        activeCorePort = port
        activeCoreLogin = login
        rustCoreBypass = namesNetworkResolver
        if (!waitForSocksPortOpen(port, coreReadyTimeoutMs) || !backend.isCoreRunning()) {
            error("Rust core SOCKS not ready on $port")
        }
        addLog("Rust core ready on $socksListenHost:$port")
    }

    /**
     * The core's geodata directory for the blocked-only mode: one of the app's own, holding
     * Re:filter's list exactly as the app bundles it ([CoreBlockedOnly.FILE], held to its hash),
     * written there from the bundle when it is missing or not that file. The core is told to read
     * geodata from it alone ([RustGeodataBackend]): a list it cannot find there fails the start.
     */
    private suspend fun refilterDir(backend: RustCoreBackend): String {
        check(backend is RustGeodataBackend) { IosCore.BLOCKED_ONLY_REFUSAL }
        return withContext(Dispatchers.IO) {
            val file = CoreBlockedOnly.FILE
            val dir = File(context.filesDir, RUST_GEODATA_DIR)
            val target = File(dir, file.name)
            if (!target.isFile || sha256Hex(target.readBytes()) != file.sha256) {
                val bytes = XrayGeodata.bytes(file)
                check(sha256Hex(bytes) == file.sha256) { "the app's copy of ${file.name} is not the one it was built with" }
                check(dir.isDirectory || dir.mkdirs()) { "the core's geodata directory could not be made" }
                val partial = File(dir, "${file.name}.partial")
                partial.writeBytes(bytes)
                check(partial.renameTo(target)) { "${file.name} could not be put in the core's geodata directory" }
                addLog("Rust core: Re:filter's list written to the core's geodata directory")
            }
            dir.absolutePath
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** An IPv4 address of [host] from the upstream network's resolver (Android blocks IPv6 at the tun), else any. */
    private suspend fun resolveServerAddress(host: String, upstream: Network): String =
        withContext(Dispatchers.IO) {
            val all = runCatching { upstream.getAllByName(host).toList() }
                .getOrElse { error("the server's name did not resolve: ${it.message ?: it}") }
            (all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull())?.hostAddress
                ?: error("the server's name resolved to nothing")
        }

    /** An olcRTC room (VK Calls, MTS Link, SaluteJazz, ...) on the core's olc runtime, as OlcrtcEngine.swift. */
    private suspend fun startRustOlc(
        backend: RustCoreBackend,
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        port: Int
    ) {
        activeCorePort = null
        activeCoreLogin = null
        val config = location.normalized()
        resetRtcHealthState()
        waitForSocksPortReleased(port, socksReleaseQuickTimeoutMs)
        if (isLocalSocksPortOpen(port)) error("SOCKS port $port is still in use")
        bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
        val directRules = OlcrtcDirectRules.forRouting(routingFor(upstream))
        addLog("Starting olcRTC (Rust core) provider=${config.bypassProvider}, transport=${config.transport}")
        val keeper = startRustRoomKeeper(backend, config, port)
        backend.startOlc(
            RustOlcParams(
                provider = config.bypassProvider,
                transport = config.transport,
                room = keeper.rooms.primary,
                failoverRooms = keeper.rooms.extras,
                keyHex = config.key,
                deviceId = deviceHwid().orEmpty(),
                socksHost = socksListenHost,
                socksPort = port,
                socksUser = socksUsername,
                socksPass = socksPassword,
                dns = upstreamDnsList(upstream),
                directRules = directRules,
                vp8Fps = config.vp8Fps.toLong(),
                vp8Batch = config.vp8Batch.toLong(),
                udp = OlcRtcUdpRelay.enabled(config.bypassProvider, config.transport, directRules = directRules.isNotEmpty()),
                dtlsProfile = OlcrtcDtls.profile(routingSettings.olcrtcChromeDtls)
            ),
            protect = { fd -> connectionMode == AndroidConnectionMode.Proxy || host.protect(fd.toInt()) },
            log = { msg ->
                val line = msg.trimEnd()
                addLog("rtc: $line")
                Log.v("olcrtc", LogScrubber.default.scrub(line))
                handleRtcLine(line)
            },
            onSessionOpened = keeper::onSessionOpened
        )
        if (requestedGeneration != generation) error("olcRTC start superseded")
        addLog("olcRTC (Rust core) ready on $socksListenHost:$port")
        markRtcConnected()
        keeper.armed()
        followStoredRooms(keeper, config)
    }

    /**
     * A Rust core line meets a new network as iOS's tunnel does (PacketTunnelProvider: NWPathMonitor
     * → CoreEngine.resetNetwork): the core closes what it holds on the old path; the tun, core.json
     * and the SOCKS port stay. It waits the grace a restart waits, so a flapping network settles
     * first, and a newer change replaces a pending reset. Not for a line whose core.json names the
     * network's resolver (a bypass: [rustCoreBypass]), nor olcRTC (no core port): false, and the
     * caller restarts them; nor a core that is gone or a reset that fails: those restart from here.
     *
     * On the ConnectivityManager callback's thread: nothing here takes the engine's lock (a stop
     * can hold it), the job does.
     */
    private fun resetRustCoreForNetwork(): Boolean {
        val backend = RustCore.backend ?: return false
        val port = activeCorePort ?: return false
        if (rustCoreBypass) return false
        val resetGeneration = generation
        rustResetJob?.cancel()
        rustResetJob = scope.launch {
            delay(networkStabilityGraceMs)
            if (resetGeneration != generation || activeCorePort != port) return@launch
            runCatching { check(backend.isCoreRunning()) { "the core is not running" }; backend.resetNetwork() }
                .onSuccess { addLog("Network changed: Rust core reset $it connection(s), the tun stays") }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    addLog("Rust core reset after a network change failed (${it::class.simpleName}): restarting the line")
                    if (resetGeneration == generation) {
                        requestTransportRecovery(reason = "Upstream network changed", fullRestart = false)
                    }
                }
        }
        return true
    }

    /**
     * The room keeper of an olcRTC start (OlcrtcRoomKeeper, RoomKeeper.swift's port), begun before
     * the engine so that its first session is counted. The start's rooms are the stored location's
     * and, after them, the ones the last running client was handed for the same carrier and key
     * (RoomMemoryRecord, a day at most): a restart reads the store again, and the store holds only
     * what the app's own refresh wrote, which can predate a rotation the client followed.
     *
     * The subscription is asked through the engine's own SOCKS port, as the app's refresh asks
     * while connected (OlcboxVpnState.channelProxy) and as iOS asks through its tun: where the
     * list matters, the tunnel is the way to it. The first session refreshes too: on iOS the app
     * does that on Connected, here the app's screen may not be running at all (always-on).
     */
    private suspend fun startRustRoomKeeper(backend: RustCoreBackend, config: LocationConfig, port: Int): OlcrtcRoomKeeper {
        stopRustRoomKeeper()
        val memory = RoomMemoryFile(File(context.filesDir, RUST_ROOM_MEMORY_FILE))
        val remembered = memory.load()?.rooms(config.bypassProvider, RoomMemoryRecord.keyDigest(config.key), nowSeconds())
        val initial = RoomMemoryRecord.initialRooms(config.id, config.failoverRoomIds, remembered)
        val url = subscriptionUrlFor(config)
        val proxy = org.olcbox.app.data.repository.SubscriptionFetchProxy(
            AndroidSocksProxySettings.connectHost(socksListenHost), port, socksUsername, socksPassword
        )
        return synchronized(rustKeeperLock) {
            stopRustRoomKeeperLocked()
            val http = createProxyHttpClient(
                proxy,
                connectTimeoutMs = RUST_ROOMS_CONNECT_TIMEOUT_MS,
                requestTimeoutMs = RUST_ROOMS_REQUEST_TIMEOUT_MS,
                socketTimeoutMs = RUST_ROOMS_CONNECT_TIMEOUT_MS
            )
            lateinit var keeper: OlcrtcRoomKeeper
            keeper = OlcrtcRoomKeeper(
                parent = scope,
                carrier = config.bypassProvider,
                keyHex = config.key,
                subscriptionUrl = url,
                initial = initial,
                // Not the first session: it opens inside the engine's start, and a fetch then would
                // hold the process's proxy-login lock (withProxyAuthentication) across the tunnel's
                // verification. The keeper is asked once the line is verified instead (setStatus).
                refreshOnFirstSession = false,
                nowMs = SystemClock::elapsedRealtime,
                io = object : OlcrtcRoomKeeper.Io {
                    override suspend fun fetch(url: String): RoomFetchOutcome = withContext(Dispatchers.IO) {
                        val hwid = deviceHwid()
                        withProxyAuthentication(proxy) { fetchRoomList(http, url, hwid) }
                    }

                    override fun apply(rooms: OlcrtcRoomList.Parsed) {
                        // A stopped keeper's last apply must not land on the next start's engine.
                        check(rustRoomKeeper === keeper) { "superseded" }
                        backend.setRooms(rooms.primary, rooms.extras)
                    }

                    override fun remember(rooms: OlcrtcRoomList.Parsed) =
                        memory.save(RoomMemoryRecord.of(rooms, config.bypassProvider, config.key, nowSeconds()))

                    override fun log(line: String) = addLog(line)
                }
            )
            rustRoomsHttp = http
            rustRoomKeeper = keeper
            keeper.begin()
            keeper
        }
    }

    /**
     * The subscription address of the stored location that runs: the active entry when it is this
     * carrier's and key's, else the first such entry (a smart connect may run another). Never logged.
     */
    private suspend fun subscriptionUrlFor(config: LocationConfig): String? {
        val bundle = runCatching { storedBundle() }.getOrNull() ?: return null
        val ours = bundle.locations.filter { entry ->
            val loc = entry.location.normalized()
            loc.key.equals(config.key, ignoreCase = true) && OlcrtcRoomList.sameCarrier(loc.bypassProvider, config.bypassProvider)
        }
        val entry = ours.firstOrNull { it.storageId == bundle.activeLocationId } ?: ours.firstOrNull()
        return entry?.subscriptionUrl?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The app's own refresh, while it runs, writes the location's rooms to the store: the keeper is
     * handed each new list of this carrier and key, as the iPhone app's message hands it to its
     * tunnel (IosVpnManager.pushRoomsToRunningTunnel). Only a list that changed is handed over.
     */
    private fun followStoredRooms(keeper: OlcrtcRoomKeeper, started: LocationConfig) {
        synchronized(rustKeeperLock) { followStoredRoomsLocked(keeper, started) }
    }

    private fun followStoredRoomsLocked(keeper: OlcrtcRoomKeeper, started: LocationConfig) {
        if (rustRoomKeeper !== keeper) return
        rustRoomsJob?.cancel()
        rustRoomsJob = scope.launch {
            var seen: List<String> = started.failoverRooms()
            storedLocationsChanges.drop(1).collect {
                val bundle = runCatching { storedBundle() }.getOrNull() ?: return@collect
                val now = bundle.locations
                    .map { it.storageId to it.location.normalized() }
                    .filter { (_, loc) ->
                        loc.key.equals(started.key, ignoreCase = true) &&
                            OlcrtcRoomList.sameCarrier(loc.bypassProvider, started.bypassProvider)
                    }
                    .let { ours -> (ours.firstOrNull { it.first == bundle.activeLocationId } ?: ours.firstOrNull())?.second }
                    ?: return@collect
                val list = now.failoverRooms()
                if (list == seen) return@collect
                seen = list
                keeper.fromStore(OlcrtcRoomList.Parsed.of(now.id, now.failoverRoomIds), now.bypassProvider, now.key)
            }
        }
    }

    /** Ends the room keeper and its fetches; a no-op when there is none. */
    fun stopRustRoomKeeper() {
        synchronized(rustKeeperLock) { stopRustRoomKeeperLocked() }
    }

    private fun stopRustRoomKeeperLocked() {
        rustRoomsJob?.cancel()
        rustRoomsJob = null
        rustRoomKeeper?.stop()
        rustRoomKeeper = null
        rustRoomsHttp?.close()
        rustRoomsHttp = null
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /** iOS's stop order: the olc client, then the xray core; each a no-op when it is not running. */
    private fun stopRustEngines() {
        stopRustRoomKeeper()
        rustResetJob?.cancel()
        rustResetJob = null
        rustCoreBypass = false
        val backend = RustCore.backend ?: return
        runCatching { backend.stopOlc(RustOlcParams.STOP_TIMEOUT_MS) }
            .onFailure { addLog("Rust olcRTC stop failed: ${it.message ?: it}") }
        runCatching { backend.stopCore() }
            .onFailure { addLog("Rust core stop failed: ${it.message ?: it}") }
        activeCorePort = null
        activeCoreLogin = null
    }

    /**
     * MemoryRelief.swift's policy on Android's own signal: trim at once, and under real
     * pressure lower the byte budget (1 MB warning, 256 KiB critical), as iOS does.
     */
    private fun relieveRustCore(level: Int) {
        val backend = RustCore.backend ?: return
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        runCatching {
            backend.setByteBudget(if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) 262_144L else 1_000_000L)
            addLog("Memory pressure $level: Rust core trimmed ${backend.trim()} bytes")
        }
    }

    private companion object {
        /** The last room list a Rust olcRTC client was handed (RoomMemoryRecord), in filesDir. */
        private const val RUST_ROOM_MEMORY_FILE = "olcrtc-rooms.json"
        /** RoomKeeper.swift's URLSession: 20 s without a byte, 30 s for the whole request. */
        private const val RUST_ROOMS_CONNECT_TIMEOUT_MS = 20_000L
        private const val RUST_ROOMS_REQUEST_TIMEOUT_MS = 30_000L

        /** The core's geodata directory (refilterDir), in filesDir. */
        private const val RUST_GEODATA_DIR = "xray-geodata"

        /** Why proxy mode does not run the blocked-only mode on the Rust core yet. */
        const val PROXY_BLOCKED_ONLY_REFUSAL =
            "the Rust core runs the blocked-only mode only in VPN mode for now (proxy mode is not handed Re:filter's list): " +
                "use VPN mode, another mode or the Go cores"
    }
}

/**
 * A Rust core that can be told, before it reads its config, the one directory it reads geodata
 * files from (xray-ffi's `xray_core_set_geodata_search_dir_exclusive`): where the session puts
 * Re:filter's list for the blocked-only mode. Asked of [RustCore.backend] by a cast, as the
 * other hooks are ([RustProbeBackend]); with an engine that is not one, the mode is refused.
 */
interface RustGeodataBackend {
    /** [RustCoreBackend.startCore], with the core reading geodata files from [geodataDir] alone. */
    fun startCoreWithGeodata(
        configJson: String,
        geodataDir: String,
        protect: (Int) -> Boolean,
        log: (level: Int, line: String) -> Unit
    )
}
