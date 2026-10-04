package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.KILL_SWITCH_HOLDS_TRAFFIC
import org.olcbox.app.vpn.VpnStatus

/**
 * The Linux tunnel's kill switch: what holds the machine's traffic when the
 * tunnel's own process dies. Its rules are kept apart from the manager and the
 * controller so they can be read and tested without a desktop.
 *
 * The leak. The tun's rule sends every lookup to table 51820, whose only IPv4
 * route is `default dev olcbox0`. hev's death takes the device and its route,
 * the lookup finds an empty table and falls through to the main one, and the
 * machine's traffic leaves directly, at its own address, while the app is still
 * deciding what to say. A core or an engine that dies does not do this
 * ([LineSupervision]): the tun stays and points at a port nobody listens on.
 *
 * The block is a second default route in the same table, on a dummy device and
 * with the last metric (`LinuxTunController.KILL_SWITCH_DEVICE`). While the tun
 * is up its own route, at metric 0, wins. Once it is gone a lookup lands on the
 * dummy, which drops what it is given. It is a route on a device, and not an
 * `unreachable` or `blackhole` one, because of the cores: they run as the user
 * and get out of the tun by being bound to the physical interface, and a lookup
 * with an outgoing interface passes over a route on another device and reaches
 * the main table. Over an `unreachable` route it does not pass: the kernel
 * takes the destination for on-link once such a lookup has failed, and sends
 * the packet out without its gateway, which reaches nothing.
 *
 * What it holds is therefore every lookup the rule sends to that table, the
 * local network's included. What it does not: root's own traffic and what the
 * machine forwards for a container or a virtual machine (a lookup with no
 * socket behind it counts as root's), which the rule before it sends to the
 * main table and the tunnel has never carried; and a socket bound to an
 * interface. And it is routes and rules, so it holds for as long as they
 * stand: not across a restart of the machine.
 *
 * Two things take it away: the user turning the switch off, and a new tunnel
 * that is verified over it. A tunnel that died is not followed by the cleanup,
 * as it is without the switch: that runs as root, so it opens a password
 * dialog by itself, and the password entered there is what would let the
 * traffic out. Nor does a Cancel, a quit, or a reconnect that fails remove it.
 */
internal object LinuxKillSwitch {
    /**
     * What the user is shown for [status] while the block stands with no tunnel
     * in front of it ([holdsTraffic]).
     *
     * "Disconnected", or the error that ended the session, would both be true
     * and both leave out the one thing that matters: this machine's traffic
     * is held, and here is how to change that. So both become one sentence,
     * the same wherever the block was met: the tunnel's death, a reconnect
     * that failed, a cleanup that was refused, a block left by a previous run.
     * What went wrong is in the log. A status that says something is being
     * done (connecting, stopping) is left as it is.
     */
    fun shown(status: VpnStatus, holdsTraffic: Boolean): VpnStatus = when {
        !holdsTraffic -> status
        status is VpnStatus.Disconnected || status is VpnStatus.Error -> VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC)
        else -> status
    }

    /**
     * Whether [routes], as `ip route show table 51820` prints them, has the
     * block's route. Compared word by word: the tun is `olcbox0`, the dummy is
     * `olcboxks0`, and a test on how the line begins is one renamed device away
     * from taking one for the other.
     */
    fun routeStands(routes: String): Boolean = routes.lineSequence().any { line ->
        line.trim().split(WHITESPACE).take(3) == listOf("default", "dev", LinuxTunController.KILL_SWITCH_DEVICE)
    }

    private val WHITESPACE = Regex("\\s+")
}
