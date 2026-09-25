package org.olcbox.app.net

/**
 * The username and password a local SOCKS5 inbound demands.
 *
 * A core's SOCKS port on 127.0.0.1 is reachable from every app on the device,
 * not only from the tun in front of it. Open, it lets any of them route traffic
 * through the tunnel and read the exit address, past the split-tunnel choices
 * that were meant to keep those apps out of it, and several popular apps in
 * Russia look for exactly that (a SOCKS server on a well-known local port that
 * answers without a login). With a login only the holders of the pair get
 * anywhere; a probe sees a server that refuses it.
 */
data class SocksLogin(val username: String, val password: String) {
    companion object {
        /** The pair, or null when either half is blank: an inbound then stays open. */
        fun of(username: String, password: String): SocksLogin? =
            if (username.isBlank() || password.isBlank()) null else SocksLogin(username, password)
    }
}
