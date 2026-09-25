package org.olcbox.app.net

import org.olcbox.app.data.model.LocationConfig

/** Starts/stops sing-box (SOCKS inbound). Implemented per-platform. */
interface SingBoxController {
    suspend fun start(configJson: String)
    suspend fun stop()
}

/** Starts/stops Xray-core (SOCKS inbound) — used for xhttp locations. Platform-implemented. */
interface XrayController {
    suspend fun start(configJson: String)
    suspend fun stop()
}

/** Starts/stops the olcrtc engine (SOCKS on [olcrtcSocksPort]). Platform-implemented. */
interface OlcrtcController {
    val olcrtcSocksPort: Int
    suspend fun start(location: LocationConfig)
    suspend fun stop()
}

/**
 * Chooses the engine(s) for a selected location and starts them so the existing
 * tun→SOCKS bridge can point at whichever core's SOCKS inbound is active (only
 * one runs at a time):
 *  - olcrtc location  → olcrtc engine child + sing-box with a `socks` outbound to it.
 *  - vless (reality)  → sing-box native outbound.
 *  - hysteria2        → sing-box native outbound.
 *  - vless (xhttp)    → Xray-core (sing-box has no xhttp transport).
 */
class EngineDispatcher(
    private val singBox: SingBoxController,
    private val xray: XrayController,
    private val olcrtc: OlcrtcController,
) {
    suspend fun start(location: LocationConfig) {
        when (location.kind) {
            LocationKind.Olcrtc -> {
                olcrtc.start(location)
                singBox.start(SingBoxConfig.buildOlcrtcSocks(olcrtc.olcrtcSocksPort))
            }
            LocationKind.Hysteria2, LocationKind.Trojan, LocationKind.Shadowsocks, LocationKind.Vmess -> {
                singBox.start(SingBoxConfig.build(requireVlessOrHy2Spec(location)))
            }
            LocationKind.Vless -> {
                val spec = requireVlessOrHy2Spec(location) as OutboundSpec.Vless
                if (spec.transport is TransportSpec.Xhttp) {
                    xray.start(XrayConfig.buildXhttp(spec)) // Xray owns xhttp
                } else {
                    singBox.start(SingBoxConfig.build(spec)) // reality/tcp via sing-box
                }
            }
        }
    }

    suspend fun stop() {
        singBox.stop()
        xray.stop()
        olcrtc.stop()
    }

    private fun requireVlessOrHy2Spec(location: LocationConfig): OutboundSpec {
        val raw = location.rawLink
            ?: throw IllegalArgumentException("${location.kind} location has no rawLink")
        return LinkParser.parse(raw)
            ?: throw IllegalArgumentException("unparseable rawLink for ${location.kind}: $raw")
    }
}
