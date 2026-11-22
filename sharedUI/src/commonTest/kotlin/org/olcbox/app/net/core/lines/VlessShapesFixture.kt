package org.olcbox.app.net.core.lines

import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/**
 * VLESS with no Reality key, a link for each shape: ordinary TLS on raw TCP
 * (also with Vision), gRPC and XHTTP, each also with the certificate check
 * waived by the link, and no TLS on raw TCP, WebSocket, gRPC, HTTP upgrade
 * and XHTTP.
 */
object VlessShapesFixture : CoreLineFixture {
    private const val TLS = "vless://44444444-4444-4444-4444-444444444444@127.0.0.1:443" +
        "?security=tls&encryption=none&sni=vless.example.com&fp=chrome"
    // A loopback server: the core sends VLESS with no TLS only to a private, loopback or link-local one.
    private const val NONE = "vless://55555555-5555-5555-5555-555555555555@127.0.0.1:80?security=none&encryption=none"

    override val line: CoreLine = VlessShapes

    override val cases: List<CoreCase> = listOf(
        CoreCase("vless-tls", "$TLS&type=tcp#vless-tls"),
        CoreCase("vless-tls-vision", "$TLS&flow=xtls-rprx-vision&type=tcp#vless-tls-vision"),
        CoreCase("vless-tls-insecure", "$TLS&allowInsecure=1&type=tcp#vless-tls-insecure"),
        CoreCase("vless-tls-grpc", "$TLS&type=grpc&serviceName=media%2Fsync#vless-tls-grpc"),
        CoreCase("vless-tls-grpc-insecure", "$TLS&allowInsecure=1&type=grpc&serviceName=media%2Fsync#vless-tls-grpc-insecure"),
        // No host and no mode: the server name is the Host, and the mode is auto.
        CoreCase("vless-tls-xhttp", "$TLS&type=xhttp&path=%2Fx#vless-tls-xhttp"),
        CoreCase("vless-tls-xhttp-insecure", "$TLS&allowInsecure=1&type=xhttp&path=%2Fx#vless-tls-xhttp-insecure"),
        CoreCase("vless-none", "$NONE&type=tcp#vless-none"),
        CoreCase("vless-none-ws", "$NONE&type=ws&path=%2Fp&host=cdn.example.com#vless-none-ws"),
        CoreCase("vless-none-grpc", "$NONE&type=grpc&serviceName=media%2Fsync#vless-none-grpc"),
        CoreCase("vless-none-httpupgrade", "$NONE&type=httpupgrade&path=%2Fu&host=cdn.example.com#vless-none-httpupgrade"),
        CoreCase("vless-none-xhttp", "$NONE&type=xhttp&path=%2Fx&host=cdn.example.com&mode=packet-up#vless-none-xhttp"),
    )
}
