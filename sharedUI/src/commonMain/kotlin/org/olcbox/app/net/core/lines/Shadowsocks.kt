package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.core.CoreLine

/**
 * Shadowsocks over TCP and UDP: the AEAD ciphers (SIP004) and the 2022 ones
 * (SIP022), each its own shape. No plugin (the link parser refuses one) and
 * no stream cipher.
 */
internal object Shadowsocks : CoreLine {
    const val AEAD = "shadowsocks"
    const val AEAD_2022 = "shadowsocks-2022"

    override val shapes: List<String> = listOf(AEAD, AEAD_2022)

    /** The AEAD methods the core takes, by the names the app's links use. */
    private val AEAD_METHODS = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
    )

    /** The 2022 methods and the length in bytes of the key each takes. */
    private val KEY_BYTES = mapOf(
        "2022-blake3-aes-128-gcm" to 16,
        "2022-blake3-aes-256-gcm" to 32,
        "2022-blake3-chacha20-poly1305" to 32,
    )

    /** The core reads at most this many identity keys, and a password of at most this many bytes. */
    private const val MAX_KEYS = 8
    private const val MAX_PASSWORD_BYTES = 8192

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun method(spec: OutboundSpec.Shadowsocks): String = spec.method.trim().lowercase()

    override fun shape(spec: OutboundSpec): String? {
        if (spec !is OutboundSpec.Shadowsocks) return null
        return if (method(spec).startsWith("2022-")) AEAD_2022 else AEAD
    }

    /**
     * Refused: a method the core does not speak (the stream ciphers, which
     * the link parser also leaves out, and aes-192-gcm, which sing-box has and
     * the core lacks), and a 2022 key the core would not take: not standard
     * base64, not the cipher's length, more than one key where the cipher
     * takes one, or more than eight. The key's own bytes are never quoted.
     */
    override fun refusal(spec: OutboundSpec): String? {
        spec as OutboundSpec.Shadowsocks
        val method = method(spec)
        return when {
            method in AEAD_METHODS -> if (spec.password.isEmpty()) "a Shadowsocks password the core does not send" else null
            method == "aes-192-gcm" -> "the Shadowsocks method aes-192-gcm, which the core does not speak"
            method in KEY_BYTES -> keyRefusal(method, spec.password)
            else -> "the Shadowsocks cipher $method is a stream cipher or one the core does not speak: it speaks the AEAD ciphers and the 2022 ones"
        }
    }

    private fun keyRefusal(method: String, password: String): String? {
        if (password.isEmpty() || password.encodeToByteArray().size > MAX_PASSWORD_BYTES) {
            return "a Shadowsocks 2022 key the core does not take: empty or over $MAX_PASSWORD_BYTES bytes"
        }
        val keys = password.split(':')
        val want = KEY_BYTES.getValue(method)
        return when {
            keys.size > MAX_KEYS -> "a Shadowsocks 2022 password of more than $MAX_KEYS identity keys"
            method == "2022-blake3-chacha20-poly1305" && keys.size != 1 -> "a Shadowsocks 2022 ChaCha20 password with identity keys, which that cipher does not take"
            keys.any { decodedBytes(it) != want } -> "a Shadowsocks 2022 key that is not standard base64 of $want bytes, as $method takes"
            else -> null
        }
    }

    /**
     * The length in bytes of [key] read as standard base64 with its padding
     * and no stray bits, as the core reads it, or -1 when it is not that.
     */
    private fun decodedBytes(key: String): Int {
        if (key.isEmpty() || key.length % 4 != 0) return -1
        val pad = key.length - key.trimEnd('=').length
        if (pad > 2) return -1
        val body = key.substring(0, key.length - pad)
        if (body.any { it !in ALPHABET }) return -1
        val spare = when (pad) {
            1 -> 2
            2 -> 4
            else -> 0
        }
        if (spare > 0 && (ALPHABET.indexOf(body.last()) and ((1 shl spare) - 1)) != 0) return -1
        return key.length / 4 * 3 - pad
    }

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec =
        (spec as OutboundSpec.Shadowsocks).copy(host = address)

    /**
     * The core's `shadowsocks` protocol with one flattened server, over the
     * core's default stream (raw TCP, no TLS). Both families take the same
     * four fields; the core tells them apart by the method's name.
     */
    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        spec as OutboundSpec.Shadowsocks
        put("protocol", "shadowsocks")
        putJsonObject("settings") {
            put("address", spec.host); put("port", spec.port)
            put("method", method(spec)); put("password", spec.password)
        }
    }
}
