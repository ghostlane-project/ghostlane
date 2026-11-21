package org.olcbox.app.vpn.service.engine

import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mobile.LogWriter
import mobile.Mobile
import mobile.Runtime as OlcrtcRuntime
import mobile.SocketProtector
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.log.LogScrubber
import org.olcbox.app.net.AndroidCoreProcess
import org.olcbox.app.net.AndroidSingBoxController
import org.olcbox.app.net.AndroidXrayController
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcDirectRules
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.OlcRtcUdpRelay
import org.olcbox.app.vpn.VpnStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * The Go engines behind the seam, as the service ran them: the gomobile olcRTC
 * runtime in this process for a carrier room, and sing-box and Xray as child
 * processes for a core line (vless, hysteria2, xhttp). The code is the
 * service's own, moved; [host] is the service.
 */
internal class GoEngineSession(private val host: EngineHost) : EngineSession, EngineHost by host {

    // Exec'd subprocesses. The app's UID already bypasses the tun
    // (addDisallowedApp(self) in the service's applySplitTunneling), so a
    // core's own sockets do not loop back through the tunnel.
    private val singBoxCore by lazy { AndroidSingBoxController(context) }
    private val xrayCore by lazy { AndroidXrayController(context) }

    // One engine per service. Created by the Go constructor: the generated
    // no-argument Runtime() allocates an empty struct that cannot start.
    private val olcrtc: OlcrtcRuntime by lazy { Mobile.new_() }

    @Volatile
    private var lastMobileProvider: String? = null
    @Volatile
    private var lastJitsiStopCompletedAtMs = 0L
    private val failoverSerial = AtomicInteger()

