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
 * VMess AEAD, as v2rayN's share link gives it: over raw TCP, WebSocket, gRPC
 * or HTTP upgrade, each with ordinary TLS or with none. VMess encrypts its
 * own body, so the core takes it without TLS to any address, which it
 * does not for VLESS.
 */
internal object Vmess : CoreLine {
    const val RAW = "vmess-raw"
    const val WS = "vmess-ws"
    const val GRPC = "vmess-grpc"
    const val HTTP_UPGRADE = "vmess-httpupgrade"
    const val RAW_TLS = "vmess-raw-tls"
    const val WS_TLS = "vmess-ws-tls"
    const val GRPC_TLS = "vmess-grpc-tls"
    const val HTTP_UPGRADE_TLS = "vmess-httpupgrade-tls"

    /** The body ciphers the core speaks: `auto` is AES-128-GCM where the CPU has AES, else ChaCha20-Poly1305. */
    val CIPHERS = setOf("auto", "aes-128-gcm", "chacha20-poly1305")

    const val LEGACY = "a legacy VMess line (alterId above 0), which the core does not speak: it speaks VMess AEAD alone"
    const val CIPHER =
        "a VMess body cipher other than auto, aes-128-gcm and chacha20-poly1305 (none, zero or a legacy one), which the core does not speak"
    const val ID = "a VMess id that is neither a UUID nor 1 to 30 bytes of text, which the core does not read"
    const val PORT = "a port outside 1 to 65535"

    override val shapes: List<String> = listOf(RAW, WS, GRPC, HTTP_UPGRADE, RAW_TLS, WS_TLS, GRPC_TLS, HTTP_UPGRADE_TLS)

    /**
     * By the transport and whether there is TLS. VMess over HTTP/2
     * (`net=h2` or `http`) is no shape of this kind: Xray removed that
     * transport for XHTTP, and the core refuses it.
     */
    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Vmess) return null
        val tls = spec.tls != null
        return when (spec.transport) {
            TransportSpec.Tcp -> if (tls) RAW_TLS else RAW
            is TransportSpec.Ws -> if (tls) WS_TLS else WS
            is TransportSpec.Grpc -> if (tls) GRPC_TLS else GRPC
            is TransportSpec.HttpUpgrade -> if (tls) HTTP_UPGRADE_TLS else HTTP_UPGRADE
            else -> null
        }
    }

    /**
     * Refused: legacy VMess (an alterId above 0), a body cipher the core
     * does not speak, an id the core cannot read as one (Xray's own rule:
     * a UUID, or 1 to 30 bytes of text it hashes into one), and a port that
     * is none. A link that waives certificate checks is carried: the core
     * honours `allowInsecure` on VMess and warns, naming the outbound.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Vmess
        return when {
            spec.alterId > 0 -> LEGACY
            cipher(spec) !in CIPHERS -> CIPHER
            !readsId(spec.uuid) -> ID
            spec.port !in 1..65535 -> PORT
            else -> null
        }
    }

    /**
     * The name stays the TLS server name and the Host of WebSocket and HTTP
     * upgrade where the link left them to be the server's own.
     */
    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec {
        spec as OutboundSpec.Vmess
        val name = spec.host
        return spec.copy(
            host = address,
            tls = spec.tls?.let { it.copy(sni = it.sni.ifBlank { name }) },
            transport = when (val t = spec.transport) {
                is TransportSpec.Ws -> t.copy(host = t.host.ifBlank { name })
                is TransportSpec.HttpUpgrade -> t.copy(host = t.host.ifBlank { name })
                else -> t
            },
        )
    }

    /**
     * One server and one user, with no alterId (the core reads only zero)
     * and the link's cipher. TLS is the link's: its server name, its `fp`
     * read as the core names hellos, its ALPN as it gives it, and
     * `allowInsecure` only when the link waives the checks.
     */
    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Vmess
        put("protocol", "vmess")
        putJsonObject("settings") {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", spec.host); put("port", spec.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", spec.uuid)
                            put("security", cipher(spec))
                        }
                    }
                }
            }
        }
        putJsonObject("streamSettings") {
            putTransport(spec.transport)
            spec.tls?.let { tls ->
                putTls(
                    serverName = tls.sni,
                    fingerprint = CoreSupport.helloName(tls.fingerprint),
                    alpn = tls.alpn,
                    insecure = tls.insecure,
                )
            }
        }
    }

    /** The link's `scy` as the core reads it: trimmed, lower case, and empty as `auto`. */
    private fun cipher(spec: OutboundSpec.Vmess): String = spec.security.trim().lowercase().ifEmpty { "auto" }

    private val ID_GROUPS = intArrayOf(8, 4, 4, 4, 12)

    /**
     * Whether the core reads [id] as a user id, as Xray-core's
     * `uuid.ParseString` does: by its length in bytes, 1 to 30 of any text,
     * or 32 to 36 that are hex digits in groups of 8, 4, 4, 4 and 12, each
     * after at most one `-`, with whatever follows the last group ignored.
     */
    internal fun readsId(id: String): Boolean {
        val bytes = id.encodeToByteArray()
        if (bytes.size in 1..30) return true
        if (bytes.size !in 32..36) return false
        var at = 0
        for (group in ID_GROUPS) {
            if (at < bytes.size && bytes[at] == '-'.code.toByte()) at++
            if (at + group > bytes.size) return false
            for (i in at until at + group) {
                val c = bytes[i].toInt()
                if (c !in '0'.code..'9'.code && c !in 'a'.code..'f'.code && c !in 'A'.code..'F'.code) return false
            }
            at += group
        }
        return true
    }
}
