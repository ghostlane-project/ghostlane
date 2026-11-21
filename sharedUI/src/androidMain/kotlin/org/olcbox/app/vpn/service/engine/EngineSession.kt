package org.olcbox.app.vpn.service.engine

import android.content.Context
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.SessionPort
import org.olcbox.app.vpn.VpnStatus

/**
 * The seam between the VpnService and whatever carries the transport: the
 * service owns the tun, the bridge in front of the engine's SOCKS port, the
 * network callbacks and the status; a session owns the engine. The service
 * asks a session and never names an engine's own classes.
 *
 * Two sessions exist while the switch does (RustCore.resolve): [GoEngineSession]
 * (the gomobile olcRTC runtime and the sing-box and Xray processes), the default,
 * and [RustEngineSession] (ghostlane-core). [EngineSwitch] is the one the
 * service holds.
 */
internal interface EngineSession {
    /** Hands the engine the service's socket protector and log sink; safe to repeat. */
    fun installCallbacks()

    /**
     * Starts the transport for [location] on [upstream] and waits until its SOCKS
     * port answers. Leaves [EngineHost.activeCorePort] and [EngineHost.activeCoreLogin]
     * saying where the bridge must point. False when the start failed or was
     * superseded; with [setErrorOnFailure] the failure becomes the status.
     */
    suspend fun start(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean

    /** Stops whatever of the engine runs; a no-op when nothing does. Never throws. */
    fun stop()

    /** Whether the transport that should be carrying traffic is alive. */
    fun isTransportRunning(): Boolean

    /** The running core's own account of itself, for the log. */
    fun diagnostics(): String

    /**
     * The upstream network changed under a connected session. True when the
     * engine deals with it by itself and the service must not restart the line.
     */
    fun networkChanged(): Boolean

    /** The service was told its underlying network: the engine re-points what follows it. */
    fun underlyingNetworkChanged(network: Network)

    /** The system's memory signal (ComponentCallbacks2 levels). */
    fun trimMemory(level: Int)

    /** The tunnel was verified and the status became Connected. */
    fun connected()

    /** The lane hev carries UDP on towards the engine's SOCKS port: "udp" or "tcp". */
    fun hevUdpLane(): String

    /** Starts a candidate line's own core for a probe, beside the session's. */
    val probeStarter: TransportProbe.Starter
}

/**
 * What a session needs of the service. The service implements it over its own
 * state, so a session's code reads as it did when it was the service's.
 */
internal interface EngineHost {
    val context: Context
    val scope: CoroutineScope
    val generation: Long
    val connectionMode: AndroidConnectionMode
    val socksListenHost: String
    val socksListenPort: Int
    val socksUsername: String
    val socksPassword: String
    val verboseDebugLogs: Boolean
    val routingSettings: RoutingSettings

    /** The SOCKS port the bridge targets when a core is active (null: the olcRTC port). */
    var activeCorePort: Int?

    /** The login that port demands (null: none). */
    var activeCoreLogin: SocksLogin?
    val sessionCorePort: SessionPort

    /** How long a core's SOCKS port may take to answer. */
    val coreReadyTimeoutMs: Long
    val socksReleaseQuickTimeoutMs: Long
    val networkStabilityGraceMs: Long

    /** Bumps whenever the stored locations change. */
    val storedLocationsChanges: Flow<Long>

    fun protect(fd: Int): Boolean
    fun addLog(msg: String)
    fun handleRtcLine(line: String)
    fun resetRtcHealthState()
    fun markRtcConnected()
    fun setStatus(status: VpnStatus)
    fun updateNotification(status: String)
    suspend fun routingFor(upstream: Network?): Routing
    fun upstreamDnsList(network: Network?): String
    fun upstreamDnsAddresses(network: Network?): List<String>
    fun bindProcessToNetwork(network: Network?, successLog: String?)
    fun unbindProcessFromNetwork()
    fun getNetName(network: Network): String
    fun shouldKeepProcessBound(network: Network): Boolean
    fun isLocalSocksPortOpen(port: Int): Boolean
    suspend fun waitForSocksPortOpen(port: Int, timeoutMs: Long): Boolean
    suspend fun waitForSocksPortReleased(port: Int, timeoutMs: Long)
    fun freeLoopbackPort(): Int

    /** Stops the session's transport and waits for the SOCKS port to be free. */
    suspend fun stopTransportAndWait()
    suspend fun deviceHwid(): String?
    suspend fun storedBundle(): LocationBundleV4
    fun requestTransportRecovery(reason: String, fullRestart: Boolean)
}

/**
 * The session the service holds while there are two engines: each call goes to
 * the Go session, or to the Rust one when [rustCore] is set (RustCore.resolve,
 * read at every start command). Off by default.
 */
internal class EngineSwitch(host: EngineHost) : EngineSession {
    private val go by lazy { GoEngineSession(host) }
    private val rust by lazy { RustEngineSession(host, go) }

    /** The Rust core engine runs the transport instead of the Go cores. */
    @Volatile
    var rustCore = false

    private val current: EngineSession get() = if (rustCore) rust else go

    override fun installCallbacks() = current.installCallbacks()

    override suspend fun start(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean = current.start(location, upstream, requestedGeneration, setErrorOnFailure)

    override fun stop() {
        current.stop()
        // A START that turned the switch off still ends the keeper and its fetches (Rust fields only).
        rust.stopRustRoomKeeper()
    }

    override fun isTransportRunning(): Boolean = current.isTransportRunning()

    override fun diagnostics(): String = current.diagnostics()

    override fun networkChanged(): Boolean = current.networkChanged()

    override fun underlyingNetworkChanged(network: Network) = current.underlyingNetworkChanged(network)

    override fun trimMemory(level: Int) = current.trimMemory(level)

    /** The Rust session's keeper whichever engine the last start chose, as the stop above. */
    override fun connected() = rust.connected()

    override fun hevUdpLane(): String = current.hevUdpLane()

    override val probeStarter: TransportProbe.Starter get() = current.probeStarter
}
