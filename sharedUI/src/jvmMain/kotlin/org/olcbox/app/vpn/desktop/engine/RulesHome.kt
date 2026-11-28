package org.olcbox.app.vpn.desktop.engine

/** Where a session's routing rules are applied, if anywhere. */
internal enum class DesktopRulesHome {
    /** Everything through the tunnel: no rules asked for. */
    Nowhere,

    /**
     * The engine's core. For a line, its rules; for a room, the front of the
     * session, the core half of it in the same process
     * ([org.olcbox.app.net.core.CoreRoomFront]).
     */
    Core
}

/**
 * Where the rules [needsRules] asks for are applied: in the core of
 * ghostlane-engine, in every mode.
 *
 * The core keeps every rule a line has, "only blocked sites" and a tunnel rule
 * under a whole-TLD entry among them. A rule that sends a connection direct
 * needs a socket that leaves outside the tunnel, and the engine makes them:
 *
 * - in proxy mode its sockets are ordinary ones;
 * - in the Linux, macOS and Windows tunnels the session names the way out
 *   (`bind`), and every socket of the engine but loopback's leaves by it, as
 *   the user. On Windows that is IP_UNICAST_IF by the interface's alias, which
 *   the manager reads before the tun comes up and hands to the line and the
 *   room alike; the tun's own default route is never consulted for them.
 *
 * Where hev sits in front of the engine, the engine's core sends what its
 * rules do not claim straight out by the bound sockets. The Windows DNS guard
 * blocks only the system's resolver service (svchost's Dnscache); the engine
 * asks the names of its direct rules itself, from sockets bound to the
 * physical interface.
 */
internal fun desktopRulesHome(needsRules: Boolean): DesktopRulesHome =
    if (needsRules) DesktopRulesHome.Core else DesktopRulesHome.Nowhere
