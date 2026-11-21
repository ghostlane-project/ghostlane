package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.SocksLogin

/** What listens for the tunnel's traffic. */
internal object CoreInbound {
    /** The tag the routing matches the tunnel's own traffic by. */
    const val TAG = "in"

    /** A loopback port is open to every app on the device: without a login any of them rides the line. */
    fun requireLogin(login: SocksLogin) {
        require(login.username.isNotBlank() && login.password.isNotBlank()) {
            "a loopback SOCKS port other apps can reach needs a login"
        }
    }
}

/**
 * The one inbound: SOCKS5 on loopback with the login hev is given, UDP on
 * (hev forwards it over TCP). The sniff names a connection for the rules
 * only (`routeOnly`): a fake address is turned back into its name before
 * any of this, and a real address stays the address it was dialled at,
 * as with sing-box's `sniff`.
 */
internal fun JsonArrayBuilder.addSocksInbound(port: Int, login: SocksLogin) {
    addJsonObject {
        put("tag", CoreInbound.TAG); put("protocol", "socks")
        put("listen", "127.0.0.1"); put("port", port)
        putJsonObject("settings") {
            put("auth", "password")
            putJsonArray("accounts") {
                addJsonObject {
                    put("user", login.username)
                    put("pass", login.password)
                }
            }
            put("udp", true)
        }
        putJsonObject("sniffing") {
            put("enabled", true)
            putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
            put("routeOnly", true)
        }
    }
}
