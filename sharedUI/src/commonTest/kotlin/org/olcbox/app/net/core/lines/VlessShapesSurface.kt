package org.olcbox.app.net.core.lines

import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/**
 * What a VLESS line with no Reality key holds: VLESS, `tlsSettings` when it
 * has TLS, and the settings of gRPC, WebSocket, HTTP upgrade or XHTTP.
 */
object VlessShapesSurface : CoreSurfaceFragment {
    override val line: CoreLine = VlessShapes

    override val protocols: Map<String, SurfaceCheck> = mapOf("vless" to XrayRustSurface.VLESS_OUTBOUND)

    override val stream: Map<String, SurfaceCheck> = mapOf(
        "tlsSettings" to XrayRustSurface.TLS_SETTINGS,
        "grpcSettings" to XrayRustSurface.GRPC_SETTINGS,
        "wsSettings" to XrayRustSurface.WS_SETTINGS,
        "httpupgradeSettings" to XrayRustSurface.HTTPUPGRADE_SETTINGS,
        "xhttpSettings" to XrayRustSurface.XHTTP_SETTINGS,
    )
}
