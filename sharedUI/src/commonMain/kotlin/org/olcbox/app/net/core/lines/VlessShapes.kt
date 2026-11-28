package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.putTls
import org.olcbox.app.net.core.putTransport
import org.olcbox.app.net.core.putVless

/**
 * VLESS with no Reality key: over ordinary TLS on raw TCP (with Vision or
 * without a flow), gRPC and XHTTP, and with no TLS at all (`security=none`)
 * on raw TCP, WebSocket, gRPC, HTTP upgrade and XHTTP, which the core sends
 * only to a private, loopback or link-local server. Ordinary TLS over
 * WebSocket and HTTP upgrade is [WebTunnel]'s, and a link with a Reality key
 * is [Reality]'s.
 *
 * A VLESS link over HTTP/2 (`type=http`, or `h2` as older links spell it) is
 * [VlessHttp2]'s, with TLS, with Reality or with none.
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

    /** Why a link with Vision and no TLS is refused: Vision runs inside the line's TLS, and there is none. */
    const val VISION_NEEDS_TLS = "the link asks for Vision with no TLS (security=none), and Vision runs only inside TLS or Reality"

    /**
     * Why a link with no TLS to a public server is refused: the core sends
     * VLESS in the clear only to a private, loopback or link-local address,
     * and refuses the whole config at parse for any other.
     */
    const val PLAIN_NEEDS_PRIVATE_SERVER =
        "the link asks for VLESS with no TLS (security=none) to a public address, and the core sends VLESS in the clear " +
            "only to a private, loopback or link-local server"

    override val shapes: List<String> =
        listOf(TLS, TLS_GRPC, TLS_XHTTP, NONE, NONE_WS, NONE_GRPC, NONE_HTTP_UPGRADE, NONE_XHTTP)

    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Vless) return null
        val transport = spec.transport
        return when {
            transport is TransportSpec.Http -> null
            spec.publicKey.isNotBlank() -> null
            spec.plain -> when (transport) {
                TransportSpec.Tcp -> NONE
                is TransportSpec.Ws -> NONE_WS
                is TransportSpec.Grpc -> NONE_GRPC
                is TransportSpec.HttpUpgrade -> NONE_HTTP_UPGRADE
                is TransportSpec.Xhttp -> NONE_XHTTP
                is TransportSpec.Http -> null
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

    /**
     * Refused: a flow other than Vision, Vision
     * with no TLS ([VISION_NEEDS_TLS]), and no TLS to a server the core
     * does not send VLESS in the clear to ([PLAIN_NEEDS_PRIVATE_SERVER]). A
     * link that waives the certificate check is carried: the waiver is the
     * link's own, and the core honours it where the config sets it, with a
     * warning that names the outbound.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Vless
        val flow = CoreSupport.flowRefusal(spec.flow)
        return when {
            flow != null -> flow
            spec.plain && spec.flow != null -> VISION_NEEDS_TLS
            spec.plain && !isPrivateServer(spec.host) -> PLAIN_NEEDS_PRIVATE_SERVER
            else -> null
        }
    }

    /**
     * Whether the core sends VLESS in the clear to [host], the server's
     * address as the link or the pin gives it (an IPv6 one with or without
     * its brackets): a private, loopback or link-local one (Go's IsPrivate,
     * IsLoopback and IsLinkLocalUnicast, which Xray asks), an IPv4-mapped
     * IPv6 address read as IPv4. Read as strictly as the core reads an
     * address: text it would not take as one (a leading zero, a zone) is a
     * name to it, and not one it exempts. A server named by a hostname is
     * pinned to an address before this is asked
     * ([org.olcbox.app.net.core.CoreRefusal.HOSTNAME]).
     */
    private fun isPrivateServer(host: String): Boolean {
        val text = host.removeSurrounding("[", "]")
        ipv4(text)?.let { return isPrivateV4(it) }
        val v6 = ipv6(text) ?: return false
        if ((0 until 10).all { v6[it] == 0 } && v6[10] == 0xff && v6[11] == 0xff) return isPrivateV4(v6.subList(12, 16))
        val uniqueLocal = (v6[0] and 0xfe) == 0xfc
        val linkLocal = v6[0] == 0xfe && (v6[1] and 0xc0) == 0x80
        val loopback = (0 until 15).all { v6[it] == 0 } && v6[15] == 1
        return uniqueLocal || linkLocal || loopback
    }

    /** 10/8, 172.16/12, 192.168/16, 127/8 and 169.254/16. */
    private fun isPrivateV4(octets: List<Int>): Boolean {
        val (a, b) = octets
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || a == 127 || (a == 169 && b == 254)
    }

    /** Four decimal octets, none with a leading zero, or null. */
    private fun ipv4(text: String): List<Int>? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
            part.toInt().takeIf { it <= 255 } ?: return null
        }
    }

    /**
     * The sixteen bytes of an IPv6 address, or null: groups of one to four
     * hex digits, at most one `::` and standing for at least one group, an
     * IPv4 address only at the end.
     */
    private fun ipv6(text: String): List<Int>? {
        val gap = text.indexOf("::")
        if (gap >= 0 && gap != text.lastIndexOf("::")) return null
        if (gap < 0) return groups(text, ipv4Last = true)?.takeIf { it.size == 16 }
        val head = groups(text.substring(0, gap), ipv4Last = false) ?: return null
        val tail = groups(text.substring(gap + 2), ipv4Last = true) ?: return null
        val zeros = 16 - head.size - tail.size
        return if (zeros >= 2) head + List(zeros) { 0 } + tail else null
    }

    /** The bytes of colon-separated groups, the last of them an IPv4 address when [ipv4Last] lets it be. */
    private fun groups(text: String, ipv4Last: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        val bytes = mutableListOf<Int>()
        for ((i, part) in parts.withIndex()) {
            if (ipv4Last && i == parts.lastIndex && '.' in part) {
                bytes += ipv4(part) ?: return null
            } else {
                if (part.isEmpty() || part.length > 4 || !part.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                val group = part.toInt(16)
                bytes += group shr 8
                bytes += group and 0xff
            }
        }
        return bytes
    }

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec =
        CoreSupport.pinVless(spec as OutboundSpec.Vless, address)

    /**
     * `security: none` for a link with no TLS, whatever else the link says
     * of TLS. Otherwise ordinary TLS with the link's server name and hello,
     * and `allowInsecure` exactly when the link asks to waive the
     * certificate check (`allowInsecure` or `insecure`, as `1` or `true`).
     * No ALPN: the app reads a link's only for WebSocket and HTTP upgrade,
     * and the core offers what gRPC and XHTTP need (h2) itself.
     */
    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Vless
        putVless(spec)
        putJsonObject("streamSettings") {
            putTransport(spec.transport)
            if (spec.plain) {
                put("security", "none")
            } else {
                putTls(serverName = spec.sni, fingerprint = CoreSupport.helloName(spec.fingerprint), insecure = spec.insecure)
            }
        }
    }
}
