package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.putTls
import org.olcbox.app.net.core.putTransport

/**
 * Trojan over raw TCP, WebSocket, gRPC and HTTP upgrade, always with ordinary
 * TLS: the link parser gives a Trojan link TLS whatever its `security` says
 * (Reality aside, which it refuses), and the core refuses Trojan without TLS
 * to any server but a private one.
 *
 * A link that waives certificate checks (`allowInsecure=1`, or `insecure=1`)
 * is carried: the core honours `allowInsecure` only when the config sets it,
 * and warns, naming the outbound and its server. A link that does not is
 * written without the key, and the core verifies the certificate.
 */
internal object Trojan : CoreLine {
    const val RAW = "trojan"
    const val WS = "trojan-ws"
    const val GRPC = "trojan-grpc"
    const val HTTP_UPGRADE = "trojan-httpupgrade"

    /**
     * Trojan over HTTP/2 (`type=http`, or `h2`): a shape of this kind's links
     * that it does not write ([refusal]), so not among [shapes].
     */
    const val HTTP = "trojan-http"

    /** What the core sends as a Trojan password: 1 to 4096 bytes of UTF-8. */
    private const val MAX_PASSWORD_BYTES = 4096

    /** Xray's name for a value taken from its MITM inbound, which the core refuses as a server name or an ALPN. */
    private const val FROM_MITM = "frommitm"

    override val shapes: List<String> = listOf(RAW, WS, GRPC, HTTP_UPGRADE)

    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Trojan) return null
        return when (spec.transport) {
            TransportSpec.Tcp -> RAW
            is TransportSpec.Ws -> WS
            is TransportSpec.Grpc -> GRPC
            is TransportSpec.HttpUpgrade -> HTTP_UPGRADE
            is TransportSpec.Http -> HTTP
            // The link parser gives a Trojan link no XHTTP.
            is TransportSpec.Xhttp -> null
        }
    }

    /**
     * Refused: HTTP/2, which the core does not have (Xray removed it for
     * XHTTP); a password the core does not send; a port it cannot dial; and
     * `fromMitm` as the server name or an ALPN. Waived certificate checks are
     * not refused: they are the link's to ask for.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Trojan
        return when {
            spec.transport is TransportSpec.Http ->
                "the link asks for HTTP/2 (type=http), a transport the core does not have"
            spec.password.isEmpty() || spec.password.encodeToByteArray().size > MAX_PASSWORD_BYTES ->
                "a Trojan password the core does not send (it sends 1 to 4096 bytes)"
            spec.port !in 1..65535 -> "the port is not one from 1 to 65535"
            spec.tls.sni.lowercase() == FROM_MITM || spec.tls.alpn.any { it.lowercase() == FROM_MITM } ->
                "the link asks for fromMitm, which only Xray's MITM inbound gives"
            else -> null
        }
    }

    /**
     * [spec] with [address] for its server: the name stays the TLS server
     * name, and the Host of WebSocket and HTTP upgrade where the link left
     * them to be the server's own.
     */
    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec {
        spec as OutboundSpec.Trojan
        val name = spec.host
        return spec.copy(
            host = address,
            tls = spec.tls.copy(sni = spec.tls.sni.ifBlank { name }),
            transport = when (val t = spec.transport) {
                is TransportSpec.Ws -> t.copy(host = t.host.ifBlank { name })
                is TransportSpec.HttpUpgrade -> t.copy(host = t.host.ifBlank { name })
                else -> t
            },
        )
    }

    /**
     * One server in Xray's `servers` form, then the transport and TLS: the
     * link's server name, its hello as the core names it
     * ([CoreSupport.helloName]), its ALPN as it gives it, and `allowInsecure`
     * only when the link waives the checks.
     */
    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Trojan
        put("protocol", "trojan")
        putJsonObject("settings") {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", spec.host); put("port", spec.port)
                    put("password", spec.password)
                }
            }
        }
        putJsonObject("streamSettings") {
            putTransport(spec.transport)
            putTls(
                serverName = spec.tls.sni,
                fingerprint = CoreSupport.helloName(spec.tls.fingerprint),
                alpn = spec.tls.alpn,
                insecure = spec.tls.insecure,
            )
        }
    }
}
