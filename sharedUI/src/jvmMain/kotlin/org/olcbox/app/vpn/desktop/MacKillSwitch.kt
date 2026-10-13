package org.olcbox.app.vpn.desktop

import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.KILL_SWITCH_HOLDS_TRAFFIC_MAC
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.shownUnderKillSwitch

/**
 * The Mac tunnel's kill switch: what holds the Mac's traffic when the tunnel's
 * own process dies, and on the way from one tunnel to the next.
 *
 * The leak. On a Mac the tun and every route through it belong to the tunnel's
 * process and go with it, however it ends: killed outright, the interface is
 * gone and the default route underneath carries everything again, at once
 * (measured on macOS 26; the comment that said such a Mac has no network until
 * it is restarted was wrong). A core or an engine that dies does not do this
 * ([LineSupervision]): the tun stays and points at a port nobody listens on.
 *
 * The block is the packet filter's, loaded by the tunnel helper, which is the
 * one part of the app that is root (`TrafficHold` in the helper has the rules
 * and why each is there). It lets out what is routed into the tunnel's
 * interface; what the tunnel's own sing-box sends, which is every line's way
 * out; the address of the server the tun keeps out of itself; the lease; and
 * the local network, port 53 aside. Everything else is refused, to the program
 * that sent it, at once.
 *
 * What it holds, then: every program's traffic that would have gone to the
 * internet beside the tunnel, root's included, and another VPN's with it. What
 * it does not: the local network, which the Mac tunnel has never carried
 * either. And it is rules in the kernel, so it holds for as long as they
 * stand: not across a restart of the Mac.
 *
 * Three things take it away: a Disconnect, the switch turned off, and the
 * helper being stopped by the system (switched off in Login Items, the app
 * deleted). Unlike on Linux none of them asks for a password, so the switch
 * turned off takes effect at once, under a running tunnel too. A tunnel that
 * died, a Cancel, a quit from a held state, a reconnect that fails: none of
 * those removes it. Nor does a session that is restarted in full into another
 * tunnel, for another routing: the block is kept across the restart, and the
 * new tunnel comes up behind it. Restarted as a proxy's, it goes with the
 * tunnel ([keptForWhatComesNext]).
 *
 * Behind a block that stands, nothing of a line gets out except through the
 * tunnel's own process, or in the tunnel's group. A core is started behind its
 * tunnel there, and leaves by the tunnel's rule on its binary. A room's engine
 * cannot: it binds its sockets to the address of the interface that carries
 * the internet, which this system routes beside any tunnel, so behind one its
 * media left in its own name and was refused. The helper runs it instead, in
 * the group the block lets out, and it is started first, as it is without the
 * switch ([order], `DesktopVpnManager.startDesktopMode`).
 */
internal object MacKillSwitch {
    /**
     * What the user is shown for [status] while the block stands with no tunnel
     * in front of it ([holdsTraffic]): [shownUnderKillSwitch], in a Mac's
     * words, which name no password.
     */
    fun shown(status: VpnStatus, holdsTraffic: Boolean): VpnStatus =
        shownUnderKillSwitch(status, holdsTraffic, KILL_SWITCH_HOLDS_TRAFFIC_MAC)

    /**
     * Whether the block under a verified session's tunnel is kept when that
     * tunnel is taken down by anything but a Disconnect: only with the switch
     * still on, and only when what comes next is a tunnel again, by the mode
     * the user has chosen now ([nextMode]).
     *
     * A proxy has no tunnel for a block to stand behind. Kept across a change
     * to proxy mode, the block would refuse the proxy's own line, and the
     * Mac would have no network for a setting having been changed.
     */
    fun keptForWhatComesNext(switchOn: Boolean, nextMode: DesktopMode): Boolean =
        switchOn && nextMode == DesktopMode.MacTun

    /** How a connect is ordered: what comes first, and who runs a room's engine. */
    data class Order(
        /** The tunnel is started before the line, and the line behind it. */
        val tunFirst: Boolean,
        /** A room's engine is the helper's to run, in the group the block lets out. */
        val engineByHelper: Boolean
    )

    /**
     * The order of a connect, by what the helper says of itself ([helper], null
     * where no tunnel of the helper's is asked for), the switch, and whether the
     * line is a room.
     *
     * A room's engine under the switch, or behind a block that stands, is run
     * by a helper that can run one, and then started as it is without the
     * switch: first. Behind its tunnel instead, the sockets it binds to the
     * address of the interface that carries the internet left beside the
     * tunnel in its own name and were refused, and a carrier with UDP alone
     * could not be joined. A helper that cannot run it still has it that way.
     *
     * A core comes behind its tunnel only where a block stands already:
     * nothing of it gets out through one except by a tunnel's own process.
     */
    fun order(helper: MacOsTunController.Helper?, switchOn: Boolean, isRoom: Boolean): Order {
        if (helper == null) return Order(tunFirst = false, engineByHelper = false)
        val underSwitch = helper.canHold && switchOn
        val engineByHelper = isRoom && helper.runsLines && (helper.blockStands || underSwitch)
        val tunFirst = !engineByHelper && (helper.blockStands || (underSwitch && isRoom))
        return Order(tunFirst = tunFirst, engineByHelper = engineByHelper)
    }
}
