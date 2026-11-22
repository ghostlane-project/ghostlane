package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.putTls

/** Hysteria2, with or without Salamander. */
internal object Hysteria2 : CoreLine {
    const val SLUG = "hysteria2"

    override val shapes: List<String> = listOf(SLUG)

    override fun shape(spec: OutboundSpec): String? = if (spec is OutboundSpec.Hysteria2) SLUG else null

    /**
     * Refused: a pin that is not a SHA-256 in hex, a link asked to skip
     * verification with no pin to verify instead, a password the core will
     * not send, and a Salamander password outside 4 to 4096 bytes.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Hysteria2
        val auth = spec.password
        val obfs = spec.obfsPassword
        return when {
            spec.certPinSha256 != null && CoreSupport.pin(spec.certPinSha256) == null ->
                "the certificate pin is not a SHA-256 in hex"
            spec.insecure && spec.certPinSha256 == null ->
                "the link waives certificate checks with no pin, which the core does not do"
            auth.isEmpty() || auth.encodeToByteArray().size > 4096 || auth.any { it < ' ' || it == '\u007f' } ->
                "a Hysteria2 password the core does not send"
            obfs != null && obfs.encodeToByteArray().size !in 4..4096 ->
                "a Salamander password outside 4 to 4096 bytes"
            else -> null
        }
    }

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec {
        spec as OutboundSpec.Hysteria2
        val name = spec.host
        return spec.copy(host = address, sni = spec.sni.ifBlank { name })
    }

    /**
     * Hysteria2 is the core's `hysteria` protocol at version 2, over its own
     * QUIC transport with TLS. No fingerprint (the core refuses one here) and
     * no ALPN (h3 is its default). A published pin is verified: sing-box
     * cannot pin and skips verification for such a server, the core checks
     * the certificate against the pin instead.
     *
     * Under Salamander, ChromeParrot is off (`quicParams.disableChromeParrot`):
     * the parrot shapes the QUIC handshake like Chrome's and with it replaces
     * the core's receive windows by Chrome's 6 and 15 MiB, while Salamander
     * hides that handshake on the wire, so it would cost the extension memory
     * and hide nothing. Every ProofKit Hysteria2 line carries Salamander.
     * Without it the hello is visible, and the parrot stays on.
     */
    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Hysteria2
        put("protocol", "hysteria")
        putJsonObject("settings") {
            put("version", 2)
            put("address", spec.host); put("port", spec.port)
        }
        putJsonObject("streamSettings") {
            put("network", "hysteria")
            putTls(serverName = spec.sni, pin = spec.certPinSha256?.let { requireNotNull(CoreSupport.pin(it)) })
            putJsonObject("hysteriaSettings") {
                put("version", 2)
                put("auth", spec.password)
            }
            spec.obfsPassword?.let { obfs ->
                putJsonObject("finalmask") {
                    putJsonArray("udp") {
                        addJsonObject {
                            put("type", "salamander")
                            putJsonObject("settings") { put("password", obfs) }
                        }
                    }
                    putJsonObject("quicParams") { put("disableChromeParrot", true) }
                }
            }
        }
    }
}
