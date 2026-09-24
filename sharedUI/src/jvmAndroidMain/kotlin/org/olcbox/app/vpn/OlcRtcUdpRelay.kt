package org.olcbox.app.vpn

import org.olcbox.app.data.model.LocationConfig

/**
 * Whether olcRTC's SOCKS5 UDP ASSOCIATE relay is on for a room: on wherever
 * the room's link has a lane for datagrams, and under a bypass; off on a room
 * with no lane otherwise.
 *
 * hev asks the engine for an association for every UDP socket off the tun
 * (full-cone). Only livekit (WB Stream) and SaluteJazz have a lane behind
 * datachannel; the jitsi engine, whose only transport it is, does not.
 * Off, the engine answers each association at once with host unreachable, so
 * UDP fails fast there. On, the engine (7b78fd4a753c, olcrtc#49) takes the
 * association on a link without the lane for what it carries off the lane —
 * a direct flow, DNS for a name the rules cover, other DNS over the reliable
 * stream — and drops what is for the lane at once; the association ends with
 * its control connection. That only pays when direct rules give it something
 * to carry: under a bypass a Russian call on a Jitsi room goes direct, as it
 * did behind the sing-box front.
 * Without rules it stays off: a refusal fails fast where a drop only times out
 * (and before olcrtc#49 such an association waited for a lane that never
 * opened, holding its SOCKS slot). vp8channel carries datagrams itself,
 * whatever the carrier; seichannel has no datagram methods, so the engine
 * refuses there on its own.
 */
object OlcRtcUdpRelay {
    private val DATACHANNEL_LANE_PROVIDERS = setOf(
        LocationConfig.PROVIDER_WB_STREAM,
        LocationConfig.PROVIDER_SALUTEJAZZ
    )

    fun enabled(provider: String, transport: String, directRules: Boolean = false): Boolean {
        if (directRules) return true
        val normalizedProvider = LocationConfig.normalizeProvider(provider)
        val normalizedTransport = LocationConfig.normalizeTransport(transport, normalizedProvider)
        return normalizedTransport != LocationConfig.TRANSPORT_DATACHANNEL ||
            normalizedProvider in DATACHANNEL_LANE_PROVIDERS
    }
}
