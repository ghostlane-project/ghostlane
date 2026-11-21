package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.putReality
import org.olcbox.app.net.core.putTransport
import org.olcbox.app.net.core.putVless

/**
 * VLESS over Reality: on raw TCP with Vision or without a flow, over gRPC,
 * and over XHTTP (ProofKit's packet-up line).
 */
internal object Reality : CoreLine {
    const val RAW = "reality"
    const val GRPC = "grpc-reality"
    const val XHTTP = "xhttp"

    /**
     * The names a link's `fp` is read as ([CoreSupport.helloName]) that
     * xray-rust has no REALITY hello for: android and 360 offer no X25519
     * key share, and the core refuses them under `realitySettings`.
     */
    private val NO_REALITY_HELLO = setOf("android", "360")

    override val shapes: List<String> = listOf(RAW, GRPC, XHTTP)

    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Vless || spec.plain || spec.publicKey.isBlank()) return null
        return when (spec.transport) {
            TransportSpec.Tcp -> RAW
            is TransportSpec.Grpc -> GRPC
            is TransportSpec.Xhttp -> XHTTP
            else -> null
        }
    }

    /** A link's `fp` as `realitySettings` takes it; null for a name with no REALITY hello in the core. */
    fun hello(fp: String?): String? = CoreSupport.helloName(fp).takeUnless { it in NO_REALITY_HELLO }

    /**
     * Refused: a link that waives certificate checks, a flow other than
     * Vision, a fingerprint with no REALITY hello in the core
     * ([NO_REALITY_HELLO]), and Reality with no server name.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Vless
        return when {
            spec.insecure -> CoreRefusal.WAIVED_CHECKS
            CoreSupport.flowRefusal(spec.flow) != null -> CoreSupport.flowRefusal(spec.flow)
            hello(spec.fingerprint) == null -> "the core has no REALITY hello for this fingerprint"
            spec.sni.isBlank() -> "a Reality line with no server name"
            else -> null
        }
    }

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec =
        CoreSupport.pinVless(spec as OutboundSpec.Vless, address)

    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Vless
        putVless(spec)
        putJsonObject("streamSettings") {
            putTransport(spec.transport)
            putReality(
                serverName = spec.sni,
                fingerprint = requireNotNull(hello(spec.fingerprint)),
                publicKey = spec.publicKey,
                shortId = spec.shortId,
            )
        }
    }
}
