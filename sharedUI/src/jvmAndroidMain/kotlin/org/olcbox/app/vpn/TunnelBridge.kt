package org.olcbox.app.vpn

/**
 * What the Android tun's tun2socks was started against.
 *
 * hev reads its SOCKS address, port and login once, at start (see
 * [HevTunnelConfig]). A transport that comes back anywhere else is one hev no
 * longer reaches, and nothing says so: the tunnel check asks the transport's
 * port directly and passes, the status reads Connected, and every app's
 * connection is refused at the port hev still has. That is what a reconnect in
 * place did to every core line once the core's port stopped being a constant.
 */
data class BridgeTarget(
    val address: String,
    val port: Int,
    val username: String,
    val password: String
)

object TunnelBridge {
    /** Whether tun2socks has to be started again before it reaches [wanted]. */
    fun needsRestart(running: BridgeTarget?, alive: Boolean, wanted: BridgeTarget): Boolean =
        !alive || running != wanted
}

/**
 * The core's SOCKS port for one tun session.
 *
 * Drawn once and handed to every core start of the session, so a core restarted
 * behind a tun that is already up comes back where tun2socks points and
 * tun2socks need not be touched. [release] when the port could not be bound, or
 * when the session is over: the next [acquire] draws another.
 */
class SessionPort(private val draw: () -> Int) {
    private var port: Int? = null

    fun acquire(): Int = port ?: draw().also { port = it }

    fun release() {
        port = null
    }
}
