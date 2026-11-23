package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObject
import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/**
 * What a VMess line's outbound holds: the `vmess` protocol with its one
 * server and one user, `tlsSettings`, and the settings of WebSocket, gRPC
 * and HTTP upgrade. The lists only this kind writes are copied here from the
 * core's `surface.rs` (`VMESS`, `VMESS_SERVER`, `VMESS_USER`) at the commit
 * the app pins.
 */
object VmessSurface : CoreSurfaceFragment {
    val VMESS = setOf("address", "port", "id", "security", "experiments", "alterId", "level", "email", "vnext")
    val VMESS_SERVER = setOf("address", "port", "users")
    val VMESS_USER = setOf("id", "security", "experiments", "alterId", "level", "email")

    /** A VMess outbound's `settings`: its one server and that server's one user. */
    private val VMESS_OUTBOUND: SurfaceCheck = { settings, path ->
        if (settings != null) {
            check(settings, path, VMESS)
            (settings as? JsonObject)?.get("vnext").each("$path.vnext") { server, at ->
                check(server, at, VMESS_SERVER)
                server["users"].each("$at.users") { user, userAt -> check(user, userAt, VMESS_USER) }
            }
        }
    }

    override val line: CoreLine = Vmess

    override val protocols: Map<String, SurfaceCheck> = mapOf("vmess" to VMESS_OUTBOUND)

    override val stream: Map<String, SurfaceCheck> = mapOf(
        "tlsSettings" to XrayRustSurface.TLS_SETTINGS,
        "wsSettings" to XrayRustSurface.WS_SETTINGS,
        "grpcSettings" to XrayRustSurface.GRPC_SETTINGS,
        "httpupgradeSettings" to XrayRustSurface.HTTPUPGRADE_SETTINGS,
    )
}
