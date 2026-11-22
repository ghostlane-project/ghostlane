package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreSupport

/**
 * VLESS with no Reality key: over ordinary TLS on raw TCP (with Vision or
 * without a flow), gRPC and XHTTP, and with no TLS at all (`security=none`)
 * on raw TCP, WebSocket, gRPC, HTTP upgrade and XHTTP. Ordinary TLS over
 * WebSocket and HTTP upgrade is [WebTunnel]'s, and a link with a Reality key
 * is [Reality]'s.
 *
 * A VLESS link over HTTP/2 (`type=http`, or `h2` as older links spell it) is
 * this kind's too, with TLS, with Reality or with none, and it is refused
 * ([HTTP2_REFUSAL]): its shape is [HTTP2], which is not among the shapes this
 * kind writes.
 */
internal object VlessShapes : CoreLine {
    const val TLS = "vless-tls"
    const val TLS_GRPC = "vless-tls-grpc"
    const val TLS_XHTTP = "vless-tls-xhttp"
    const val NONE = "vless-none"
    const val NONE_WS = "vless-none-ws"
    const val NONE_GRPC = "vless-none-grpc"
    const val NONE_HTTP_UPGRADE = "vless-none-httpupgrade"
    const val NONE_XHTTP = "vless-none-xhttp"

    /** The shape of a VLESS link over HTTP/2: this kind's, always refused, so no config is written for it. */
    const val HTTP2 = "vless-http2"

    /** Why a VLESS link over HTTP/2 is refused. */
    const val HTTP2_REFUSAL =
        "the link asks for HTTP/2 (type=http), a transport Xray removed and the core does not carry (XHTTP took its place)"

    /** Why a link with Vision and no TLS is refused: Vision runs inside the line's TLS, and there is none. */
    const val VISION_NEEDS_TLS = "the link asks for Vision with no TLS (security=none), and Vision runs only inside TLS or Reality"

    override val shapes: List<String> =
        listOf(TLS, TLS_GRPC, TLS_XHTTP, NONE, NONE_WS, NONE_GRPC, NONE_HTTP_UPGRADE, NONE_XHTTP)

    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Vless) return null
        val transport = spec.transport
        return when {
            transport is TransportSpec.Http -> HTTP2
            spec.publicKey.isNotBlank() -> null
            spec.plain -> when (transport) {
                TransportSpec.Tcp -> NONE
                is TransportSpec.Ws -> NONE_WS
                is TransportSpec.Grpc -> NONE_GRPC
                is TransportSpec.HttpUpgrade -> NONE_HTTP_UPGRADE
                is TransportSpec.Xhttp -> NONE_XHTTP
                is TransportSpec.Http -> HTTP2
            }
            else -> when (transport) {
                TransportSpec.Tcp -> TLS
                is TransportSpec.Grpc -> TLS_GRPC
                is TransportSpec.Xhttp -> TLS_XHTTP
                // Ordinary TLS over WebSocket and HTTP upgrade: WebTunnel's.
                is TransportSpec.Ws, is TransportSpec.HttpUpgrade, is TransportSpec.Http -> null
            }
        }
    }

    /** Not written yet: every link of this kind is refused until its outbound is. */
    override fun refusal(spec: OutboundSpec): String? = "the core's VLESS line of this shape is not written yet"

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec =
        CoreSupport.pinVless(spec as OutboundSpec.Vless, address)

    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        error("the core's VLESS line of this shape is not written yet")
    }
}
