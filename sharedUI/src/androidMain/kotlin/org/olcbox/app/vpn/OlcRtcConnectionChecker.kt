package org.olcbox.app.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.vpn.service.RustCore

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
    /** The answer when no Rust backend is in the build: nothing dials the room. */
    const val ENGINE_GO = "go"
    const val HTTP_PING_URL = "https://www.google.com/generate_204"

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
        ?.let { checkRoom(it, context) } ?: incomplete()

    suspend fun pingDetailed(
        locationConfig: LocationConfig,
        deviceId: String,
        dtlsProfile: String = OlcrtcDtls.OFF,
        context: Context? = null
    ): RoomProbeResult = request(locationConfig, deviceId, dtlsProfile, HTTP_PING_TIMEOUT_MS)
        ?.let { pingRoom(it, context) } ?: incomplete()

    /** A check of exactly [request], attempted [CONNECTION_CHECK_ATTEMPTS] times. */
    suspend fun checkRoom(request: RoomRequest, context: Context? = null): RoomProbeResult =
        probe(request, context, CONNECTION_CHECK_ATTEMPTS, "check") { backend, r ->
            backend?.check(r) ?: noBackend()
        }

    /** A probe of exactly [request], attempted [HTTP_PING_ATTEMPTS] time. */
    suspend fun pingRoom(request: RoomRequest, context: Context? = null): RoomProbeResult =
        probe(request, context, HTTP_PING_ATTEMPTS, "ping") { backend, r ->
            backend?.ping(r) ?: noBackend()
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

    private fun incomplete() =
        RoomProbeResult(null, "the location is not complete", engineName(rustBackend()))

    /** The Rust client when the build carries it; the app has no other engine. */
    private fun rustBackend(): RoomCheckBackend? {
        if (RustCore.backend == null) return null
        return runCatching {
            Class.forName(ROOM_CHECK_CLASS).getField("INSTANCE").get(null) as RoomCheckBackend
        }.getOrNull()
    }

    private fun engineName(backend: RoomCheckBackend?) = if (backend != null) ENGINE_RUST else ENGINE_GO

    private suspend fun probe(
        request: RoomRequest,
        @Suppress("UNUSED_PARAMETER") context: Context?,
        attempts: Int,
        what: String,
        run: (RoomCheckBackend?, RoomRequest) -> Long
    ): RoomProbeResult = withContext(Dispatchers.IO) {
        val backend = rustBackend()
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

    private fun noBackend(): Long = error("the Rust core is not packaged in this build")

    private const val TAG = "OlcRtcConnectionChecker"
    private const val ROOM_CHECK_CLASS = "org.olcbox.app.rustcore.RustRoomCheck"

    private const val CONNECTION_CHECK_ATTEMPTS = 2
    private const val CONNECTION_CHECK_TIMEOUT_MS = 8_000L

    private const val HTTP_PING_ATTEMPTS = 1
    private const val HTTP_PING_TIMEOUT_MS = 8_000L
}
