package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreLine

/**
 * What a Hysteria2 line's outbound holds: the `hysteria` protocol,
 * `tlsSettings`, `hysteriaSettings`, and under `finalmask` Salamander and the
 * QUIC parameters. The lists only this kind writes are copied here from the
 * core's `surface.rs`, at the commit [XrayRustSurface] names.
 */
object Hysteria2Surface : CoreSurfaceFragment {
    val HYSTERIA = setOf("version", "address", "port")
    val HYSTERIA_TRANSPORT = setOf("version", "auth")
    val FINALMASK = setOf("tcp", "udp", "quicParams")
    val FINALMASK_ENTRY = setOf("type", "settings")
    val SALAMANDER = setOf("password", "packetSize")
    val QUIC_PARAMS = setOf(
        "congestion", "debug", "bbrProfile", "brutalUp", "brutalDown", "udpHop", "initStreamReceiveWindow",
        "maxStreamReceiveWindow", "initConnectionReceiveWindow", "maxConnectionReceiveWindow", "maxIdleTimeout",
        "keepAlivePeriod", "disablePathMTUDiscovery", "maxIncomingStreams", "disableChromeParrot", "disableGSO",
        "disableStatelessReset", "brutalDisableLossCompensation",
    )

    /** `finalmask`: its UDP masks, of which Salamander alone is known, and the QUIC parameters. */
    private val FINALMASK_OBJECT: SurfaceCheck = { value, at ->
        check(value, at, FINALMASK)
        (value as? JsonObject)?.get("udp").each("$at.udp") { mask, maskAt ->
            check(mask, maskAt, FINALMASK_ENTRY)
            if (mask["type"]?.jsonPrimitive?.content != "salamander") {
                found.add("$maskAt.type is not checked here")
            }
            check(mask["settings"], "$maskAt.settings", SALAMANDER)
        }
        (value as? JsonObject)?.get("quicParams")?.let { check(it, "$at.quicParams", QUIC_PARAMS) }
        if ((value as? JsonObject)?.get("tcp") != null) found.add("$at.tcp is not checked here")
    }

    override val line: CoreLine = Hysteria2

    override val protocols: Map<String, SurfaceCheck> = mapOf("hysteria" to XrayRustSurface.fields(HYSTERIA))

    override val stream: Map<String, SurfaceCheck> = mapOf(
        "tlsSettings" to XrayRustSurface.TLS_SETTINGS,
        "hysteriaSettings" to XrayRustSurface.fields(HYSTERIA_TRANSPORT),
        "finalmask" to FINALMASK_OBJECT,
    )
}
