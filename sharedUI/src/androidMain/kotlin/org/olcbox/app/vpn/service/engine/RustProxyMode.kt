package org.olcbox.app.vpn.service.engine

/**
 * Proxy mode (a SOCKS port for other apps on the phone) on the Rust core.
 *
 * Not built for a core line yet: its core.json demands the tun's login and
 * listens on loopback only. An olcRTC room needs only its own port and login,
 * so it runs. The Rust session asks here before it starts anything.
 */
internal object RustProxyMode {
    /** Why this start cannot run, or null when it can. [tun]: tun mode; [isOlc]: an olcRTC room. */
    fun refusal(tun: Boolean, isOlc: Boolean): String? =
        if (tun || isOlc) {
            null
        } else {
            "the Rust core engine does not run proxy mode for this line yet; use tun mode or switch it off"
        }
}
