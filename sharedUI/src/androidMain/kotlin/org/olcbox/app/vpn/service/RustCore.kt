package org.olcbox.app.vpn.service

import org.olcbox.app.net.SocksLogin

/**
 * The seam between the VpnService and ghostlane-core (the Rust library: xray-rust's
 * core through its C ABI, the olcRTC client through UniFFI).
 *
 * The service lives in this library, the library's native code and its UniFFI
 * bindings in :androidApp (build with `-PghostlaneCoreDir=<build-ghostlane-core-android.sh output>`),
 * so the service sees only this interface. The implementation is
 * `org.olcbox.app.rustcore.RustCoreEngine`, found by name:
 * a build without the core has no such class and [backend] is null, which the
 * service reports as a start that failed with a reason.
 *
 * The arrangement is iOS's (iosApp/PacketTunnel/CoreEngine.swift, OlcrtcEngine.swift):
 * the engine speaks the transport on a loopback SOCKS port that demands a login and
 * hev-socks5-tunnel owns the tun in front of it. Every socket the engine opens is
 * handed to [protect] first (VpnService.protect, as iOS pins to the physical interface).
 */
interface RustCoreBackend {
    /** Loads the library; throws with the reason when it cannot. */
    fun load()

    /** Starts the xray core on a complete core.json; stops a previous one first. Throws with the core's own message. */
    fun startCore(configJson: String, protect: (Int) -> Boolean, log: (level: Int, line: String) -> Unit)

    /** Stops and frees the xray core, if one runs. Never throws. */
    fun stopCore()
    fun isCoreRunning(): Boolean

    /**
     * The network moved: the core closes what it holds on the old path. Returns the connections it
     * closed (0 when no core runs); throws with the core's message when the reset fails.
     */
    fun resetNetwork(): Long

    /**
     * Starts the olcRTC client and waits until it is ready. Throws with the engine's message.
     * [onSessionOpened] is told the room of every session the engine opens (the first, every
     * hop, every reconnect), on the engine's thread: it must not block (OlcrtcRoomKeeper queues).
     */
    fun startOlc(
        params: RustOlcParams,
        protect: (Long) -> Boolean,
        log: (String) -> Unit,
        onSessionOpened: (room: String) -> Unit
    )

    /** Stops the olcRTC client, if one runs. Never throws. */
    fun stopOlc(timeoutMs: Long)

    /**
     * The room list of a running (or about to run) olcRTC client: the engine re-reads it at every
     * hop, so a list refreshed while the tunnel is up is applied here (RoomKeeper.swift's `apply`).
     */
    fun setRooms(primary: String, extras: List<String>)
    fun isOlcRunning(): Boolean

    /** Drops the core's pools and asks the allocator for its free pages; the bytes given back. */
    fun trim(): Long

    /** Moves the core's byte budget (0 gives the formula back), as iOS's MemoryRelief does under pressure. */
    fun setByteBudget(bytes: Long)

    /** The olcRTC client's memory figures as one JSON line. */
    fun memoryStats(): String
}

/** What the olcRTC client is given, the fields OlcrtcEngine.swift's `launch` sets. */
data class RustOlcParams(
    val provider: String,
    val transport: String,
    val room: String,
    /** The other rooms of the location's failover group, in order ([LocationConfig.failoverRoomIds]). */
    val failoverRooms: List<String>,
    val keyHex: String,
    val deviceId: String,
    val socksHost: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val dns: String,
    val directRules: String,
    val vp8Fps: Long,
    val vp8Batch: Long,
    val udp: Boolean,
    val dtlsProfile: String,
) {
    companion object {
        /** OlcrtcEngine.swift's readyTimeoutMillis. */
        const val READY_TIMEOUT_MS = 35_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The Rust core is the app's only engine. What is here is the way to it and a
 * hook of the instrumented suite.
 */
object RustCore {
    private const val ENGINE_CLASS = "org.olcbox.app.rustcore.RustCoreEngine"

    /** Test hook: a complete core.json for the given SOCKS port and login, in place of the profile's. Never set outside tests. */
    @Volatile
    var testConfig: ((port: Int, login: SocksLogin?) -> String)? = null

    /** The engine, or null in a build that does not carry ghostlane-core. */
    val backend: RustCoreBackend? by lazy {
        runCatching {
            Class.forName(ENGINE_CLASS).getField("INSTANCE").get(null) as RustCoreBackend
        }.getOrNull()
    }
}
