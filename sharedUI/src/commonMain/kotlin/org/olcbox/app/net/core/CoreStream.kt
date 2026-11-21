package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.XrayConfig

/**
 * What stands under an outbound's `streamSettings` and is no one kind's: the
 * transport a link names, ordinary TLS, and Reality. A kind's file calls
 * these in the order its outbound is written in; which links it gives them is
 * its own rule.
 */
object CoreStream {
    /**
     * Go's stream window for XHTTP on HTTP/2 on iOS (`XrayEngine.h2StreamWindow`,
     * 512 KiB), which the lab's iOS-limits rows were measured with. xray-rust
     * reads it as a fixed window per stream; unset, a constrained host opens at
     * 1 MiB and grows to 4 MiB.
     */
    const val XHTTP_H2_STREAM_RECEIVE_WINDOW = 524_288
}

/** `network` and the transport's own settings. */
internal fun JsonObjectBuilder.putTransport(transport: TransportSpec) {
    when (transport) {
        TransportSpec.Tcp -> put("network", "raw")
        is TransportSpec.Grpc -> {
            put("network", "grpc")
            putJsonObject("grpcSettings") { put("serviceName", transport.serviceName) }
        }
        is TransportSpec.Ws -> {
            put("network", "ws")
            putJsonObject("wsSettings") {
                put("path", transport.path)
                if (transport.host.isNotBlank()) put("host", transport.host)
            }
        }
        is TransportSpec.HttpUpgrade -> {
            put("network", "httpupgrade")
            putJsonObject("httpupgradeSettings") {
                put("path", transport.path)
                if (transport.host.isNotBlank()) put("host", transport.host)
            }
        }
        is TransportSpec.Xhttp -> {
            put("network", "xhttp")
            putJsonObject("xhttpSettings") {
                put("path", transport.path)
                put("host", transport.host)
                put("mode", transport.mode)
                // See XrayConfig.buildXhttp: what an uploading
                // connection buffers, the jetsam step on iOS.
                put("scMaxEachPostBytes", XrayConfig.XHTTP_MAX_EACH_POST_BYTES)
                put("h2StreamReceiveWindow", CoreStream.XHTTP_H2_STREAM_RECEIVE_WINDOW)
            }
        }
        is TransportSpec.Http -> error("no kind of line writes HTTP/2 (type=http) for the core")
    }
}

/**
 * `security: tls` and `tlsSettings`. [serverName] empty is the server's own
 * name. [fingerprint] is the hello's name, null where the core takes none
 * (its QUIC transports). [alpn] is the link's as it gives it, which Xray's own
 * client writes too: on WebSocket and HTTP upgrade the core keeps exactly
 * `h2, http/1.1` (a browser's offer, ProofKit's WebTunnel links) and turns any
 * other list, or none, into `http/1.1` alone, the fingerprint those links
 * avoid. [pin] is a certificate's SHA-256 as [CoreSupport.pin] spells it.
 */
internal fun JsonObjectBuilder.putTls(
    serverName: String,
    fingerprint: String? = null,
    alpn: List<String> = emptyList(),
    pin: String? = null,
) {
    put("security", "tls")
    putJsonObject("tlsSettings") {
        if (serverName.isNotBlank()) put("serverName", serverName)
        if (fingerprint != null) put("fingerprint", fingerprint)
        if (alpn.isNotEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
        if (pin != null) put("pinnedPeerCertSha256", pin)
    }
}

/** `security: reality` and `realitySettings`. */
internal fun JsonObjectBuilder.putReality(serverName: String, fingerprint: String, publicKey: String, shortId: String) {
    put("security", "reality")
    putJsonObject("realitySettings") {
        put("serverName", serverName)
        put("fingerprint", fingerprint)
        put("publicKey", publicKey)
        put("shortId", shortId)
    }
}
