package org.olcbox.app.vpn.service.engine

import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.vpn.AndroidSocksProxySettings

/**
 * Proxy mode (a SOCKS port for the other apps on the phone) on the Rust core.
 *
 * A core line listens as the Go cores did: on a loopback address, at the port
 * the settings name (the endpoint the app tells other apps to use), demanding
 * the user's login when one is set and open to the phone's apps when not. The
 * inbound is [CoreInbound.Proxy]; the tun's own ([CoreInbound.Tunnel]) is the
 * session's business. An olcRTC room needs only its own port and login, so it
 * runs as it did. The Rust session asks here before it starts anything.
 */
internal object RustProxyMode {
    /**
     * Proxy mode on an address other devices reach: the Go cores bound loopback
     * whatever the setting said, so a line that only looked listening on the
     * network would be a quiet change; the core's LAN sharing is the way to share.
     */
    const val NOT_LOOPBACK_REFUSAL =
        "the Rust core engine does not run proxy mode on an address other devices reach; " +
            "listen on 127.0.0.1 (the other apps on this phone use it) or share the tunnel with the local network"

    /**
     * Why this start cannot run, or null when it can. [tun]: tun mode; [isOlc]: an olcRTC
     * room; [listenHost]: the proxy host the settings name.
     */
    fun refusal(tun: Boolean, isOlc: Boolean, listenHost: String): String? =
        if (tun || isOlc || listenAddress(listenHost) != null) null else NOT_LOOPBACK_REFUSAL

    /**
     * The loopback address the core binds for [listenHost], or null for any other. The wildcard
     * addresses mean "this phone" to the app's own clients ([AndroidSocksProxySettings.connectHost]),
     * and bind loopback as they did on the Go cores.
     */
    fun listenAddress(listenHost: String): String? =
        CoreInbound.loopbackAddress(AndroidSocksProxySettings.connectHost(listenHost))

    /** core.json for [spec] in proxy mode: the config the tun's session writes, with proxy mode's inbound in the tun's place. */
    fun config(
        spec: OutboundSpec,
        port: Int,
        login: SocksLogin?,
        listenHost: String,
        routing: Routing,
        geodata: XrayGeodata.Lists?,
        verboseLogs: Boolean,
    ): String {
        val listen = requireNotNull(listenAddress(listenHost)) { NOT_LOOPBACK_REFUSAL }
        return CoreInbound.build(
            spec,
            CoreInbound.Plan(CoreInbound.Proxy(port, login, listen)),
            routing = routing,
            geodata = geodata,
            verboseLogs = verboseLogs
        )
    }
}
