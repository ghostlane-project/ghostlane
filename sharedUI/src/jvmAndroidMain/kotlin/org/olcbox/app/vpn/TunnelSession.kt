package org.olcbox.app.vpn

/**
 * What an Android VPN interface was established with. A session keeps one
 * interface for as long as this stays the same; another [TunSpec] needs another
 * interface, and gets it by a handover rather than by closing the first.
 */
data class TunSpec(
    val splitMode: String,
    val proxyApps: Set<String>,
    val bypassApps: Set<String>
)

/**
 * The rules of a tun session, kept apart from the service so they can be read
 * and tested without a phone.
 *
 * A session begins at the first verified connection and ends when the user
 * disconnects. In between nothing closes the interface: while it is up, every
 * app routed into it has no other way out (Android blocks the family the
 * interface has no address for, and the Builder never allows a bypass), so a
 * transport that is restarting is a pause, not a moment of direct traffic.
 * Closing the interface to restart what is behind it was that moment.
 */
object TunnelSession {
    /**
     * Whether this start runs behind the interface that is already up: any
     * restart of a tun session, whoever asked for it. A first start has no
     * interface yet, and proxy mode never has one.
     */
    fun runsBehindTunnel(
        tunMode: Boolean,
        interfaceHeld: Boolean,
        isMigration: Boolean,
        isRestart: Boolean
    ): Boolean = tunMode && interfaceHeld && (isMigration || isRestart)

    /**
     * Whether the interface itself has to be replaced. Only when what it was
     * built with changed, which is split tunnelling edited while connected; a
     * new transport, a new location and a dead tun2socks all fit behind the
     * interface that exists.
     */
    fun needsHandover(held: TunSpec?, wanted: TunSpec): Boolean = held != null && held != wanted

    /**
     * Whether a start that failed may end as a first connect does: an error on
     * screen, the interface closed, traffic as it was before the tap. Holding
     * there would leave a phone without network behind a server that never
     * worked. Not inside a session, and not when Android itself asked for the
     * VPN (always-on): the system expects it to exist, and under lockdown blocks
     * everything anyway.
     */
    fun mayFailOpen(behindTunnel: Boolean, isMigration: Boolean, startedBySystem: Boolean): Boolean =
        !behindTunnel && !isMigration && !startedBySystem
}