    override fun installCallbacks() {
        olcrtc.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode == AndroidConnectionMode.Proxy) return true
                return host.protect(fd.toInt())
            }
        })
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                val line = msg.trimEnd()
                addLog("rtc: $line")
                // Its own tag, so it needs its own scrub: this one does not go through
                // addLog, and logcat is captured by bug reports.
                Log.v("olcrtc", LogScrubber.default.scrub(line))
                handleRtcLine(line)
            }
        })
    }

    /**
     * Branches on the location's kind: olcRTC runs the engine in this process
     * ([startMobile]), which routes by itself under a bypass; vless, hysteria2
     * and xhttp start a sing-box or Xray process ([startCore]). Both leave the
     * bridge pointed at the right SOCKS port (via [activeCorePort]).
     */
    override suspend fun start(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val routing = routingFor(upstream)
        return if (location.kind == LocationKind.Olcrtc) {
            activeCorePort = null
            activeCoreLogin = null
            startMobile(location, upstream, requestedGeneration, setErrorOnFailure, routing)
        } else {
            // A core is a process of its own and needs no binding. One left from
            // an olcRTC room on mobile data would keep this process's own
            // requests on that network after the session has moved off it.
            unbindProcessFromNetwork()
            startCore(location, setErrorOnFailure, routing, upstream)
        }
    }

    /**
     * Start a sing-box (reality/hy2) or Xray (xhttp) core.
     *
     * In proxy mode the core listens on [socksListenPort], because that is the
     * endpoint the app tells other apps to use — putting the core anywhere else
     * leaves that promised port dead — and, as before, without a login. In tun
     * mode nothing outside talks to it, so it takes a port the kernel hands out
     * for this tun session and demands the app's own login: tun2socks is started
     * against [activeCorePort] with [activeCoreLogin], and [ensureBridge] restarts
     * it if either ever differs. It used to be 10810, open, which any app on the
     * phone could use to leave through the tunnel or to read its exit address
     * (see [SocksLogin]).
     */
    private suspend fun startCore(
        location: LocationConfig,
        setErrorOnFailure: Boolean,
        routing: Routing,
        upstream: Network
    ): Boolean {
        val tun = connectionMode == AndroidConnectionMode.Tun
        val port = if (tun) sessionCorePort.acquire() else socksListenPort
        val login = if (tun) SocksLogin.of(socksUsername, socksPassword) else null
        return try {
            val raw = location.rawLink ?: error("core location has no link")
            val spec = LinkParser.parse(raw) ?: error("unparseable core link")
            stopCores()
            waitForSocksPortReleased(port, socksReleaseQuickTimeoutMs)
            val label: String
            // Which core to ask afterwards, decided here rather than re-derived
            // from the label — the failure path should not have to parse a string
            // we build for humans.
            val diagnose: () -> String
            // Which processes must be alive once the port answers. A port that
            // answers proves nothing about who answers.
            val alive: () -> Boolean
            val fronted = routing is Routing.Rules && connectionMode == AndroidConnectionMode.Tun
            if (spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp) {
                if (fronted) {
                    // Xray does not route; sing-box does, so it goes in front.
                    // Both ports are loopback and both demand the login: the one
                    // behind the front is as reachable from other apps as the
                    // front itself.
                    val xrayPort = freeLoopbackPort()
                    xrayCore.start(
                        XrayConfig.buildXhttp(
                            spec,
                            socksPort = xrayPort,
                            verboseLogs = verboseDebugLogs,
                            login = login
                        )
                    )
                    singBoxCore.start(
                        SingBoxConfig.buildSocksChain(
                            xrayPort,
                            socksPort = port,
                            username = login?.username.orEmpty(),
                            password = login?.password.orEmpty(),
                            routing = routing,
                            verboseLogs = verboseDebugLogs,
                            login = login
                        )
                    )
                    label = "sing-box front + Xray/xhttp"
                    diagnose = { singBoxCore.diagnostics() + "\n" + xrayCore.diagnostics() }
                    alive = { singBoxCore.isRunning() && xrayCore.isRunning() }
                } else {
                    if (routing is Routing.Rules) addLog("Routing: proxy mode keeps xhttp global")
                    xrayCore.start(
                        XrayConfig.buildXhttp(spec, socksPort = port, verboseLogs = verboseDebugLogs, login = login)
                    )
                    label = "Xray/xhttp"
                    diagnose = xrayCore::diagnostics
                    alive = xrayCore::isRunning
                }
            } else {
                singBoxCore.start(
                    SingBoxConfig.build(
                        spec,
                        socksPort = port,
                        routing = routing,
                        verboseLogs = verboseDebugLogs,
                        login = login,
                        // A server named by hostname: the core has no system
                        // resolver to ask on Android, so it is given the network's.
                        serverResolver = DirectDns.Servers(upstreamDnsAddresses(upstream))
                    )
                )
                label = "sing-box/${location.kind}"
                diagnose = singBoxCore::diagnostics
                alive = singBoxCore::isRunning
            }
            activeCorePort = port
            activeCoreLogin = login
            if (!waitForSocksPortOpen(port, coreReadyTimeoutMs) || !alive()) {
                // The core's own account of what went wrong, which otherwise sits
                // in a cache file only root can read. Without it this branch says
                // a port did not open and nothing about why, and that is all a
                // user has ever been able to report.
                addLog(diagnose())
                error("$label SOCKS not ready on $port")
            }
            coroutineContext.ensureActive()
            addLog("$label ready on $socksListenHost:$port")
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("core start canceled")
                stopCores()
            }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Core transport failed"
            addLog("core start failed: $msg")
            stopCores()
            // Whatever went wrong, the next start draws another port: one that
            // something else took in the meantime would fail every start alike.
            if (tun) sessionCorePort.release()
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error(msg))
                updateNotification("Connection failed")
            }
            false
        }
    }

    /** Stops the sing-box and Xray processes; a no-op when none runs. */
    fun stopCores() {
        singBoxCore.stopNow()
        xrayCore.stopNow()
        activeCorePort = null
        activeCoreLogin = null
    }

    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean,
        routing: Routing
    ): Boolean {
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        return try {
            installCallbacks()
            val targetSocksPort = socksListenPort
            val deviceId = deviceHwid().orEmpty()
            resetRtcHealthState()

            waitForSocksPortReleased(targetSocksPort, socksReleaseQuickTimeoutMs)
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            waitForJitsiRoomCleanup(config.bypassProvider)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            // The engine routes by itself (internal/route), as it does on iOS:
            // the region's lists as rules, or none under Global.
            val directRules = OlcrtcDirectRules.forRouting(routing)
            configureMobileTransport(config, upstream, directRules)
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=${config.id}"
            )
            lastMobileProvider = config.bypassProvider
            olcrtc.setProvider(config.bypassProvider)
            olcrtc.setRoom(config.id)
            olcrtc.setDeviceID(deviceId)
            olcrtc.setKey(config.key)
            olcrtc.setSocksPort(targetSocksPort.toLong())
            olcrtc.setSocksCredentials(socksUsername, socksPassword)
            olcrtc.start()
            olcrtc.waitReady(OLCRTC_READY_TIMEOUT_MS)
            if (requestedGeneration != generation) {
                addLog("olcRTC start superseded")
                return false
            }
            coroutineContext.ensureActive()
            addLog("olcRTC ready on $socksListenHost:$targetSocksPort")
            markRtcConnected()
            if (keepProcessBound) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("olcRTC start canceled")
                unbindProcessFromNetwork()
                stopTransportAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            if (staleRequest) {
                addLog("olcRTC start canceled: $message")
            } else {
                addLog("olcRTC start failed: $message")
            }
            unbindProcessFromNetwork()
            stopTransportAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification("Connection failed")
            }
            false
        } finally {
            if (!keepProcessBound || !olcrtc.isRunning()) {
                unbindProcessFromNetwork()
            }
        }
    }

    private suspend fun waitForJitsiRoomCleanup(provider: String) {
        if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI) return

        val waitMs = JITSI_RESTART_SETTLE_MS -
            (System.currentTimeMillis() - lastJitsiStopCompletedAtMs)
        if (waitMs <= 0L) return

        addLog("Waiting for previous Jitsi room cleanup")
        delay(waitMs)
    }

    private fun configureMobileTransport(location: LocationConfig, upstream: Network?, directRules: String) {
        val config = location.normalized()
        olcrtc.setTransport(config.transport)
        // The upstream network's own resolvers first, the public operator
        // behind them: some mobile networks answer only their own (olcbox#16).
        // Direct names under a bypass are resolved on the same list.
        olcrtc.setDNS(upstreamDnsList(upstream))
        olcrtc.setSocksListenHost(socksListenHost)
        olcrtc.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
        // Set on every start, the empty text included: the one Runtime keeps
        // its rules across Starts, so Global after a bypass would keep them.
        // Parsed here, so a list the engine refuses fails this start loudly.
        olcrtc.setDirectRules(directRules)
        if (directRules.isNotEmpty()) {
            addLog("Routing: olcRTC takes ${directRules.count { it == '\n' }} direct rules")
        }
        // Off on a Jitsi room, which has no datagram lane, unless a bypass gives
        // the relay direct flows to carry (OlcRtcUdpRelay). Set on every start,
        // for the same reason as the rules: a WB room after a Jitsi one would
        // otherwise run with no UDP.
        olcrtc.setUDP(
            OlcRtcUdpRelay.enabled(config.bypassProvider, config.transport, directRules = directRules.isNotEmpty())
        )
        // Set on every start too, "off" included: the Runtime keeps a profile
        // across Starts, so switching the setting off would otherwise not stick.
        val dtlsProfile = OlcrtcDtls.profile(routingSettings.olcrtcChromeDtls)
        olcrtc.setDTLSProfile(dtlsProfile)
        if (dtlsProfile != OlcrtcDtls.OFF) {
            addLog("olcRTC handshake: $dtlsProfile")
        }
    }

    override fun stop() {
        val provider = lastMobileProvider
        val wasRunning = olcrtc.isRunning()
        // Logged rather than swallowed. A stop that misses its deadline is the
        // difference between the next room joining and "olcRTC runtime is
        // already active", and for one whole debugging session this line threw
        // that away: the phone reported a start refused by a runtime nobody
        // could see had failed to stop.
        runCatching { olcrtc.stop(MOBILE_STOP_TIMEOUT_MS) }
            .onFailure { addLog("olcRTC stop did not finish in time: ${it.message ?: it}") }
        // Also tear down any active sing-box / Xray core (no-op if none running).
        runCatching { stopCores() }
        if (wasRunning && provider == LocationConfig.PROVIDER_JITSI) {
            lastJitsiStopCompletedAtMs = System.currentTimeMillis()
        }
    }

    /**
     * olcRTC runs inside the gomobile library; sing-box and Xray run as child
     * processes. Asking the olcRTC engine alone is false whenever a core is the
     * active transport, and the watchdog would then restart every core line
     * fifteen seconds after it came up, forever.
     */
    override fun isTransportRunning(): Boolean =
        if (activeCorePort != null) {
            singBoxCore.isRunning() || xrayCore.isRunning()
        } else {
            olcrtc.isRunning()
        }

    /**
     * The running core's account of itself, whichever core that is. An olcRTC
     * location runs neither, and saying so is the useful answer there rather than
     * an empty string that reads like a core with nothing to report.
     */
    override fun diagnostics(): String = when {
        xrayCore.isRunning() -> xrayCore.diagnostics()
        singBoxCore.isRunning() -> singBoxCore.diagnostics()
        else -> "no core process is running"
    }

    /** A Go line is restarted on a new network: nothing is dealt with here. */
    override fun networkChanged(): Boolean = false

    override fun underlyingNetworkChanged(network: Network) {
        // A running olcRTC re-points its lookups at the new network's resolvers
        // at once; the engine applies SetDNS live and refuses a malformed list.
        if (olcrtc.isRunning()) {
            runCatching { olcrtc.setDNS(upstreamDnsList(network)) }
                .onFailure { addLog("olcRTC resolvers not updated: ${it.message}") }
        }
    }

    override fun trimMemory(level: Int) = Unit

    override fun connected() = Unit

    override fun hevUdpLane(): String = "udp"

    /** A candidate's own core, alone, in a work dir of its own: the session's core is never touched. */
    override val probeStarter = TransportProbe.Starter { spec, config ->
        val label = "failover-${failoverSerial.incrementAndGet()}"
        val core = AndroidCoreProcess(
            context = context,
            soName = if (TransportProbe.usesXray(spec)) "libxraycore.so" else "libsingboxcore.so",
            label = label,
            argv = { bin, file -> listOf(bin, "run", "-c", file) },
        )
        core.start(config)
        object : TransportProbe.Core {
            override fun isRunning(): Boolean = core.isRunning()
            override suspend fun stop() {
                core.stop()
                File(context.cacheDir, "olcbox-$label").deleteRecursively()
            }
        }
    }

    private companion object {
        /**
         * How long olcRTC may take to come up: the room's auth, the call's own
         * ICE and DTLS, then the handshake. The engine now gives a WB Stream
         * (LiveKit) join 25 s by itself (it was the SDK's 5 s), and a cellular
         * ICE negotiation has taken 21 s, so the 25 s the cores get is too
         * short here. The iOS tunnel waits 35 s for the same start.
         */
        private const val OLCRTC_READY_TIMEOUT_MS = 35_000L
        private const val MOBILE_STOP_TIMEOUT_MS = 5_000L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
    }
}
