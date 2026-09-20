package org.olcbox.app.net

import kotlinx.serialization.Serializable

@Serializable
enum class LocationKind { Olcrtc, Vless, Hysteria2 }

sealed interface TransportSpec {
    data object Tcp : TransportSpec
    data class Grpc(val serviceName: String) : TransportSpec
    data class Xhttp(val path: String, val host: String, val mode: String) : TransportSpec
}

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
}
