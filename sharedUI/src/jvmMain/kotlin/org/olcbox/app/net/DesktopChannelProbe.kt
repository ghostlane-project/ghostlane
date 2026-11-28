package org.olcbox.app.net

import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig

/**
 * Measures a real outbound through an isolated core, without installing routes:
 * the location's own line on a loopback port of its own, behind a login made up
 * for the probe, started by whatever [TransportProbe.Starter] the engine gives
 * ([org.olcbox.app.vpn.desktop.engine.ProbeEngine.fresh]) and stopped whatever
 * happens.
 */
internal object DesktopChannelProbe {
    // LocationViewModel owns the bounded semaphore. Each probe has an isolated
    // core and an ephemeral SOCKS port, so serialising again here turned a
    // 30-server subscription into minutes of waiting.
    suspend fun measure(location: LocationConfig, starter: TransportProbe.Starter): Long? {
        var measured: Long? = null
        withTimeoutOrNull(15_000L) {
            TransportProbe.passesWith(
                location,
                serverResolver = null,
                autoDetectInterface = false,
                bindInterface = null,
                starter = starter
            ) { proxy ->
                measured = ChannelLatency.measure(proxy)
                measured != null
            }
        }
        return measured
    }
}
