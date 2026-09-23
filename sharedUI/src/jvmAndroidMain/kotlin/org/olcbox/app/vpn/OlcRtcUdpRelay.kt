package org.olcbox.app.vpn

import org.olcbox.app.data.model.LocationConfig

/**
 * Whether olcRTC's SOCKS5 UDP ASSOCIATE relay is on for a room: on wherever
 * the room's link has a lane for datagrams, off where it has none.
 *
 * hev asks the engine for an association for every UDP flow off the tun. The
 * engine (3cf5f20df43d, internal/client/udp.go) takes one whenever its
 * transport has datagram methods, and datachannel always has them; but the
 * lane behind them is the engine's, and only livekit (WB Stream) and
 * SaluteJazz have one — the jitsi engine does not. So on a Jitsi room the
 * association is accepted, and the first packet that is not DNS waits for a
 * lane that never opens: until the client itself stops, not until hev lets
 * the flow go. The association keeps its SOCKS slot, and after 512 of them the
 * engine accepts no connection at all, TCP included, until a restart.
 *
 * Off, the engine answers each association at once with host unreachable:
 * UDP fails fast there, as it did before hev asked for associations. vp8channel
 * carries datagrams itself, whatever the carrier; seichannel has no datagram
 * methods, so the engine refuses there on its own.
 */
object OlcRtcUdpRelay {
    private val DATACHANNEL_LANE_PROVIDERS = setOf(
        LocationConfig.PROVIDER_WB_STREAM,
        LocationConfig.PROVIDER_SALUTEJAZZ
    )

    fun enabled(provider: String, transport: String): Boolean {
        val normalizedProvider = LocationConfig.normalizeProvider(provider)
        val normalizedTransport = LocationConfig.normalizeTransport(transport, normalizedProvider)
        return normalizedTransport != LocationConfig.TRANSPORT_DATACHANNEL ||
            normalizedProvider in DATACHANNEL_LANE_PROVIDERS
    }
}
