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
    /**
     * Whether tun2socks has to be started again before it reaches [wanted].
     *
     * [stopping]: it has been told to stop and has not gone yet. Its thread is
     * still alive, and it is no bridge: a start that was superseded stops
     * tun2socks on its way out, and the start that follows would otherwise find
     * it "alive" on the right target and report a session with nothing reading
     * the tun.
     */
    fun needsRestart(running: BridgeTarget?, alive: Boolean, stopping: Boolean, wanted: BridgeTarget): Boolean =
        !alive || stopping || running != wanted
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
