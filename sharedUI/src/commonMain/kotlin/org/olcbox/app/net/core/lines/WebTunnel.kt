package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.putTls
import org.olcbox.app.net.core.putTransport
import org.olcbox.app.net.core.putVless

/** WebTunnel: VLESS over WebSocket or over HTTP upgrade, with ordinary TLS. */
internal object WebTunnel : CoreLine {
    const val WS = "webtunnel-ws"
    const val HTTP_UPGRADE = "webtunnel-httpupgrade"

    override val shapes: List<String> = listOf(WS, HTTP_UPGRADE)

    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Vless || spec.plain || spec.publicKey.isNotBlank()) return null
        return when (spec.transport) {
            is TransportSpec.Ws -> WS
            is TransportSpec.HttpUpgrade -> HTTP_UPGRADE
            else -> null
        }
    }

    /**
     * Refused: `allowInsecure`, since the core verifies every certificate
     * (Xray removed the switch; a pin is the way to trust a self-signed one),
     * and a flow other than Vision.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Vless
        return if (spec.insecure) CoreRefusal.WAIVED_CHECKS else CoreSupport.flowRefusal(spec.flow)
    }

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec =
        CoreSupport.pinVless(spec as OutboundSpec.Vless, address)

    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Vless
        putVless(spec)
        putJsonObject("streamSettings") {
            putTransport(spec.transport)
            putTls(serverName = spec.sni, fingerprint = CoreSupport.helloName(spec.fingerprint), alpn = spec.alpn)
        }
    }
}
