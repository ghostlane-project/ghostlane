package org.olcbox.app.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trojan, Shadowsocks and VMess share links as providers hand them out, and the
 * WebSocket / HTTP upgrade transports on VLESS. A link that cannot work is
 * refused (null), never turned into a row that dials something else.
 */
@OptIn(ExperimentalEncodingApi::class)
class LinkParserProtocolsTest {

    @Test fun trojanOverWebSocketWithEverythingSpelledOut() {
        val spec = LinkParser.parse(
            "trojan://p%40ss@example.com:443?security=tls&sni=sni.example.com&type=ws&path=%2Fws" +
                "&host=cdn.example.com&fp=chrome&alpn=h2%2Chttp%2F1.1&allowInsecure=1#Trojan%20WS"
        )
        assertIs<OutboundSpec.Trojan>(spec)
        assertEquals("p@ss", spec.password)
        assertEquals("example.com", spec.host)
        assertEquals(443, spec.port)
        assertEquals(TlsSpec("sni.example.com", insecure = true, fingerprint = "chrome", alpn = listOf("h2", "http/1.1")), spec.tls)
        assertEquals(TransportSpec.Ws(path = "/ws", host = "cdn.example.com"), spec.transport)
        assertEquals("Trojan WS", spec.tag)
    }

    @Test fun aBareTrojanLinkIsTlsToItsOwnHost() {
        val spec = LinkParser.parse("trojan://secret@1.2.3.4:443#T")
        assertIs<OutboundSpec.Trojan>(spec)
        assertEquals(TlsSpec("1.2.3.4"), spec.tls)
        assertEquals(TransportSpec.Tcp, spec.transport)
    }

    @Test fun trojanItCannotSpeakIsRefused() {
        assertNull(LinkParser.parse("trojan://secret@1.2.3.4:443?security=reality&pbk=x#T"))
        assertNull(LinkParser.parse("trojan://secret@1.2.3.4:443?type=kcp#T"))
        assertNull(LinkParser.parse("trojan://@1.2.3.4:443#T"))
    }

    @Test fun shadowsocksSip002WithABase64Userinfo() {
        val userinfo = Base64.UrlSafe.encode("aes-256-gcm:secret".encodeToByteArray()).trimEnd('=')
        val spec = LinkParser.parse("ss://$userinfo@1.2.3.4:8388#SS%20one")
        assertEquals(OutboundSpec.Shadowsocks("aes-256-gcm", "secret", "1.2.3.4", 8388, "SS one"), spec)
    }

    @Test fun shadowsocks2022WithAPlainUserinfo() {
        val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val spec = LinkParser.parse("ss://2022-blake3-aes-256-gcm:${key.replace("=", "%3D")}@[2001:db8::1]:8388/#SS2022")
        assertEquals(OutboundSpec.Shadowsocks("2022-blake3-aes-256-gcm", key, "2001:db8::1", 8388, "SS2022"), spec)
    }

    @Test fun shadowsocksTheLegacyWholeBase64Way() {
        val body = Base64.Default.encode("chacha20-ietf-poly1305:pw@5.6.7.8:443".encodeToByteArray())
        assertEquals(
            OutboundSpec.Shadowsocks("chacha20-ietf-poly1305", "pw", "5.6.7.8", 443, "Legacy"),
            LinkParser.parse("ss://$body#Legacy")
        )
    }

    @Test fun shadowsocksWithAPluginOrAStreamCipherIsRefused() {
        val userinfo = Base64.UrlSafe.encode("aes-128-gcm:pw".encodeToByteArray())
        assertNull(LinkParser.parse("ss://$userinfo@1.2.3.4:8388/?plugin=obfs-local%3Bobfs%3Dhttp#P"))
        val rc4 = Base64.UrlSafe.encode("rc4-md5:pw".encodeToByteArray())
        assertNull(LinkParser.parse("ss://$rc4@1.2.3.4:8388#Old"))
    }

    private fun vmess(json: String) = "vmess://" + Base64.Default.encode(json.encodeToByteArray())

    @Test fun vmessInV2rayNsShapeWithStringNumbers() {
        val spec = LinkParser.parse(
            vmess(
                """{"v":"2","ps":"VM WS","add":"1.2.3.4","port":"443","id":"11111111-1111-1111-1111-111111111111",""" +
                    """"aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/vm",""" +
                    """"tls":"tls","sni":"cdn.example.com","alpn":"h2","fp":"chrome"}"""
            )
        )
        assertIs<OutboundSpec.Vmess>(spec)
        assertEquals("1.2.3.4", spec.host)
        assertEquals(443, spec.port)
        assertEquals(0, spec.alterId)
        assertEquals("auto", spec.security)
        assertEquals(TransportSpec.Ws("/vm", "cdn.example.com"), spec.transport)
        assertEquals(TlsSpec("cdn.example.com", fingerprint = "chrome", alpn = listOf("h2")), spec.tls)
        assertEquals("VM WS", spec.tag)
    }

    @Test fun vmessWithNumbersAndNoTlsAndGrpc() {
        val plain = LinkParser.parse(vmess("""{"add":"h.example","port":8080,"id":"u","aid":2,"net":"tcp","ps":"P"}"""))
        assertIs<OutboundSpec.Vmess>(plain)
        assertEquals(8080, plain.port)
        assertEquals(2, plain.alterId)
        assertNull(plain.tls)
        val grpc = LinkParser.parse(vmess("""{"add":"h.example","port":443,"id":"u","net":"grpc","path":"svc","tls":"tls"}"""))
        assertIs<OutboundSpec.Vmess>(grpc)
        assertEquals(TransportSpec.Grpc("svc"), grpc.transport)
    }

    @Test fun vmessItCannotSpeakIsRefused() {
        assertNull(LinkParser.parse(vmess("""{"add":"h","port":443,"id":"u","net":"tcp","type":"http"}""")))
        assertNull(LinkParser.parse(vmess("""{"add":"h","port":443,"id":"u","net":"kcp"}""")))
        assertNull(LinkParser.parse(vmess("""{"add":"h","id":"u"}""")))
        assertNull(LinkParser.parse("vmess://not-base64-json"))
    }

    // VLESS over WebSocket used to fall through to TCP and connect to nothing.
    @Test fun vlessOverWebSocketAndHttpUpgrade() {
        val ws = LinkParser.parse("vless://u@1.2.3.4:443?security=tls&sni=s.example&type=ws&path=%2Fv&host=h.example#V")
        assertIs<OutboundSpec.Vless>(ws)
        assertEquals(TransportSpec.Ws("/v", "h.example"), ws.transport)
        val up = LinkParser.parse("vless://u@1.2.3.4:443?security=tls&sni=s.example&type=httpupgrade&path=%2Fu#V")
        assertIs<OutboundSpec.Vless>(up)
        assertEquals(TransportSpec.HttpUpgrade("/u", ""), up.transport)
    }

    @Test fun theImporterTakesEveryScheme() {
        listOf("vless://x", "hysteria2://x", "hy2://x", "trojan://x", "ss://x", "vmess://x").forEach {
            assertTrue(LinkParser.supports(it), it)
        }
        assertTrue(!LinkParser.supports("socks://x"))
    }
}
