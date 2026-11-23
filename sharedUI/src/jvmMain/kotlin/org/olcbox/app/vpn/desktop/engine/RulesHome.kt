package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.vpn.DesktopMode

/** Where a session's routing rules are applied, if anywhere. */
internal enum class DesktopRulesHome {
    /** Everything through the tunnel: no rules asked for, or no way out for a direct socket. */
    Nowhere,

    /**
     * The proxy's core; for olcRTC the core in front of the room: sing-box
     * before the Go engine, the core half of the session on ghostlane-engine
     * ([org.olcbox.app.net.core.CoreRoomFront]).
     */
    Core,

    /** The macOS tunnel daemon; the core behind it stays as it was. */
    Daemon,

    /** The olcRTC engine itself, from its direct rules. */
    Engine
}

/**
 * Where [mode] can apply the rules [needsRules] asks for. A rule that sends a
 * connection direct needs a socket that leaves outside the tunnel:
 *
 * - the proxy's sockets are ordinary ones;
 * - the macOS daemon binds its own to the physical interface;
 * - in the Linux tunnel only root's traffic keeps the main table, and of what
 *   runs there only the olcRTC engine runs as root. A core runs as the user and
 *   reaches its server bound to the physical interface (startDesktopCore); its
 *   rules have not been moved there;
 * - in the Windows tunnel nothing does yet.
 *
 * The proxy keeps its front for olcRTC: it also does what the engine cannot,
 * "only blocked sites" and a tunnel rule under a whole-TLD entry.
 */
internal fun desktopRulesHome(mode: DesktopMode, isOlcrtc: Boolean, needsRules: Boolean): DesktopRulesHome =
    when {
        !needsRules -> DesktopRulesHome.Nowhere
        mode == DesktopMode.SystemProxy -> DesktopRulesHome.Core
        mode == DesktopMode.MacTun -> DesktopRulesHome.Daemon
        mode == DesktopMode.LinuxTun && isOlcrtc -> DesktopRulesHome.Engine
        else -> DesktopRulesHome.Nowhere
    }

/** Which engine runs a desktop session's line or room. */
internal enum class DesktopLineEngine {
    /** The Go programs: sing-box or Xray for a line, the olcRTC engine for a room with sing-box in front of it. */
    Go,

    /** ghostlane-engine, the Rust core: a line, or a room with the core in front of it in the same process. */
    Rust
}

/**
 * Where [mode] applies the rules [needsRules] asks for when [engine] runs the
 * session.
 *
 * On the Go engines, and for a core line on ghostlane-engine, the answer of
 * the [desktopRulesHome] above: where a line's rules live in the tunnels is
 * the tunnels' own to move.
 *
 * A room on ghostlane-engine keeps its rules in its front, the core half of
 * its session ([org.olcbox.app.net.core.CoreRoomFront]), in the process the
 * room runs in: every rule a line has, "only blocked sites" and a tunnel rule
 * under a whole-TLD entry among them. A rule that sends a connection direct
 * needs a socket that leaves outside the tunnel, and the engine makes them:
 *
 * - in proxy mode its sockets are ordinary ones;
 * - in the Linux and macOS tunnels the session names the way out (`bind`),
 *   and every socket of the engine but loopback's leaves by it, as the user;
 * - in the Windows tunnel the engine binds no socket yet, so everything goes
 *   through the tunnel.
 */
internal fun desktopRulesHome(mode: DesktopMode, isOlcrtc: Boolean, needsRules: Boolean, engine: DesktopLineEngine): DesktopRulesHome =
    when {
        engine == DesktopLineEngine.Go || !isOlcrtc -> desktopRulesHome(mode, isOlcrtc, needsRules)
        !needsRules -> DesktopRulesHome.Nowhere
        mode == DesktopMode.WindowsTun -> DesktopRulesHome.Nowhere
        else -> DesktopRulesHome.Core
    }
