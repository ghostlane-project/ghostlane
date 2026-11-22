package org.olcbox.app.net.core.lines

import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/**
 * Shadowsocks by every cipher the core speaks, as the app's links write them:
 * the four AEAD ciphers, and the three 2022 ones with made-up keys of the
 * length each takes.
 */
object ShadowsocksFixture : CoreLineFixture {
    override val line: CoreLine = Shadowsocks

    /** A 16-byte key and a 32-byte key in standard base64, made up. */
    const val KEY_16 = "AAECAwQFBgcICQoLDA0ODw=="
    const val KEY_32 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    private fun link(method: String, password: String, tag: String) =
        "ss://$method:${password.replace("=", "%3D")}@192.0.2.1:8388#$tag"

    override val cases: List<CoreCase> = listOf(
        CoreCase("shadowsocks-aes-128-gcm", link("aes-128-gcm", "pw-aes-128", "ss-aes-128-gcm")),
        CoreCase("shadowsocks-aes-256-gcm", link("aes-256-gcm", "pw-aes-256", "ss-aes-256-gcm")),
        CoreCase("shadowsocks-chacha20-poly1305", link("chacha20-ietf-poly1305", "pw-chacha", "ss-chacha20")),
        CoreCase("shadowsocks-xchacha20-poly1305", link("xchacha20-ietf-poly1305", "pw-xchacha", "ss-xchacha20")),
        CoreCase("shadowsocks-2022-aes-128-gcm", link("2022-blake3-aes-128-gcm", KEY_16, "ss2022-aes-128")),
        CoreCase("shadowsocks-2022-aes-256-gcm", link("2022-blake3-aes-256-gcm", KEY_32, "ss2022-aes-256")),
        CoreCase("shadowsocks-2022-chacha20-poly1305", link("2022-blake3-chacha20-poly1305", KEY_32, "ss2022-chacha")),
    )
}
