package org.olcbox.app.net.core.lines

import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/** What a WebTunnel line's outbound holds: VLESS, `tlsSettings`, and WebSocket's or HTTP upgrade's settings. */
object WebTunnelSurface : CoreSurfaceFragment {
    override val line: CoreLine = WebTunnel

    override val protocols: Map<String, SurfaceCheck> = mapOf("vless" to XrayRustSurface.VLESS_OUTBOUND)

    override val stream: Map<String, SurfaceCheck> = mapOf(
        "tlsSettings" to XrayRustSurface.TLS_SETTINGS,
        "wsSettings" to XrayRustSurface.WS_SETTINGS,
        "httpupgradeSettings" to XrayRustSurface.HTTPUPGRADE_SETTINGS,
    )
}
