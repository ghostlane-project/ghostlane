package org.olcbox.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.vpn.desktop.engine.host.HostProbe

/**
 * The room check and the room ping of the desktop: ghostlane-engine's one-shots
 * ([HostProbe]), each joining the room for real.
 */
internal object OlcRtcConnectionChecker {

    /**
     * The probe the checks go through; null is a [HostProbe] of the bundled
     * engine. A test hands in one that runs a stand-in program.
     */
    @Volatile
    internal var rooms: HostProbe? = null

    suspend fun check(
        locationConfig: LocationConfig,
        deviceId: String = DEFAULT_DEVICE_ID,
        dtlsProfile: String = OlcrtcDtls.OFF
    ): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null

            val engine = rooms ?: HostProbe().also { rooms = it }
            viaEngine(CONNECTION_CHECK_ATTEMPTS) {
                engine.check(HostProbe.Room.of(config, deviceId, dtlsProfile), OLC_READY_TIMEOUT_MS)
                    .let { if (it.millis != null) it.copy(millis = it.millis.coerceAtLeast(1L)) else it }
            }
        }
    }

    suspend fun ping(
        locationConfig: LocationConfig,
        deviceId: String = DEFAULT_DEVICE_ID,
        dtlsProfile: String = OlcrtcDtls.OFF
    ): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null

            val engine = rooms ?: HostProbe().also { rooms = it }
            viaEngine(HTTP_PING_ATTEMPTS) {
                engine.ping(
                    HostProbe.Room.of(config, deviceId, dtlsProfile),
                    OLC_READY_TIMEOUT_MS + HTTP_PING_TIMEOUT_MS,
                    HTTP_PING_URL
                )
            }
        }
    }

    /**
     * [attempts] asks of the engine, each joining the room for real: the first
     * yes, or null when none was one. A no is logged with the engine's reason.
     */
    private fun viaEngine(attempts: Int, ask: () -> HostProbe.Result): Long? {
        repeat(attempts) {
            val result = ask()
            result.millis?.let { return it }
            println("OlcRtcConnectionChecker: ghostlane-engine: ${result.reason}")
        }
        return null
    }

    private const val CONNECTION_CHECK_ATTEMPTS = 2
    private const val HTTP_PING_ATTEMPTS = 1

    /** A probe joins the room for real, so it waits as long as a join can take (WB Stream: 25 s). */
    private const val OLC_READY_TIMEOUT_MS = 30_000L

    private const val HTTP_PING_TIMEOUT_MS = 8_000L
    private const val HTTP_PING_URL = "https://www.google.com/generate_204"
    private const val DEFAULT_DEVICE_ID = "olcbox"
}
