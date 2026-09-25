package org.olcbox.app.net

import kotlinx.serialization.Serializable

@Serializable
enum class LocationKind { Olcrtc, Vless, Hysteria2, Trojan, Shadowsocks, Vmess }

sealed interface TransportSpec {
    data object Tcp : TransportSpec
    data class Grpc(val serviceName: String) : TransportSpec
    data class Xhttp(val path: String, val host: String, val mode: String) : TransportSpec
    /** WebSocket (`type=ws`); [host] is the Host header, empty for the server's own name. */
    data class Ws(val path: String, val host: String) : TransportSpec
    /** HTTP upgrade (`type=httpupgrade`), the lighter WebSocket handshake. */
    data class HttpUpgrade(val path: String, val host: String) : TransportSpec
}

/** Ordinary TLS as a Trojan or VMess link spells it (`sni`, `allowInsecure`, `fp`, `alpn`). */
data class TlsSpec(
    val sni: String,
    val insecure: Boolean = false,
    /** uTLS fingerprint; null leaves the core's own ClientHello. */
    val fingerprint: String? = null,
    val alpn: List<String> = emptyList(),
)

sealed interface OutboundSpec {
    val host: String
    val port: Int
    val tag: String

    data class Vless(
        val uuid: String,
        override val host: String,
        override val port: Int,
        val sni: String,
        val publicKey: String,   // Reality pbk
        val shortId: String,     // Reality sid
        val fingerprint: String, // fp, default "chrome"
        val flow: String?,       // e.g. xtls-rprx-vision; null for xhttp
        val transport: TransportSpec,
        override val tag: String,
    ) : OutboundSpec

    data class Hysteria2(
        val password: String,
        override val host: String,
        override val port: Int,
        val sni: String,
        val obfsPassword: String?, // Salamander
        val insecure: Boolean,
        override val tag: String,
        /**
         * Operator-published certificate fingerprint, present when the server runs a
         * self-signed certificate. Last and defaulted so existing positional
         * construction keeps compiling.
         */
        val certPinSha256: String? = null,
    ) : OutboundSpec

    data class Trojan(
        val password: String,
        override val host: String,
        override val port: Int,
        val tls: TlsSpec,
        val transport: TransportSpec,
        override val tag: String,
    ) : OutboundSpec

    /** SIP002 Shadowsocks: an AEAD or 2022 [method] (see [Shadowsocks.METHODS]), no plugin. */
    data class Shadowsocks(
        val method: String,
        val password: String,
        override val host: String,
        override val port: Int,
        override val tag: String,
    ) : OutboundSpec {
        companion object {
            /** What sing-box 1.13 speaks and providers still hand out; the stream ciphers are gone. */
            val METHODS = setOf(
                "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
                "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
                "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            )
        }
    }

    /** v2rayN's VMess share link (base64 JSON); [tls] null means plain. */
    data class Vmess(
        val uuid: String,
        override val host: String,
        override val port: Int,
        val alterId: Int,
        val security: String,
        val tls: TlsSpec?,
        val transport: TransportSpec,
        override val tag: String,
    ) : OutboundSpec
}
