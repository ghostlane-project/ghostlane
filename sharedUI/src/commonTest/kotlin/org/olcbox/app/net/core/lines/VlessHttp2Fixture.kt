package org.olcbox.app.net.core.lines

import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/**
 * VLESS over HTTP/2 (`type=http`), a link for each shape: over ordinary TLS
 * with a host, and over Reality with none.
 */
object VlessHttp2Fixture : CoreLineFixture {
    override val line: CoreLine = VlessHttp2

    override val cases: List<CoreCase> = listOf(
        CoreCase(
            "vless-http2-tls",
            "vless://44444444-4444-4444-4444-444444444444@127.0.0.1:443" +
                "?security=tls&encryption=none&sni=vless.example.com&fp=chrome&type=http&path=%2Fh2&host=h.example.com#vless-http2-tls",
        ),
        CoreCase(
            "vless-http2-reality",
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443?security=reality&encryption=none" +
                "&pbk=${CoreFixtures.PUBLIC_KEY}&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&type=http&path=%2Fh2#vless-http2-reality",
        ),
    )
}
