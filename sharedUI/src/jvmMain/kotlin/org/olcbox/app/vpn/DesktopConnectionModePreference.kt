package org.olcbox.app.vpn

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.MacOsTunnelDaemon
import java.nio.file.Files

/**
 * Which of the two ways of carrying traffic the person chose, on the desktops
 * that have two.
 *
 * Android has had this choice since it had a tun, and the desktop never did —
 * not because it was taken away, but because until macOS grew a tunnel every
 * desktop had exactly one mode available and the settings screen showed a card
 * that looked like a chooser and was a label. This is the same idea as
 * `AndroidConnectionMode`, deliberately: same two names, same meaning, so that
 * one explanation covers every platform.
 *
 * Availability is not uniform and cannot be, so it is reported rather than
 * assumed: Linux has no system-proxy implementation at all
 * (`UnsupportedProxyController`), and macOS cannot use a tun until its root
 * daemon has been approved.
 */
enum class DesktopConnectionMode(val id: String) {
    Tun("tun"),
    Proxy("proxy");

    companion object {
        fun fromId(id: String?): DesktopConnectionMode = entries.firstOrNull { it.id == id } ?: Tun
    }
}

/** One row of the chooser, with the reason it cannot be picked when it cannot. */
data class DesktopConnectionModeOption(
    val mode: DesktopConnectionMode,
    val title: String,
    val summary: String,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
)

object DesktopConnectionModePreference {

    private val file by lazy { DesktopPaths.appDataDir().resolve("desktop_connection_mode") }

    /**
     * Tun by default, which keeps Linux and Windows behaving exactly as they
     * always have and gives macOS a tunnel the moment its daemon is approved —
     * the state a person who went and approved it is asking for.
     */
    fun selected(): DesktopConnectionMode = runCatching {
        DesktopConnectionMode.fromId(Files.readString(file).trim())
    }.getOrDefault(DesktopConnectionMode.Tun)

    fun select(mode: DesktopConnectionMode) {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, mode.id)
        }
    }

    /**
     * What this machine can actually offer. An option missing from here is one
     * the platform cannot do; an option present but disabled is one the person
     * can have after doing something, and [DesktopConnectionModeOption.disabledReason]
     * says what.
     */
    fun available(): List<DesktopConnectionModeOption> = when (DesktopPaths.os) {
        DesktopOs.MacOS -> listOf(
            DesktopConnectionModeOption(
                mode = DesktopConnectionMode.Tun,
                title = "System-wide tunnel",
                summary = "Every app on this Mac, through a utun",
                enabled = MacOsTunnelDaemon.status() == MacOsTunnelDaemon.Registration.Enabled,
                disabledReason = "Install the system-wide tunnel below first"
            ),
            DesktopConnectionModeOption(
                mode = DesktopConnectionMode.Proxy,
                title = "Proxy",
                summary = "Local SOCKS5 proxy — only apps that follow the system proxy"
            )
        )
        DesktopOs.Windows -> listOf(
            DesktopConnectionModeOption(
                mode = DesktopConnectionMode.Tun,
                title = "System-wide tunnel",
                summary = "Every app on this PC — Ghostlane restarts as administrator"
            ),
            DesktopConnectionModeOption(
                mode = DesktopConnectionMode.Proxy,
                title = "Proxy",
                summary = "Local SOCKS5 proxy — only apps that follow the system proxy"
            )
        )
        // One option, and it is not a choice: there is no system-proxy
        // implementation for Linux, so offering it would be offering an error.
        DesktopOs.Linux -> listOf(
            DesktopConnectionModeOption(
                mode = DesktopConnectionMode.Tun,
                title = "System-wide tunnel",
                summary = "Every app on this machine, through a TUN device"
            )
        )
        DesktopOs.Other -> emptyList()
    }

    /** The option currently in force, which is not always the one stored. */
    fun effective(): DesktopConnectionModeOption? = effective(available(), selected())

    /**
     * The same answer from values the caller already holds. A screen that keeps
     * the options and the choice as state derives the answer from them, so a
     * pick redraws it; asked the disk each time, it redraws only when something
     * unrelated happens to.
     */
    fun effective(
        options: List<DesktopConnectionModeOption>,
        selected: DesktopConnectionMode
    ): DesktopConnectionModeOption? {
        val stored = options.firstOrNull { it.mode == selected }
        if (stored != null && stored.enabled) return stored
        return options.firstOrNull { it.enabled } ?: options.firstOrNull()
    }
}

/**
 * Why the routing choice cannot be applied on this desktop right now, or null
 * where it can. The proxy and the macOS tunnel apply it; the Linux and Windows
 * tunnels route by policy and by metric, and a direct socket from the core
 * would enter them, so they stay global until they have a way out.
 */
fun desktopRoutingUnavailableReason(effective: DesktopConnectionMode?): String? =
    routingUnavailableReasonFor(DesktopPaths.os, effective)

internal fun routingUnavailableReasonFor(os: DesktopOs, effective: DesktopConnectionMode?): String? {
    val tunWithoutAWayOut = when (os) {
        DesktopOs.Linux -> true
        DesktopOs.Windows -> false
        DesktopOs.MacOS, DesktopOs.Other -> false
    }
    return if (tunWithoutAWayOut) {
        "Available in proxy mode and Windows/macOS tunnels. Linux TUN routing is not supported yet."
    } else {
        null
    }
}
