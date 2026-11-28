package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.putReality
import org.olcbox.app.net.core.putTls
import org.olcbox.app.net.core.putVless

/**
 * VLESS over HTTP/2: a link with `type=http` (or `h2`, as older links spell
 * it), over ordinary TLS or over Reality. sing-box and v2fly serve this
 * transport; Xray removed it. The core reads `network: "http"` with
 * `httpSettings` (the hosts and the path) and runs it as XHTTP in stream-one
 * mode, one HTTP/2 request whose body is the uplink and whose answer is the
 * downlink, offering `h2` as the only ALPN.
 *
 * With no TLS (`security=none`) the link is this kind's and is refused
 * ([NEEDS_TLS]): HTTP/2 over a clear connection is no line anyone serves.
 * A link with Vision is refused too ([NO_FLOW]): Vision runs on raw TCP only.
 */
internal object VlessHttp2 : CoreLine {
    const val TLS = "vless-http2-tls"
    const val REALITY = "vless-http2-reality"

    /** The shape of a link over HTTP/2 with no TLS: this kind's, always refused, so no config is written for it. */
    const val NONE = "vless-http2-none"

    /** Why a link over HTTP/2 with no TLS is refused. */
    const val NEEDS_TLS =
        "the link asks for HTTP/2 (type=http) with no TLS (security=none), and the core carries HTTP/2 only inside TLS or Reality"

    /** Why a link over HTTP/2 with a flow is refused. */
    const val NO_FLOW = "the link asks for Vision over HTTP/2 (type=http), and Vision runs only on raw TCP"

    override val shapes: List<String> = listOf(TLS, REALITY)

    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Vless || spec.transport !is TransportSpec.Http) return null
        return when {
            spec.publicKey.isNotBlank() -> REALITY
            spec.plain -> NONE
            else -> TLS
        }
    }

    /**
     * Refused: no TLS ([NEEDS_TLS]), a flow ([NO_FLOW]), and for a Reality
     * line what [Reality] refuses (waived certificate checks, no server
     * name). A link over TLS that waives the certificate check is carried:
     * the waiver is the link's own.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Vless
        val reality = spec.publicKey.isNotBlank()
        return when {
            spec.plain && !reality -> NEEDS_TLS
            spec.flow != null -> NO_FLOW
            reality && spec.insecure -> CoreRefusal.WAIVED_CHECKS
            reality && spec.sni.isBlank() -> "a Reality line with no server name"
            else -> null
        }
    }

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec =
        CoreSupport.pinVless(spec as OutboundSpec.Vless, address)

    /**
     * `network: "http"` and `httpSettings` (`host`, the link's hosts, when it
     * names any, and `path`), then ordinary TLS with the link's server name
     * and hello (and `allowInsecure` when the link waives the check), or
     * Reality. No ALPN: the core offers `h2` for this transport itself.
     */
    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Vless
        val http = spec.transport as TransportSpec.Http
        putVless(spec)
        putJsonObject("streamSettings") {
            put("network", "http")
            putJsonObject("httpSettings") {
                if (http.hosts.isNotEmpty()) putJsonArray("host") { http.hosts.forEach { add(it) } }
                put("path", http.path)
            }
            if (spec.publicKey.isNotBlank()) {
                putReality(
                    serverName = spec.sni,
                    fingerprint = Reality.hello(spec.fingerprint),
                    publicKey = spec.publicKey,
                    shortId = spec.shortId,
                )
            } else {
                putTls(serverName = spec.sni, fingerprint = CoreSupport.helloName(spec.fingerprint), insecure = spec.insecure)
            }
        }
    }
}
