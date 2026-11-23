package org.olcbox.app.net.core.lines

import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/**
 * Trojan's four transports, each by a link as providers hand them out, and
 * the raw line once more with its certificate checks waived
 * (`allowInsecure=1`), which is carried with the flag.
 */
object TrojanFixture : CoreLineFixture {
    private const val PASSWORD = "trojan-fixture-password"
    private const val SERVER = "trojan://$PASSWORD@127.0.0.1:443?security=tls&sni=trojan.example.com"

    override val line: CoreLine = Trojan

    override val cases: List<CoreCase> = listOf(
        CoreCase("trojan", "$SERVER&fp=chrome&type=tcp#trojan"),
        CoreCase("trojan-ws", "$SERVER&fp=chrome&type=ws&path=%2Ftr&host=cdn.example.com&alpn=http%2F1.1#trojan-ws"),
        CoreCase("trojan-grpc", "$SERVER&fp=firefox&type=grpc&serviceName=tr.grpc#trojan-grpc"),
        CoreCase("trojan-httpupgrade", "$SERVER&type=httpupgrade&path=%2Fup&host=trojan.example.com#trojan-httpupgrade"),
        CoreCase("trojan-insecure", "$SERVER&fp=chrome&allowInsecure=1&type=tcp#trojan-insecure"),
    )
}
