package org.olcbox.app.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mobile.Mobile
import mobile.Runtime as OlcrtcRuntime
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcDtls

/**
 * What a room check and a probe need of the Rust core's olcRTC client. The
 * implementation is `org.olcbox.app.rustcore.RustRoomCheck`, found by name as
 * [RustCore.backend] is: a build without the core has none.
 *
 * Each call joins one room on a runtime of its own and leaves it before it
 * returns, so nothing here outlives a call and a probe never touches the
 * service's engine. A failure is thrown with the core's own text.
 */
interface RoomCheckBackend {
    /** The milliseconds the join took, to the probe's SOCKS port. */
    fun check(request: RoomRequest): Long

    /** The best of three HTTP requests through the room, in milliseconds. */
    fun ping(request: RoomRequest): Long
}

/** One room check or probe: the carrier, the room and the handshake the caller asks for. */
data class RoomRequest(
    val provider: String,
    val transport: String,
    val room: String,
    val deviceId: String,
    val keyHex: String,
    val timeoutMs: Long,
    val vp8Fps: Int,
    val vp8Batch: Int,
    val dtlsProfile: String,
    val pingUrl: String = OlcRtcConnectionChecker.HTTP_PING_URL,
)

/** How a call ended: the milliseconds, or null with the reason of the last attempt, and which engine ran it. */
data class RoomProbeResult(val millis: Long?, val reason: String?, val engine: String)

object OlcRtcConnectionChecker {
    const val ENGINE_RUST = "rust"
    const val ENGINE_GO = "go"
    const val HTTP_PING_URL = "https://www.google.com/generate_204"

    // The Go checker's own engine, so a probe never touches the service's.
    private val runtime: OlcrtcRuntime by lazy { Mobile.new_() }

    suspend fun check(
        locationConfig: LocationConfig,
        deviceId: String,
        dtlsProfile: String = OlcrtcDtls.OFF,
        context: Context? = null
    ): Long? = checkDetailed(locationConfig, deviceId, dtlsProfile, context).millis

    suspend fun ping(
        locationConfig: LocationConfig,
        deviceId: String,
        dtlsProfile: String = OlcrtcDtls.OFF,
        context: Context? = null
    ): Long? = pingDetailed(locationConfig, deviceId, dtlsProfile, context).millis

    suspend fun checkDetailed(
        locationConfig: LocationConfig,
        deviceId: String,
        dtlsProfile: String = OlcrtcDtls.OFF,
        context: Context? = null
    ): RoomProbeResult = request(locationConfig, deviceId, dtlsProfile, CONNECTION_CHECK_TIMEOUT_MS)
        ?.let { checkRoom(it, context) } ?: incomplete(context)

    suspend fun pingDetailed(
        locationConfig: LocationConfig,
        deviceId: String,
        dtlsProfile: String = OlcrtcDtls.OFF,
        context: Context? = null
    ): RoomProbeResult = request(locationConfig, deviceId, dtlsProfile, HTTP_PING_TIMEOUT_MS)
        ?.let { pingRoom(it, context) } ?: incomplete(context)

    /** A check of exactly [request], attempted [CONNECTION_CHECK_ATTEMPTS] times. */
    suspend fun checkRoom(request: RoomRequest, context: Context? = null): RoomProbeResult =
        probe(request, context, CONNECTION_CHECK_ATTEMPTS, "check") { backend, r ->
            backend?.check(r) ?: goCheck(r)
        }

    /** A probe of exactly [request], attempted [HTTP_PING_ATTEMPTS] time. */
    suspend fun pingRoom(request: RoomRequest, context: Context? = null): RoomProbeResult =
        probe(request, context, HTTP_PING_ATTEMPTS, "ping") { backend, r ->
            backend?.ping(r) ?: goPing(r)
        }

    private fun request(
        locationConfig: LocationConfig,
        deviceId: String,
        dtlsProfile: String,
        timeoutMs: Long
    ): RoomRequest? {
        val config = locationConfig.normalized()
        if (!config.isComplete()) return null
        return RoomRequest(
            provider = config.bypassProvider,
            transport = config.transport,
            room = config.id,
            deviceId = deviceId,
            keyHex = config.key,
            timeoutMs = timeoutMs,
            vp8Fps = config.vp8Fps,
            vp8Batch = config.vp8Batch,
            dtlsProfile = dtlsProfile,
        )
    }

    private fun incomplete(context: Context?) =
        RoomProbeResult(null, "the location is not complete", engineName(rustBackend(context)))

    /**
     * The Rust client when the build carries it and the switch is on (the
     * service's own rule, [RustCore.resolve], with no intent); else null, the
     * Go runtime. Without a [context] only the build's default is known.
     */
    /** Not on the Rust core yet: every call is the Go runtime's. */
    @Suppress("UNUSED_PARAMETER")
    private fun rustBackend(context: Context?): RoomCheckBackend? = null

    private fun engineName(backend: RoomCheckBackend?) = if (backend != null) ENGINE_RUST else ENGINE_GO

    private suspend fun probe(
        request: RoomRequest,
        context: Context?,
        attempts: Int,
        what: String,
        run: (RoomCheckBackend?, RoomRequest) -> Long
    ): RoomProbeResult = withContext(Dispatchers.IO) {
        val backend = rustBackend(context)
        val engine = engineName(backend)
        var reason: String? = null
        repeat(attempts) {
            try {
                val millis = run(backend, request)
                if (millis >= 0L && (what == "ping" || millis > 0L)) return@withContext RoomProbeResult(millis, null, engine)
                reason = "no time came back"
            } catch (e: Throwable) {
                reason = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "room $what failed: $reason")
            }
        }
        RoomProbeResult(null, reason, engine)
    }

    private fun goCheck(r: RoomRequest): Long {
        // A probe takes the runtime's profile with the rest of its defaults.
        runtime.setDTLSProfile(r.dtlsProfile)
        return runtime.check(
            r.provider, r.transport, r.room, r.deviceId, r.keyHex,
            allocateLocalPort().toLong(), r.timeoutMs, r.vp8Fps.toLong(), r.vp8Batch.toLong()
        )
    }

    private fun goPing(r: RoomRequest): Long {
        runtime.setDTLSProfile(r.dtlsProfile)
        return runtime.ping(
            r.provider, r.transport, r.room, r.deviceId, r.keyHex,
            allocateLocalPort().toLong(), r.timeoutMs, r.pingUrl, r.vp8Fps.toLong(), r.vp8Batch.toLong()
        )
    }

    private fun allocateLocalPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private const val TAG = "OlcRtcConnectionChecker"

    private const val CONNECTION_CHECK_ATTEMPTS = 2
    private const val CONNECTION_CHECK_TIMEOUT_MS = 8_000L

    private const val HTTP_PING_ATTEMPTS = 1
    private const val HTTP_PING_TIMEOUT_MS = 8_000L
}
