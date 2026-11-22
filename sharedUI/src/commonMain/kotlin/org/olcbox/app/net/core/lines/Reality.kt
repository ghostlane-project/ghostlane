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
     * key share, and the core refuses them under `realitySettings`. The line
     * is carried with the Chrome hello instead ([hello]), and [helloNote]
     * says so.
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

    /**
     * A link's `fp` as `realitySettings` takes it: [CoreSupport.helloName],
     * with the names the core has no REALITY hello for (android, 360) given
     * the Chrome hello. Nothing else is made to look like another client.
     */
    fun hello(fp: String?): String = CoreSupport.helloName(fp).let { if (it in NO_REALITY_HELLO) "chrome" else it }

    /**
     * The sentence for the caller's log when [spec]'s hello was swapped for
     * Chrome's ([hello]), or null when it was kept or the line is not a Reality line (any
     * caller may ask it about every line). Never a secret: it names
     * the hello and nothing of the link.
     */
    fun helloNote(spec: OutboundSpec): String? {
        if (shape(spec) == null) return null
        spec as OutboundSpec.Vless
        val asked = CoreSupport.helloName(spec.fingerprint)
        return if (asked in NO_REALITY_HELLO) {
            "the link asks for the $asked hello, which the core has no REALITY form of: the Chrome hello is used instead"
        } else {
            null
        }
    }

    /**
     * Refused: a link that waives certificate checks, a flow other than
     * Vision, and Reality with no server name. A fingerprint with no REALITY
     * hello in the core is carried with the Chrome hello ([hello]).
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Vless
        return when {
            spec.insecure -> CoreRefusal.WAIVED_CHECKS
            CoreSupport.flowRefusal(spec.flow) != null -> CoreSupport.flowRefusal(spec.flow)
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
                fingerprint = hello(spec.fingerprint),
                publicKey = spec.publicKey,
                shortId = spec.shortId,
            )
        }
    }
}
