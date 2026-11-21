package org.olcbox.app.net.core.lines

import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/** What a Reality line's outbound holds: VLESS, `realitySettings`, and gRPC's or XHTTP's settings. */
object RealitySurface : CoreSurfaceFragment {
    override val line: CoreLine = Reality

    override val protocols: Map<String, SurfaceCheck> = mapOf("vless" to XrayRustSurface.VLESS_OUTBOUND)

    override val stream: Map<String, SurfaceCheck> = mapOf(
        "realitySettings" to XrayRustSurface.REALITY_SETTINGS,
        "grpcSettings" to XrayRustSurface.GRPC_SETTINGS,
        "xhttpSettings" to XrayRustSurface.XHTTP_SETTINGS,
    )
}
