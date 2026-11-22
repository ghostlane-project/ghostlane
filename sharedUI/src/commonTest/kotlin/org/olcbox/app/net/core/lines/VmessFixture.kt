package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * VMess AEAD by each of its eight shapes, as v2rayN's share link writes
 * them (numbers as strings), with each of the core's three body ciphers and
 * the hellos a link names (none, which is chrome; chrome; firefox).
 */
object VmessFixture : CoreLineFixture {
    override val line: CoreLine = Vmess

    /** The user's id, made up. */
    const val ID = "44444444-4444-4444-4444-444444444444"

    /** The server's name, for TLS and the Host of WebSocket and HTTP upgrade. */
    const val NAME = "vm.example.com"

    /** A share link of [fields] beside the server, its port, the id and alterId 0. */
    @OptIn(ExperimentalEncodingApi::class)
    fun link(vararg fields: Pair<String, String>): String {
        val json = buildJsonObject {
            put("v", "2")
            put("add", "127.0.0.1"); put("port", "10086")
            put("id", ID); put("aid", "0")
            for ((key, value) in fields) put(key, value)
        }
        return "vmess://" + Base64.Default.encode(json.toString().encodeToByteArray())
    }

    private val tls = arrayOf("tls" to "tls", "sni" to NAME)

    override val cases: List<CoreCase> = listOf(
        CoreCase(Vmess.RAW, link("ps" to "vm-raw", "scy" to "auto", "net" to "tcp", "type" to "none")),
        CoreCase(Vmess.WS, link("ps" to "vm-ws", "scy" to "aes-128-gcm", "net" to "ws", "host" to NAME, "path" to "/vm")),
        CoreCase(Vmess.GRPC, link("ps" to "vm-grpc", "scy" to "chacha20-poly1305", "net" to "grpc", "path" to "vm-grpc")),
        CoreCase(Vmess.HTTP_UPGRADE, link("ps" to "vm-up", "scy" to "auto", "net" to "httpupgrade", "host" to NAME, "path" to "/vu")),
        CoreCase(Vmess.RAW_TLS, link("ps" to "vm-raw-tls", "scy" to "auto", "net" to "tcp", *tls)),
        CoreCase(
            Vmess.WS_TLS,
            link("ps" to "vm-ws-tls", "scy" to "aes-128-gcm", "net" to "ws", "host" to NAME, "path" to "/vm", *tls, "fp" to "chrome", "alpn" to "h2,http/1.1"),
        ),
        CoreCase(
            Vmess.GRPC_TLS,
            link("ps" to "vm-grpc-tls", "scy" to "chacha20-poly1305", "net" to "grpc", "path" to "vm-grpc", *tls, "fp" to "chrome", "alpn" to "h2"),
        ),
        CoreCase(
            Vmess.HTTP_UPGRADE_TLS,
            link("ps" to "vm-up-tls", "scy" to "auto", "net" to "httpupgrade", "host" to NAME, "path" to "/vu", *tls, "fp" to "firefox"),
        ),
    )
}
