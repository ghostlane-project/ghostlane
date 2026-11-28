package org.olcbox.app.net.core.lines

import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/** What a VLESS line over HTTP/2 holds: VLESS, `httpSettings`, and `tlsSettings` or `realitySettings`. */
object VlessHttp2Surface : CoreSurfaceFragment {
    override val line: CoreLine = VlessHttp2

    override val protocols: Map<String, SurfaceCheck> = mapOf("vless" to XrayRustSurface.VLESS_OUTBOUND)

    override val stream: Map<String, SurfaceCheck> = mapOf(
        "tlsSettings" to XrayRustSurface.TLS_SETTINGS,
        "realitySettings" to XrayRustSurface.REALITY_SETTINGS,
        "httpSettings" to XrayRustSurface.fields(setOf("host", "path")),
    )
}
