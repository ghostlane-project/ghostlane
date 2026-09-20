package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LinkParserTest {
    @Test fun parsesVlessReality() {
        val link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
            "?security=reality&encryption=none&pbk=PUBKEY&sid=ab12&fp=chrome&sni=www.example.com&flow=xtls-rprx-vision&type=tcp#DE"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(s)
        assertEquals("11111111-1111-1111-1111-111111111111", s.uuid)
        assertEquals("1.2.3.4", s.host); assertEquals(443, s.port)
        assertEquals("www.example.com", s.sni)
        assertEquals("PUBKEY", s.publicKey); assertEquals("ab12", s.shortId)
        assertEquals("xtls-rprx-vision", s.flow)
        assertEquals(TransportSpec.Tcp, s.transport)
        assertEquals("DE", s.tag)
    }

    @Test fun parsesVlessXhttp() {
        val link = "vless://22222222-2222-2222-2222-222222222222@1.2.3.4:443" +
            "?type=xhttp&security=reality&encryption=none&pbk=PBK&sid=cd34&fp=chrome&sni=sni.example&path=%2Fdownload&host=sni.example&mode=packet-up#FI"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(s)
        val t = s.transport
        assertIs<TransportSpec.Xhttp>(t)
        assertEquals("/download", t.path)
        assertEquals("sni.example", t.host)
        assertEquals("packet-up", t.mode)
        assertNull(s.flow) // xhttp is incompatible with flow
    }

    @Test fun parsesVlessGrpcAndDropsTcpOnlyFlow() {
        val link = "vless://33333333-3333-3333-3333-333333333333@grpc.example:2053" +
            "?type=grpc&serviceName=rutube&security=reality&pbk=PBK&flow=xtls-rprx-vision#KZ"
        val spec = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(spec)
        assertEquals(TransportSpec.Grpc("rutube"), spec.transport)
        assertNull(spec.flow)
    }

    @Test fun emptyTransportMeansTcpButUnknownTransportIsReported() {
        val prefix = "vless://44444444-4444-4444-4444-444444444444@host:443"
        val empty = LinkParser.parse("$prefix?type=&sni=host")
        assertIs<OutboundSpec.Vless>(empty)
        assertEquals(TransportSpec.Tcp, empty.transport)

        val unknown = "$prefix?type=ws&sni=host"
        assertNull(LinkParser.parse(unknown))
        assertEquals("ws", LinkParser.unsupportedVlessTransport(unknown))
        assertNull(LinkParser.unsupportedVlessTransport("vless://broken"))
    }

    @Test fun parsesHysteria2() {
        val link = "hysteria2://PASSWORD@1.2.3.4:443?sni=h.example&obfs=salamander&obfs-password=OBFS&insecure=1#RU"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Hysteria2>(s)
        assertEquals("PASSWORD", s.password)
        assertEquals("h.example", s.sni)
        assertEquals("OBFS", s.obfsPassword)
        assertEquals(true, s.insecure)
        assertEquals("RU", s.tag)
    }

    @Test fun hy2AliasAndNoObfs() {
        val s = LinkParser.parse("hy2://PW@host:8443?sni=x#T") as OutboundSpec.Hysteria2
        assertNull(s.obfsPassword)
        assertEquals(8443, s.port)
    }

    @Test fun rejectsOlcrtcAndGarbage() {
        assertNull(LinkParser.parse("olcrtc://telemost?vp8channel@room#key"))
        assertNull(LinkParser.parse("https://example.com/sub"))
        assertNull(LinkParser.parse("not a link"))
        assertNull(LinkParser.parse("vless://missing-host"))
    }

    /**
     * The exact name a partner subscription serves. Every character above
     * U+007F here is more than one byte, and decoding each `%XX` into its own
     * Char turned this into `ð\u009F‡· EKB Â· Hy2 â\u0086\u2019 ð\u009F\u008C`
     * on a real device — a whole subscription's worth of unreadable rows.
     */
    @Test fun decodesMultiByteTagsAsUtf8() {
        val link = "hysteria2://11111111-1111-1111-1111-111111111111@1.2.3.4:38445" +
            "?sni=example.org&obfs=salamander&obfs-password=pw" +
            "#%F0%9F%87%B7%F0%9F%87%BA%20EKB%20%C2%B7%20Hy2%20%E2%86%92%20%F0%9F%8C%90"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Hysteria2>(s)
        assertEquals("\uD83C\uDDF7\uD83C\uDDFA EKB \u00B7 Hy2 \u2192 \uD83C\uDF10", s.tag)
    }

    /** A run of escapes that is not valid UTF-8 must not lose the rest of the name. */
    @Test fun survivesInvalidPercentSequences() {
        val link = "hysteria2://11111111-1111-1111-1111-111111111111@1.2.3.4:38445" +
            "?sni=example.org#%FF%FE%20ok"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Hysteria2>(s)
        assertEquals(" ok", s.tag.takeLast(3))
    }
}
