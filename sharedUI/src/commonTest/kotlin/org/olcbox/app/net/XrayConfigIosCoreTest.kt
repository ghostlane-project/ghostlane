package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.lines.Reality
import org.olcbox.app.net.core.lines.VlessShapes
import org.olcbox.app.net.core.lines.VmessFixture
import org.olcbox.app.vpn.olc.RustCarriers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `core.json` ([XrayConfig.buildIosCore]): the Rust core's config on iOS, for
 * each line it runs (every shape of every kind under `net/core/lines`), under
 * Global and Bypass Russia; and what the app starts for a location
 * ([IosCore.start]): core.json, olcrtc.json, or a refusal that names the line,
 * decided before the extension is asked. The lists are stand-ins here; the
 * JVM dump test writes the reference configs (once more with the bundled
 * lists), and `scripts/check-rust-core-configs.sh` has the core's own parser
 * read those.
 */
class XrayConfigIosCoreTest {
    private val login = IosCoreFixtures.login

    /**
     * The six lines the extension shipped first, which Phase 0 (sing-box
     * behind hev, Go's Xray for XHTTP) also ran: the comparisons with it are
     * for these.
     */
    private val firstSix = listOf(
        IosCoreLine.RealityVision, IosCoreLine.RealityGrpc, IosCoreLine.WebTunnelWs,
        IosCoreLine.WebTunnelHttpUpgrade, IosCoreLine.Hysteria2, IosCoreLine.XhttpReality,
    )

    private val lists = IosCoreFixtures.lists

    private val bypass = Routing.BypassRussia("unused-on-the-rust-core", DirectDns.Placeholder)

    private val routings: List<Routing> = listOf(Routing.Global, bypass)

    private fun spec(line: IosCoreLine) = IosCoreFixtures.spec(line)

    private fun text(line: IosCoreLine, routing: Routing = Routing.Global, verbose: Boolean = false): String =
        XrayConfig.buildIosCore(
            spec(line), login, routing,
            geodata = lists.takeIf { routing != Routing.Global },
            verboseLogs = verbose,
        )

