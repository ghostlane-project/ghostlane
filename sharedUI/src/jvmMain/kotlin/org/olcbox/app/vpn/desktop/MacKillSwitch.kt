package org.olcbox.app.vpn.desktop

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
 * those removes it. Nor does a session that is restarted in full, for another
 * routing or another mode: the block is kept across the restart, and the new
 * tunnel comes up behind it.
 *
 * Behind a block that stands, a line cannot be started before its tunnel: a
 * room's engine has to reach a meeting service whose addresses nobody knows in
 * advance, and nothing of it gets out except through the tunnel's process. So
 * such a connect starts the tunnel first and the line behind it, and so does
 * every connect to a room with the switch on: an engine that joined first
 * would have what it had just connected refused when the block was loaded
 * (`DesktopVpnManager.startDesktopMode`).
 */
internal object MacKillSwitch {
    /**
     * What the user is shown for [status] while the block stands with no tunnel
     * in front of it ([holdsTraffic]): [shownUnderKillSwitch], in a Mac's
     * words, which name no password.
     */
    fun shown(status: VpnStatus, holdsTraffic: Boolean): VpnStatus =
        shownUnderKillSwitch(status, holdsTraffic, KILL_SWITCH_HOLDS_TRAFFIC_MAC)
}
