package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.vpn.DesktopMode

/** Where a session's routing rules are applied, if anywhere. */
internal enum class DesktopRulesHome {
    /** Everything through the tunnel: no rules asked for, or no way out for a direct socket. */
    Nowhere,

    /** The proxy's core, or for olcRTC the sing-box front before the engine. */
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
