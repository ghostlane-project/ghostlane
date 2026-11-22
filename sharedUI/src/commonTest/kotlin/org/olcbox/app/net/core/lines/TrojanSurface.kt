package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObject
import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/**
 * What a Trojan line's outbound holds: the `trojan` protocol with its one
 * server, `tlsSettings`, and WebSocket's, gRPC's or HTTP upgrade's settings.
 * The lists only this kind writes are copied here from the core's
 * `surface.rs` (TROJAN and TROJAN_SERVER), at the commit the app pins.
 */
object TrojanSurface : CoreSurfaceFragment {
    val TROJAN = setOf("address", "port", "password", "level", "email", "flow", "servers")
    val TROJAN_SERVER = setOf("address", "port", "password", "level", "email", "flow")

    /** A Trojan outbound's `settings`: its servers, each with the server's own fields. */
    private val TROJAN_OUTBOUND: SurfaceCheck = { settings, path ->
        if (settings != null) {
            check(settings, path, TROJAN)
            (settings as? JsonObject)?.get("servers").each("$path.servers") { server, at -> check(server, at, TROJAN_SERVER) }
        }
    }

    override val line: CoreLine = Trojan

    override val protocols: Map<String, SurfaceCheck> = mapOf("trojan" to TROJAN_OUTBOUND)

    override val stream: Map<String, SurfaceCheck> = mapOf(
        "tlsSettings" to XrayRustSurface.TLS_SETTINGS,
        "wsSettings" to XrayRustSurface.WS_SETTINGS,
        "grpcSettings" to XrayRustSurface.GRPC_SETTINGS,
        "httpupgradeSettings" to XrayRustSurface.HTTPUPGRADE_SETTINGS,
    )
}
