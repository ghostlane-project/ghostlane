package org.olcbox.app.vpn.desktop

/**
 * The addresses and the name that the desktop tunnels and the app agree on.
 * They were constants of the sing-box config builder, which is gone.
 */
internal object DesktopTunNames {
    /**
     * The desktop tun's own address. An IPv4 one is not enough alone: see
     * [ADDRESS6].
     */
    const val ADDRESS = "172.19.0.1/30"

    /**
     * IPv6 is claimed and refused. The tun needs an IPv6 address even though no IPv6 is carried, because
     * a route is claimed only for the families the interface has an address for.
     *
     * Without it the machine keeps its IPv6 default route on the physical
     * interface and every dual-stack site is reached over IPv6, outside the
     * tunnel, at the machine's real address. That is not a corner case: browsers
     * prefer IPv6, so on a dual-stack network *the browser* leaks while
     * `curl api.ipify.org` (an A record only) keeps reporting the tunnel and
     * looking fine.
     */
    const val ADDRESS6 = "fdfe:dcba:9876::1/126"

    /**
     * The address the macOS helper has the system ask for names while a tun
     * that answers every name runs: the other end of [ADDRESS]. Nobody is
     * there. It only has to be an address whose route is the tun's, so that
     * the question enters the tun, where it is answered.
     */
    const val RESOLVER = "172.19.0.2"

    /**
     * A name that is answered, with [NAMES_PROGRAMS_ANSWER], only to the one
     * program that is named for it, and does not exist for anybody else. It is
     * how the app learns that a tun has begun to name the programs behind its
     * connections.
     */
    const val NAMES_PROGRAMS_PROBE = "names-its-programs.ghostlane.invalid"
    const val NAMES_PROGRAMS_ANSWER = "127.0.0.53"
}