    private fun build(line: IosCoreLine, routing: Routing = Routing.Global, verbose: Boolean = false): JsonObject =
        Json.parseToJsonElement(text(line, routing, verbose)).jsonObject

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.content.toInt()
    private fun JsonArray.strings() = map { it.jsonPrimitive.content }
    private fun JsonObject.outbounds() = this["outbounds"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.out() = outbounds().first()
    private fun JsonObject.stream() = out().obj("streamSettings")
    private fun JsonObject.rules() = obj("routing")["rules"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.dnsRules() = outbounds().single { it.str("tag") == "dns-out" }.obj("settings")["rules"]!!.jsonArray.map { it.jsonObject }

    /** The types a `qType` string names, as the core reads it: numbers and ranges, comma-separated. */
    private fun qTypes(spec: String): Set<Int> = spec.split(',').flatMapTo(mutableSetOf()) { part ->
        val bounds = part.split('-').map { it.trim().toInt() }
        bounds.first()..bounds.last()
    }

    /**
     * The `dns` outbound's last rule: every type but A (1), AAAA (28) and
     * HTTPS (65) is asked, unchanged, of the resolver at the far end of the
     * line, through the line itself, `out`. A and AAAA stay fake and HTTPS
     * refused whatever the rules before it say.
     */
    private fun assertForwardsTheRestThroughTheLine(config: JsonObject, rule: JsonObject, what: String) {
        assertEquals(setOf("action", "qType", "address", "port", "outboundTag"), rule.keys, what)
        assertEquals("forward", rule.str("action"), what)
        assertEquals((2..65535).toSet() - setOf(28, 65), qTypes(rule.str("qType")), what)
        assertEquals("1.1.1.1", rule.str("address"), what)
        assertEquals(53, rule.int("port"), what)
        assertEquals(config.out().str("tag"), rule.str("outboundTag"), what)
    }

    @Test fun everyFixtureIsTheLineItsLinkSaysAndTheCoreTakesIt() {
        for (line in IosCoreLine.entries) {
            val parsed = spec(line)
            assertEquals(line, IosCore.lineOf(parsed), "$line")
            assertNull(IosCore.refusal(parsed), "$line")
        }
    }

    @Test fun oneInboundSocksOnLoopbackWithTheLoginAndUdp() {
        for (line in IosCoreLine.entries) for (routing in routings) {
            val inbounds = build(line, routing)["inbounds"]!!.jsonArray
            assertEquals(1, inbounds.size, "$line $routing")
            val socks = inbounds.single().jsonObject
            assertEquals("in", socks.str("tag"))
            assertEquals("socks", socks.str("protocol"))
            assertEquals("127.0.0.1", socks.str("listen"))
            assertEquals(XrayConfig.XRAY_SOCKS_PORT, socks.int("port"))
            assertEquals(SingBoxConfig.SINGBOX_SOCKS_PORT, socks.int("port"), "the port hev is given on every line")
            val settings = socks.obj("settings")
            assertEquals("password", settings.str("auth"))
            val account = settings["accounts"]!!.jsonArray.single().jsonObject
            assertEquals(login.username, account.str("user"))
            assertEquals(login.password, account.str("pass"))
            assertEquals("true", settings.str("udp"))
            val sniffing = socks.obj("sniffing")
            assertEquals("true", sniffing.str("enabled"))
            assertEquals(listOf("http", "tls", "quic"), sniffing["destOverride"]!!.jsonArray.strings())
            // The sniffed name is for the rules; a fake address becomes its
            // name before this, and a real one is dialled as it was.
            assertEquals("true", sniffing.str("routeOnly"))
        }
    }

    @Test fun anotherPortIsTheOneWritten() {
        val config = Json.parseToJsonElement(XrayConfig.buildIosCore(spec(IosCoreLine.RealityVision), login, socksPort = 20810)).jsonObject
        assertEquals(20810, config["inbounds"]!!.jsonArray.single().jsonObject.int("port"))
    }

    @Test fun aBlankLoginIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildIosCore(spec(IosCoreLine.RealityVision), SocksLogin("", "p"))
        }
        assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildIosCore(spec(IosCoreLine.RealityVision), SocksLogin("u", " "))
        }
    }

    @Test fun logsAtWarningWithNoAccessLog() {
        val log = build(IosCoreLine.RealityVision).obj("log")
        assertEquals("warning", log.str("loglevel"))
        assertEquals("none", log.str("access"))
        assertEquals("debug", build(IosCoreLine.RealityVision, verbose = true).obj("log").str("loglevel"))
        assertEquals("none", build(IosCoreLine.RealityVision, verbose = true).obj("log").str("access"))
    }

    @Test fun globalFakesAddressesRefusesHttpsAndForwardsTheRestThroughTheLine() {
        for (line in IosCoreLine.entries) {
            val config = build(line)
            val dns = config.obj("dns")
            assertEquals("UseIPv4", dns.str("queryStrategy"))
            val fakeIp = dns.obj("fakeIp")
            assertEquals("true", fakeIp.str("enabled"))
            assertEquals("198.18.0.0/15", fakeIp.str("ipv4Pool"))
            assertNull(dns["servers"], "nothing is resolved for real under Global")
            assertEquals(listOf("out", "dns-out"), config.outbounds().map { it.str("tag") })
            val dnsOut = config.outbounds()[1]
            assertEquals("dns", dnsOut.str("protocol"))
            assertNull(dnsOut.obj("settings")["rewriteAddress"])
            assertEquals(2, config.dnsRules().size, "$line")
            val (https, forward) = config.dnsRules()
            assertEquals("return", https.str("action"))
            assertEquals(65, https.int("qType"))
            assertEquals(5, https.int("rCode"))
            assertForwardsTheRestThroughTheLine(config, forward, "$line")

            assertEquals("AsIs", config.obj("routing").str("domainStrategy"))
            val hijack = config.rules().single()
            assertEquals(listOf("in"), hijack["inboundTag"]!!.jsonArray.strings())
            assertEquals("tcp,udp", hijack.str("network"))
            assertEquals(53, hijack.int("port"))
            assertEquals("dns-out", hijack.str("outboundTag"))
        }
    }

    @Test fun bypassResolvesTheListsOnTheNetworkUnderneathAndSendsThemDirect() {
        for (line in IosCoreLine.entries) {
            val config = build(line, bypass)
            val dns = config.obj("dns")
            assertEquals("198.18.0.0/15", dns.obj("fakeIp").str("ipv4Pool"))
            val server = dns["servers"]!!.jsonArray.single().jsonObject
            assertEquals("dns-direct", server.str("tag"))
            assertEquals(SingBoxConfig.DIRECT_DNS_PLACEHOLDER, server.str("address"))
            assertEquals(53, server.int("port"))
            assertNull(server["domains"])

            assertEquals(listOf("out", "direct", "dns-out"), config.outbounds().map { it.str("tag") })
            val direct = config.outbounds()[1]
            assertEquals("freedom", direct.str("protocol"))
            assertNull(direct["settings"])
            val dnsOut = config.outbounds()[2].obj("settings")
            assertEquals(SingBoxConfig.DIRECT_DNS_PLACEHOLDER, dnsOut.str("rewriteAddress"))
            assertEquals(53, dnsOut.int("rewritePort"))
            // The first rule that matches wins: a Russian name of any type is
            // asked underneath before the forward rule, last, can claim it.
            assertEquals(3, config.dnsRules().size, "$line")
            val (names, https, forward) = config.dnsRules()
            assertEquals("direct", names.str("action"))
            assertEquals(lists.domains, names["domain"]!!.jsonArray.strings())
            assertNull(names["qType"], "every type of a Russian name, as sing-box sends them to dns-direct")
            assertEquals("return", https.str("action"))
            assertEquals(65, https.int("qType")); assertEquals(5, https.int("rCode"))
            assertForwardsTheRestThroughTheLine(config, forward, "$line")

            val rules = config.rules()
            assertEquals(5, rules.size)
            assertEquals(listOf("in"), rules[0]["inboundTag"]!!.jsonArray.strings())
            assertEquals("dns-out", rules[0].str("outboundTag"))
            assertEquals(listOf("dns-direct"), rules[1]["inboundTag"]!!.jsonArray.strings())
            assertEquals("direct", rules[1].str("outboundTag"))
            assertEquals(XrayConfig.PRIVATE_RANGES, rules[2]["ip"]!!.jsonArray.strings())
            assertEquals(lists.domains, rules[3]["domain"]!!.jsonArray.strings())
            assertEquals(lists.cidrs, rules[4]["ip"]!!.jsonArray.strings())
            assertTrue(rules.drop(1).all { it.str("outboundTag") == "direct" })
            assertTrue("198.18.0.0/15" !in XrayConfig.PRIVATE_RANGES, "a fake address must not go direct")
        }
    }

    @Test fun theLineIsTheSameUnderEitherRouting() {
        for (line in IosCoreLine.entries) {
            assertEquals(build(line).out(), build(line, bypass).out(), "$line")
        }
    }

    @Test fun thePlaceholderIsWhereTheExtensionReplacesIt() {
        // DirectResolver.substitute replaces every quoted occurrence: the
        // resolver and where the dns outbound forwards Russian names.
        val placeholder = "\"${SingBoxConfig.DIRECT_DNS_PLACEHOLDER}\""
        assertEquals(2, Regex(Regex.escape(placeholder)).findAll(text(IosCoreLine.RealityVision, bypass)).count())
        assertEquals(0, Regex(Regex.escape(placeholder)).findAll(text(IosCoreLine.RealityVision)).count())
    }

    @Test fun anExplicitResolverIsWrittenAndAZonedOneIsNot() {
        fun resolver(direct: DirectDns) = Json.parseToJsonElement(
            XrayConfig.buildIosCore(
                spec(IosCoreLine.RealityVision), login, Routing.BypassRussia("x", direct), geodata = lists,
            )
        ).jsonObject.obj("dns")["servers"]!!.jsonArray.single().jsonObject.str("address")
        assertEquals("10.20.30.40", resolver(DirectDns.Servers(listOf("10.20.30.40"))))
        // The core's parser refuses a scoped address: the public one stands in.
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, resolver(DirectDns.Servers(listOf("fe80::1%en0"))))
        assertFailsWith<IllegalArgumentException> { resolver(DirectDns.System) }
    }

    @Test fun onlyTheShapesSingBoxBuildsOnThePhoneAreBuilt() {
        val reality = spec(IosCoreLine.RealityVision)
        assertFailsWith<IllegalArgumentException>("a bypass carries its lists") {
            XrayConfig.buildIosCore(reality, login, bypass)
        }
        assertFailsWith<IllegalArgumentException>("blocked-only needs Re:filter's lists the app does not carry in Xray's form") {
            XrayConfig.buildIosCore(reality, login, Routing.Rules("x", DirectDns.Placeholder, Routing.Policy.BlockedOnly), geodata = lists)
        }
        assertFailsWith<IllegalArgumentException>("a bypass with the user's rules still carries its lists") {
            XrayConfig.buildIosCore(
                reality, login,
                Routing.Rules("x", DirectDns.Placeholder, Routing.Policy.Bypass("ru"), CustomRules.of(listOf("example.com"), emptyList())),
            )
        }
        // The user's own rules (Android's Rust path): built, see CoreCustomRoutingTest.
        XrayConfig.buildIosCore(
            reality, login,
            Routing.Rules("x", DirectDns.Placeholder, Routing.Policy.Bypass("ru"), CustomRules.of(listOf("example.com"), emptyList())),
            geodata = lists,
        )
    }

    @Test fun realityVision() {
        val out = build(IosCoreLine.RealityVision).out()
        assertEquals("vless", out.str("protocol"))
        val server = out.obj("settings")["vnext"]!!.jsonArray.single().jsonObject
        assertEquals("127.0.0.1", server.str("address")); assertEquals(443, server.int("port"))
        val user = server["users"]!!.jsonArray.single().jsonObject
        assertEquals("11111111-1111-1111-1111-111111111111", user.str("id"))
        assertEquals("none", user.str("encryption"))
        // Vision that lets QUIC to 443 through, as sing-box's Vision does;
        // the server is sent plain Vision either way.
        assertEquals("xtls-rprx-vision-udp443", user.str("flow"))
        val stream = out.obj("streamSettings")
        assertEquals("raw", stream.str("network"))
        assertEquals("reality", stream.str("security"))
        val reality = stream.obj("realitySettings")
        assertEquals("www.microsoft.com", reality.str("serverName"))
        assertEquals("chrome", reality.str("fingerprint"))
        assertEquals(IosCoreFixtures.PUBLIC_KEY, reality.str("publicKey"))
        assertEquals("ab12cd34", reality.str("shortId"))
        assertEquals(setOf("network", "security", "realitySettings"), stream.keys)
    }

    @Test fun realityWithoutAFlowWritesNone() {
        val link = IosCoreFixtures.links.getValue(IosCoreLine.RealityVision).replace("&flow=xtls-rprx-vision", "")
        val spec = assertNotNull(LinkParser.parse(link))
        assertEquals(IosCoreLine.RealityVision, IosCore.lineOf(spec))
        val config = Json.parseToJsonElement(XrayConfig.buildIosCore(spec, login)).jsonObject
        val user = config.out().obj("settings")["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertNull(user["flow"])
    }

    @Test fun realityGrpc() {
        val out = build(IosCoreLine.RealityGrpc).out()
        val user = out.obj("settings")["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertNull(user["flow"])
        val stream = out.obj("streamSettings")
        assertEquals("grpc", stream.str("network"))
        assertEquals("media/sync", stream.obj("grpcSettings").str("serviceName"))
        assertEquals("reality", stream.str("security"))
        assertEquals("rutube.ru", stream.obj("realitySettings").str("serverName"))
    }

    private fun checkWebTunnel(line: IosCoreLine, network: String, settings: String, path: String) {
        val out = build(line).out()
        val user = out.obj("settings")["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertNull(user["flow"])
        val stream = out.obj("streamSettings")
        assertEquals(network, stream.str("network"))
        val transport = stream.obj(settings)
        assertEquals(path, transport.str("path"))
        assertEquals("wt.example.com", transport.str("host"))
        assertEquals("tls", stream.str("security"))
        val tls = stream.obj("tlsSettings")
        assertEquals("wt.example.com", tls.str("serverName"))
        assertEquals("chrome", tls.str("fingerprint"))
        // The link's browser offer: the core keeps exactly this list on an
        // upgrade handshake and sends `http/1.1` alone for anything else.
        assertEquals(listOf("h2", "http/1.1"), tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
        // A certificate is checked, always: the core refuses to waive it.
        assertNull(tls["allowInsecure"])
        assertNull(stream["realitySettings"])
    }

    @Test fun webTunnelOverWebSocketAndOverHttpUpgrade() {
        checkWebTunnel(IosCoreLine.WebTunnelWs, "ws", "wsSettings", "/v")
        checkWebTunnel(IosCoreLine.WebTunnelHttpUpgrade, "httpupgrade", "httpupgradeSettings", "/u")
    }

    @Test fun webTunnelWithoutAHostOrServerNameLeavesThemToTheCore() {
        val link = "vless://33333333-3333-3333-3333-333333333333@127.0.0.1:443?security=tls&type=ws#bare"
        val spec = assertNotNull(LinkParser.parse(link))
        val stream = Json.parseToJsonElement(XrayConfig.buildIosCore(spec, login)).jsonObject.stream()
        assertNull(stream.obj("wsSettings")["host"])
        assertEquals("/", stream.obj("wsSettings").str("path"))
        assertNull(stream.obj("tlsSettings")["serverName"])
        // No alpn in the link, none written: the core's own `http/1.1`.
        assertNull(stream.obj("tlsSettings")["alpn"])
    }

    @Test fun proofKitsWebTunnelLinkOffersABrowsersAlpn() {
        // The line exactly as ProofKit's subscription writes it.
        val link = "vless://33333333-3333-3333-3333-333333333333@198.51.100.7:443?encryption=none&security=tls" +
            "&sni=wt.example.com&host=wt.example.com&type=httpupgrade&path=%2Fs3cr3t&fp=chrome" +
            "&alpn=h2%2Chttp%2F1.1#DE%20%C2%B7%20WebTunnel"
        val spec = assertNotNull(LinkParser.parse(link)) as OutboundSpec.Vless
        assertEquals(listOf("h2", "http/1.1"), spec.alpn)
        assertEquals(IosEngineChoice(IosEngine.RUST, IosCoreLine.WebTunnelHttpUpgrade), IosCore.choose(spec))
        val tls = Json.parseToJsonElement(XrayConfig.buildIosCore(spec, login)).jsonObject.stream().obj("tlsSettings")
        assertEquals(listOf("h2", "http/1.1"), tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Phase 0 offers the same.
        val sb = Json.parseToJsonElement(SingBoxConfig.buildIosSocks(spec, login)).jsonObject.outbounds().first()
        assertEquals(listOf("h2", "http/1.1"), sb.obj("tls")["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun alpnIsReadForUpgradeHandshakesOnly() {
        // Reality has no ALPN of the link's to offer, and gRPC's is the core's.
        for (link in listOf(
            IosCoreFixtures.links.getValue(IosCoreLine.RealityVision) + "&alpn=h2%2Chttp%2F1.1",
            IosCoreFixtures.links.getValue(IosCoreLine.RealityGrpc) + "&alpn=h2",
        )) {
            val spec = assertNotNull(LinkParser.parse(link)) as OutboundSpec.Vless
            assertEquals(emptyList<String>(), spec.alpn, link)
            val stream = Json.parseToJsonElement(XrayConfig.buildIosCore(spec, login)).jsonObject.stream()
            assertNull(stream["tlsSettings"], link)
        }
    }

    @Test fun hysteria2WithSalamanderAndThePinVerified() {
        val out = build(IosCoreLine.Hysteria2).out()
        assertEquals("hysteria", out.str("protocol"))
        val settings = out.obj("settings")
        assertEquals(2, settings.int("version"))
        assertEquals("127.0.0.1", settings.str("address")); assertEquals(443, settings.int("port"))
        val stream = out.obj("streamSettings")
        assertEquals("hysteria", stream.str("network"))
        assertEquals("tls", stream.str("security"))
        val tls = stream.obj("tlsSettings")
        assertEquals("www.microsoft.com", tls.str("serverName"))
        assertEquals(IosCoreFixtures.PIN.replace(":", "").lowercase(), tls.str("pinnedPeerCertSha256"))
        // The core refuses a fingerprint on Hysteria, and an ALPN other than h3.
        assertNull(tls["fingerprint"]); assertNull(tls["alpn"]); assertNull(tls["allowInsecure"])
        val hysteria = stream.obj("hysteriaSettings")
        assertEquals(2, hysteria.int("version"))
        assertEquals("PASSWORD123", hysteria.str("auth"))
        val mask = stream.obj("finalmask")["udp"]!!.jsonArray.single().jsonObject
        assertEquals("salamander", mask.str("type"))
        assertEquals("OBFSPW", mask.obj("settings").str("password"))
        // Salamander hides the QUIC handshake, so ChromeParrot would hide
        // nothing and replace the core's windows with Chrome's 6 and 15 MiB.
        val quic = stream.obj("finalmask").obj("quicParams")
        assertEquals(setOf("disableChromeParrot"), quic.keys)
        assertEquals("true", quic.str("disableChromeParrot"))
    }

    @Test fun hysteria2WithoutObfsHasNoMask() {
        val link = "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&pinSHA256=${IosCoreFixtures.PIN}#plain"
        val spec = assertNotNull(LinkParser.parse(link))
        val stream = Json.parseToJsonElement(XrayConfig.buildIosCore(spec, login)).jsonObject.stream()
        // No mask and no quicParams: the hello is on the wire, the parrot stays on.
        assertNull(stream["finalmask"])
    }

    @Test fun xhttpRealityPacketUpWithGosWindows() {
        val out = build(IosCoreLine.XhttpReality).out()
        val stream = out.obj("streamSettings")
        assertEquals("xhttp", stream.str("network"))
        val xhttp = stream.obj("xhttpSettings")
        assertEquals("/download", xhttp.str("path"))
        assertEquals("www.microsoft.com", xhttp.str("host"))
        assertEquals("packet-up", xhttp.str("mode"))
        assertEquals(XrayConfig.XHTTP_MAX_EACH_POST_BYTES, xhttp.int("scMaxEachPostBytes"))
        assertEquals(524_288, xhttp.int("h2StreamReceiveWindow"))
        assertEquals("reality", stream.str("security"))
        assertEquals("cd34", stream.obj("realitySettings").str("shortId"))
        assertNull(stream["sockopt"], "the core refuses sockopt.domainStrategy on VLESS")
    }

    @Test fun noFieldOutsideWhatTheCoreAccepts() {
        for (line in IosCoreLine.entries) for (routing in routings) for (verbose in listOf(false, true)) {
            assertEquals(emptyList<String>(), XrayRustSurface.violations(build(line, routing, verbose)), "$line $routing verbose=$verbose")
        }
    }

    @Test fun theSurfaceCheckSeesAFieldTheCoreDoesNotKnow() {
        val config = build(IosCoreLine.RealityVision).toMutableMap()
        config["experimental"] = JsonObject(emptyMap())
        val inbound = config["inbounds"]!!.jsonArray[0].jsonObject
        config["inbounds"] = JsonArray(listOf(JsonObject(inbound + ("udp_timeout" to JsonPrimitive("30s")))))
        val tls = JsonObject(mapOf("allowInsecure" to JsonPrimitive(false), "insecure" to JsonPrimitive(true)))
        val out = build(IosCoreLine.WebTunnelWs).out()
        val stream = JsonObject(out.obj("streamSettings") + ("tlsSettings" to tls) + ("sockopt" to JsonObject(emptyMap())))
        config["outbounds"] = JsonArray(listOf(JsonObject(out + ("streamSettings" to stream))))
        assertEquals(
            listOf(
                "$.experimental",
                "$.inbounds[0].udp_timeout",
                "$.outbounds[0].streamSettings.tlsSettings.insecure",
                "$.outbounds[0].streamSettings.sockopt is not checked here",
            ),
            XrayRustSurface.violations(JsonObject(config)),
        )
    }

    /** What Phase 0 (sing-box behind hev) is given for the same link. */
    private fun phase0(line: IosCoreLine, routing: Routing = Routing.Global): JsonObject =
        Json.parseToJsonElement(SingBoxConfig.buildIosSocks(spec(line), login, routing = routing)).jsonObject

    @Test fun xhttpIsWhatGosXrayIsGivenPlusItsWindow() {
        // Phase 0 runs XHTTP on Go's Xray (buildXhttp), not on sing-box.
        val xhttp = spec(IosCoreLine.XhttpReality) as OutboundSpec.Vless
        val go = Json.parseToJsonElement(XrayConfig.buildXhttp(xhttp, answersDns = true, login = login)).jsonObject.out()
        val out = build(IosCoreLine.XhttpReality).out()
        assertEquals(go.obj("settings"), out.obj("settings"))
        val stream = out.obj("streamSettings")
        val withoutWindow = JsonObject(stream + ("xhttpSettings" to JsonObject(stream.obj("xhttpSettings") - "h2StreamReceiveWindow")))
        assertEquals(go.obj("streamSettings"), withoutWindow)
    }

    @Test fun theSameLinkMeansWhatItMeansToPhase0() {
        for (line in firstSix - IosCoreLine.XhttpReality) {
            val sb = phase0(line).outbounds().first()
            val out = build(line).out()
            val stream = out.obj("streamSettings")
            when (line) {
                IosCoreLine.Hysteria2 -> {
                    val settings = out.obj("settings")
                    assertEquals(sb.str("server"), settings.str("address"))
                    assertEquals(sb.int("server_port"), settings.int("port"))
                    assertEquals(sb.str("password"), stream.obj("hysteriaSettings").str("auth"))
                    assertEquals(sb.obj("obfs").str("password"), stream.obj("finalmask")["udp"]!!.jsonArray[0].jsonObject.obj("settings").str("password"))
                    assertEquals(sb.obj("tls").str("server_name"), stream.obj("tlsSettings").str("serverName"))
                }
                else -> {
                    val server = out.obj("settings")["vnext"]!!.jsonArray[0].jsonObject
                    val user = server["users"]!!.jsonArray[0].jsonObject
                    assertEquals(sb.str("server"), server.str("address"))
                    assertEquals(sb.int("server_port"), server.int("port"))
                    assertEquals(sb.str("uuid"), user.str("id"))
                    assertEquals(sb["flow"] != null, user["flow"] != null, "$line flow")
                    val tls = sb.obj("tls")
                    val security = stream.obj(if (tls["reality"] != null) "realitySettings" else "tlsSettings")
                    assertEquals(tls.str("server_name"), security.str("serverName"), "$line")
                    assertEquals(tls.obj("utls").str("fingerprint"), security.str("fingerprint"), "$line")
                    assertEquals(tls["alpn"], security["alpn"], "$line alpn")
                    tls["reality"]?.jsonObject?.let { reality ->
                        assertEquals(reality.str("public_key"), security.str("publicKey"))
                        assertEquals(reality.str("short_id"), security.str("shortId"))
                    }
                    sb["transport"]?.jsonObject?.let { transport ->
                        assertEquals(transport.str("type"), stream.str("network"), "$line")
                        when (transport.str("type")) {
                            "grpc" -> assertEquals(transport.str("service_name"), stream.obj("grpcSettings").str("serviceName"))
                            "ws" -> {
                                assertEquals(transport.str("path"), stream.obj("wsSettings").str("path"))
                                assertEquals(transport.obj("headers").str("Host"), stream.obj("wsSettings").str("host"))
                            }
                            "httpupgrade" -> {
                                assertEquals(transport.str("path"), stream.obj("httpupgradeSettings").str("path"))
                                assertEquals(transport.str("host"), stream.obj("httpupgradeSettings").str("host"))
                            }
                            else -> error("no comparison for ${transport.str("type")}")
                        }
                    }
                }
            }
        }
    }

    @Test fun theSameResolverPlaceholderFakeRangeAndPortAsPhase0() {
        val sb = phase0(IosCoreLine.RealityVision, bypass)
        val servers = sb.obj("dns")["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            servers.single { it.str("tag") == "dns-direct" }.str("server"),
            build(IosCoreLine.RealityVision, bypass).obj("dns")["servers"]!!.jsonArray[0].jsonObject.str("address"),
        )
        assertEquals(servers.single { it.str("type") == "fakeip" }.str("inet4_range"), IosCore.FAKE_IP_POOL)
        assertEquals(sb["inbounds"]!!.jsonArray[0].jsonObject.int("listen_port"), XrayConfig.XRAY_SOCKS_PORT)
        // Both refuse HTTPS-type queries.
        assertTrue(sb.obj("dns")["rules"]!!.jsonArray.any { rule ->
            rule.jsonObject["query_type"]?.jsonArray?.strings() == listOf("HTTPS") && rule.jsonObject.str("action") == "reject"
        })
    }

    @Test fun theRestIsAskedOfTheResolverTheGoEnginesAskThroughTheLine() {
        // Phase 0 asks `dns-remote` through `out` for what no rule claims:
        // sing-box on every line but XHTTP, Go's Xray on XHTTP. core.json
        // asks the same server through the same outbound.
        val xhttp = spec(IosCoreLine.XhttpReality) as OutboundSpec.Vless
        val go = Json.parseToJsonElement(XrayConfig.buildXhttp(xhttp, answersDns = true, login = login)).jsonObject
        val goRemote = go.obj("dns")["servers"]!!.jsonArray.map { it.jsonObject }.single { it.str("tag") == "dns-remote" }
        assertEquals(listOf("dns-remote"), go.rules().single { it.str("outboundTag") == "out" }["inboundTag"]!!.jsonArray.strings())
        for (line in IosCoreLine.entries) for (routing in routings) {
            val forward = build(line, routing).dnsRules().last()
            assertEquals(goRemote.str("address"), forward.str("address"), "$line $routing")
            assertEquals(goRemote.int("port"), forward.int("port"), "$line $routing")
            if (line == IosCoreLine.XhttpReality || line !in firstSix) continue
            val sbRemote = phase0(line, routing).obj("dns")["servers"]!!.jsonArray.map { it.jsonObject }
                .single { it.str("tag") == "dns-remote" }
            assertEquals(sbRemote.str("server"), forward.str("address"), "$line $routing")
            assertEquals(sbRemote.str("detour"), forward.str("outboundTag"), "$line $routing")
            assertNull(sbRemote["server_port"], "sing-box's default, 53")
        }
    }

    @Test fun fingerprintsAreSingBoxsNamesTheCoreKnows() {
        assertEquals("chrome", IosCore.fingerprint(null, reality = true))
        assertEquals("chrome", IosCore.fingerprint("", reality = true))
        assertEquals("chrome", IosCore.fingerprint("hellochrome_133", reality = true))
        assertEquals("chrome", IosCore.fingerprint("chrome_pq", reality = true))
        assertEquals("firefox", IosCore.fingerprint("Firefox", reality = true))
        assertEquals("safari", IosCore.fingerprint("hellosafari_26_3", reality = true))
        assertEquals("randomized", IosCore.fingerprint("randomizednoalpn", reality = true))
        assertEquals("android", IosCore.fingerprint("android", reality = false))
        // No X25519 key share: no REALITY hello in the core, so Chrome's is used.
        assertEquals("chrome", IosCore.fingerprint("android", reality = true))
        assertEquals("chrome", IosCore.fingerprint("360", reality = true))
    }

    @Test fun pinsAreCompactLowerCaseHexOfThirtyTwoBytes() {
        val compact = IosCoreFixtures.PIN.replace(":", "").lowercase()
        assertEquals(compact, IosCore.pin(IosCoreFixtures.PIN))
        assertEquals(compact, IosCore.pin(compact.uppercase()))
        assertNull(IosCore.pin("not-hex"))
        assertNull(IosCore.pin(compact.dropLast(2)))
        assertNull(IosCore.pin("m9U0V+MKz7I1bI6ijtuflS4GAfVqGqNwGltOongREfg="), "base64 is not what the core reads")
    }

    /** Links the core refuses: none has an engine to start on, since the extension carries no other. */
    private val refused = listOf(
        "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&pinSHA256=abcd#short-pin",
        "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=abc&pinSHA256=${IosCoreFixtures.PIN}#short-obfs",
        "hysteria2://PASSWORD123@hy.example.com:443?sni=www.microsoft.com&pinSHA256=${IosCoreFixtures.PIN}#hostname",
        "vless://u@127.0.0.1:443?security=tls&sni=wt.example.com&type=ws&allowInsecure=1#insecure",
        "vless://u@127.0.0.1:443?security=reality&pbk=${IosCoreFixtures.PUBLIC_KEY}&type=tcp#no-sni",
        "vless://u@127.0.0.1:443?security=reality&pbk=${IosCoreFixtures.PUBLIC_KEY}&sni=a.example&flow=xtls-rprx-direct&type=tcp#old-flow",
        "vless://u@de.example.com:443?security=reality&pbk=${IosCoreFixtures.PUBLIC_KEY}&sni=a.example&type=tcp#hostname",
    )

    /** A VLESS id, made up. */
    private val vlessId = "44444444-4444-4444-4444-444444444444"

    /** VLESS with no TLS to a public address: refused, as official Xray refuses it. */
    private val plainToAPublicServer =
        "vless://55555555-5555-5555-5555-555555555555@198.51.100.7:80?security=none&encryption=none&type=tcp#public"

    /** VLESS over HTTP/2, which the core does not carry. */
    private val http2 =
        "vless://$vlessId@127.0.0.1:443?security=tls&encryption=none&sni=example.com&type=http&path=%2F#http"

    @Test fun everyShapeOfEveryKindIsALineOfTheIphones() {
        // The line table is the kinds' shapes: a kind added under
        // net/core/lines is a line of the iPhone's with nothing named by hand.
        assertEquals(CoreLines.all.flatMap { it.shapes }, IosCoreLine.entries.map { it.slug })
        for (line in firstSix) assertTrue(line in IosCoreLine.entries, "$line")
    }

    @Test fun theExtensionCarriesNoGoSoEveryLineChoosesRust() {
        for (line in IosCoreLine.entries) {
            assertEquals(IosEngineChoice(IosEngine.RUST, line), IosCore.choose(spec(line)), "$line")
        }
    }

    @Test fun aLineTheCoreRefusesHasNoEngineAndSaysWhy() {
        for (link in refused) {
            val spec = assertNotNull(LinkParser.parse(link), link)
            val choice = IosCore.choose(spec)
            assertTrue(choice.engine != IosEngine.RUST, link)
            assertNotNull(choice.line, link)
            assertNotNull(choice.reason, link)
            assertEquals(IosCore.refusal(spec), choice.reason, link)
            assertFailsWith<IllegalArgumentException>(link) { XrayConfig.buildIosCore(spec, login) }
        }
    }

    /**
     * The kinds beside the first six: each link is a line of the iPhone's by
     * its shape, chooses the core, and is written a core.json whose outbound
     * speaks its own protocol and holds nothing the core does not know.
     */
    @Test fun trojanShadowsocksVmessAndVlessOverTlsChooseRustAndBuildACoreJson() {
        val vmess = VmessFixture.link(
            "ps" to "vm", "scy" to "auto", "net" to "ws", "host" to "vm.example.com", "path" to "/vm",
            "tls" to "tls", "sni" to "vm.example.com",
        )
        val tls = "vless://$vlessId@127.0.0.1:443?security=tls&encryption=none&sni=example.com"
        val links = listOf(
            Triple("trojan://secret@127.0.0.1:443?security=tls&sni=example.com#trojan", "trojan", "trojan"),
            Triple(
                "trojan://secret@127.0.0.1:443?security=tls&sni=example.com&type=ws&path=%2Ft&host=cdn.example.com#trojan-ws",
                "trojan-ws", "trojan",
            ),
            Triple("ss://YWVzLTI1Ni1nY206c2VjcmV0@127.0.0.1:8388#ss", "shadowsocks", "shadowsocks"),
            Triple("ss://2022-blake3-aes-128-gcm:AAECAwQFBgcICQoLDA0ODw%3D%3D@127.0.0.1:8388#ss2022", "shadowsocks-2022", "shadowsocks"),
            Triple(vmess, "vmess-ws-tls", "vmess"),
            Triple("$tls&type=tcp#tls-tcp", "vless-tls", "vless"),
            Triple("$tls&flow=xtls-rprx-vision&type=tcp#tls-vision", "vless-tls", "vless"),
            Triple("$tls&type=grpc&serviceName=g#grpc-tls", "vless-tls-grpc", "vless"),
            Triple("$tls&type=xhttp&path=%2F#xhttp-tls", "vless-tls-xhttp", "vless"),
            Triple("vless://$vlessId@127.0.0.1:80?security=none&encryption=none&type=ws&path=%2F#plain", "vless-none-ws", "vless"),
        )
        for ((link, slug, protocol) in links) {
            val spec = assertNotNull(LinkParser.parse(link), link)
            val line = assertNotNull(IosCore.lineOf(spec), link)
            assertEquals(slug, line.slug, link)
            assertEquals(IosEngineChoice(IosEngine.RUST, line), IosCore.choose(spec), link)
            val config = Json.parseToJsonElement(XrayConfig.buildIosCore(spec, login)).jsonObject
            assertEquals(protocol, config.out().str("protocol"), link)
            assertEquals(emptyList<String>(), XrayRustSurface.violations(config), link)
        }
    }

    /** What the core refuses of these kinds has no engine either, and says why in the kind's own words. */
    @Test fun http2AndPlainVlessToAPublicServerHaveNoEngineAndTheirSentences() {
        val overHttp2 = IosCore.choose(assertNotNull(LinkParser.parse(http2)))
        assertTrue(overHttp2.engine != IosEngine.RUST)
        assertNull(overHttp2.line, "HTTP/2 is no shape the core writes")
        assertEquals(VlessShapes.HTTP2_REFUSAL, overHttp2.reason)
        val plain = IosCore.choose(assertNotNull(LinkParser.parse(plainToAPublicServer)))
        assertTrue(plain.engine != IosEngine.RUST)
        assertEquals("vless-none", plain.line?.slug)
        assertEquals(VlessShapes.PLAIN_NEEDS_PRIVATE_SERVER, plain.reason)
    }

    // What the app starts for a location (IosCore.start).

    private fun kindOf(spec: OutboundSpec): LocationKind = when (spec) {
        is OutboundSpec.Vless -> LocationKind.Vless
        is OutboundSpec.Hysteria2 -> LocationKind.Hysteria2
        is OutboundSpec.Trojan -> LocationKind.Trojan
        is OutboundSpec.Shadowsocks -> LocationKind.Shadowsocks
        is OutboundSpec.Vmess -> LocationKind.Vmess
    }

    /** A location of [link], as the list stores one: its kind, the link, a name. */
    private fun located(link: String, name: String = "DE line"): LocationConfig {
        val kind = LinkParser.parse(link)?.let { kindOf(it) } ?: LocationKind.Vless
        return LocationConfig(name = name, kind = kind, rawLink = link)
    }

    /** An olcRTC room on [provider], with a made-up room and key. */
    private fun room(provider: String, name: String = "NL room") = LocationConfig(
        name = name,
        id = "room-of-the-test",
        key = "5a".repeat(32),
        bypassProvider = provider,
        kind = LocationKind.Olcrtc,
    )

    @Test fun everyLineStartsOnTheCoreFromCoreJson() {
        for (line in IosCoreLine.entries) {
            val spec = spec(line)
            assertEquals(
                IosStart.Core(spec, line, listOfNotNull(Reality.helloNote(spec))),
                IosCore.start(located(IosCoreFixtures.link(line))),
                "$line",
            )
        }
    }

    @Test fun aRealityHelloTheCoreLacksIsSwappedForChromesAndTheLogIsToldSo() {
        val vision = IosCoreFixtures.links.getValue(IosCoreLine.RealityVision)
        for (fp in listOf("android", "360")) {
            val link = vision.replace("&fp=chrome&", "&fp=$fp&")
            val spec = assertNotNull(LinkParser.parse(link), fp)
            val start = assertIs<IosStart.Core>(IosCore.start(located(link)), fp)
            val note = assertNotNull(Reality.helloNote(spec), fp)
            assertEquals(listOf(note), start.notes, fp)
            assertTrue(fp in note && "Chrome" in note, note)
            val reality = Json.parseToJsonElement(XrayConfig.buildIosCore(start.spec, login)).jsonObject.stream().obj("realitySettings")
            assertEquals("chrome", reality.str("fingerprint"), fp)
        }
        // A hello the core has: nothing to tell.
        assertEquals(emptyList<String>(), assertIs<IosStart.Core>(IosCore.start(located(vision))).notes)
    }

    @Test fun aRetiredCarriersRoomIsRefusedBeforeTheExtensionIsAsked() {
        for (provider in listOf(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.PROVIDER_TELEMOST)) {
            val room = room(provider)
            val reason = assertNotNull(RustCarriers.refusal(room), provider)
            assertEquals(IosStart.Refused("Cannot start NL room: $reason"), IosCore.start(room), provider)
        }
        for (provider in listOf(LocationConfig.PROVIDER_SALUTEJAZZ, LocationConfig.PROVIDER_VKCALLS, LocationConfig.PROVIDER_MTSLINK)) {
            assertEquals(IosStart.Room, IosCore.start(room(provider)), provider)
        }
    }

    @Test fun aRefusalNamesTheLineAndSaysWhy() {
        assertEquals(
            IosStart.Refused("Cannot start DE line: ${VlessShapes.PLAIN_NEEDS_PRIVATE_SERVER}"),
            IosCore.start(located(plainToAPublicServer)),
        )
        assertEquals(IosStart.Refused("Cannot start DE line: ${VlessShapes.HTTP2_REFUSAL}"), IosCore.start(located(http2)))
        for (link in refused) {
            val reason = assertNotNull(IosCore.refusal(assertNotNull(LinkParser.parse(link), link)), link)
            assertEquals(IosStart.Refused("Cannot start DE line: $reason"), IosCore.start(located(link)), link)
        }
        // A location with no name of its own is named by what it is, and a
        // room never by its id: that is the address of a meeting.
        assertEquals(
            IosStart.Refused("Cannot start this line: ${VlessShapes.PLAIN_NEEDS_PRIVATE_SERVER}"),
            IosCore.start(located(plainToAPublicServer, name = " ")),
        )
        val unnamed = room(LocationConfig.PROVIDER_WB_STREAM, name = "")
        assertEquals(IosStart.Refused("Cannot start this room: ${RustCarriers.refusal(unnamed)}"), IosCore.start(unnamed))
        // A link the app does not read.
        assertEquals(IosStart.Refused("Cannot start DE line: the app reads no link in it"), IosCore.start(located("https://example.com/")))
    }

    @Test fun aPinOverridesTheWaiverAndIsVerified() {
        // insecure=1 beside a pin: the core verifies the pin, sing-box skipped both.
        val link = "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&insecure=1&pinSHA256=${IosCoreFixtures.PIN}#both"
        val spec = assertNotNull(LinkParser.parse(link))
        assertEquals(IosEngine.RUST, IosCore.choose(spec).engine)
        val tls = Json.parseToJsonElement(XrayConfig.buildIosCore(spec, login)).jsonObject.stream().obj("tlsSettings")
        assertEquals(IosCoreFixtures.PIN.replace(":", "").lowercase(), tls.str("pinnedPeerCertSha256"))
    }

    @Test fun theXrayShapesOfTheOtherPlatformsAreNotThisOne() {
        // buildXhttp is what Go's Xray runs everywhere; nothing of core.json
        // leaks into it.
        val xhttp = spec(IosCoreLine.XhttpReality) as OutboundSpec.Vless
        for (answersDns in listOf(false, true)) {
            val config = XrayConfig.buildXhttp(xhttp, answersDns = answersDns, login = login)
            assertTrue("fakeIp" !in config && "h2StreamReceiveWindow" !in config && "routeOnly" !in config)
        }
    }

    @Test fun theForwardRuleIsCoreJsonsAlone() {
        // Go's Xray refuses a `dns` rule action it does not know, so the
        // configs the Go engines run never carry one: XHTTP's xray.json and
        // Phase 0's sing-box, under either routing.
        val forward = "\"action\":\"forward\""
        val xhttp = spec(IosCoreLine.XhttpReality) as OutboundSpec.Vless
        for (answersDns in listOf(false, true)) {
            assertTrue(forward !in XrayConfig.buildXhttp(xhttp, answersDns = answersDns, login = login))
        }
        assertTrue(forward !in XrayConfig.buildXhttp(xhttp, routing = bypass, geodata = lists, login = login))
        for (line in IosCoreLine.entries) for (routing in routings) {
            if (line != IosCoreLine.XhttpReality && line in firstSix) {
                assertTrue(forward !in SingBoxConfig.buildIosSocks(spec(line), login, routing = routing), "$line $routing")
            }
            assertTrue(forward in text(line, routing), "$line $routing")
        }
    }
}
