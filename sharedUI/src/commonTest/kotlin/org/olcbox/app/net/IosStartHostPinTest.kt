package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.core.CoreDns
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.lines.VlessShapes
import org.olcbox.app.net.core.lines.VmessFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A line whose server is named by a hostname starts on the iPhone
 * ([IosCore.start]): written for an address the extension fills in once it
 * has resolved the name outside the tunnel (HostPin.swift), with the name
 * kept as the TLS server name and the Host, instead of refused.
 */
class IosStartHostPinTest {
    private val login = IosCoreFixtures.login

    /** The address the app writes for the server until the extension pins it. */
    private val placeholder = IosCore.HOST_PIN_PLACEHOLDER

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

    /** A link of each kind, its server named by a hostname, by what it is. */
    private val named: Map<String, String> = mapOf(
        "webtunnel-ws" to "vless://33333333-3333-3333-3333-333333333333@edge.example.com:443" +
            "?security=tls&encryption=none&fp=chrome&type=ws&path=%2Fv#wt",
        "webtunnel-httpupgrade" to "vless://33333333-3333-3333-3333-333333333333@edge.example.com:443" +
            "?security=tls&encryption=none&sni=front.example&host=cdn.example.net&type=httpupgrade&path=%2Fu&fp=chrome#up",
        "reality" to "vless://11111111-1111-1111-1111-111111111111@de.example.com:443" +
            "?security=reality&encryption=none&pbk=${IosCoreFixtures.PUBLIC_KEY}" +
            "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#reality",
        "hysteria2" to "hysteria2://PASSWORD123@hy.example.com:443?pinSHA256=${IosCoreFixtures.PIN}#hy",
        "trojan" to "trojan://secret@tr.example.com:443?security=tls&type=ws&path=%2Ft#trojan",
        "shadowsocks" to "ss://chacha20-ietf-poly1305:pw-chacha@ss.example.com:8388#ss",
        "vmess" to VmessFixture.link(
            "ps" to "vm", "add" to "vm.example.com", "scy" to "auto", "net" to "ws", "path" to "/vm", "tls" to "tls",
        ),
    )

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.out() = this["outbounds"]!!.jsonArray.first().jsonObject

    /** The outbound's server address, in whichever form its protocol writes it. */
    private fun JsonObject.server(): String {
        val settings = out().obj("settings")
        val servers = settings["vnext"] ?: settings["servers"]
        return servers?.jsonArray?.first()?.jsonObject?.str("address") ?: settings.str("address")
    }

    @Test fun aServerNamedByAHostnameStartsOnTheCoreWrittenForThePlaceholder() {
        for ((case, link) in named) {
            val spec = assertNotNull(LinkParser.parse(link), case)
            assertTrue(!XrayConfig.isIpLiteral(spec.host), case)
            val start = assertIs<IosStart.Core>(IosCore.start(located(link)), case)
            assertEquals(CoreLines.pinHost(spec, placeholder), start.spec, case)
            assertEquals(IosCore.lineOf(spec), start.line, case)
            val config = Json.parseToJsonElement(XrayConfig.buildIosCore(start.spec, login)).jsonObject
            assertEquals(placeholder, config.server(), case)
        }
    }

    @Test fun theCoreJsonKeepsTheNameAsTheServerNameAndTheHost() {
        fun stream(case: String): JsonObject {
            val start = assertIs<IosStart.Core>(IosCore.start(located(named.getValue(case))), case)
            return Json.parseToJsonElement(XrayConfig.buildIosCore(start.spec, login)).jsonObject.out().obj("streamSettings")
        }
        val ws = stream("webtunnel-ws")
        assertEquals("edge.example.com", ws.obj("tlsSettings").str("serverName"))
        assertEquals("edge.example.com", ws.obj("wsSettings").str("host"))
        // A server name and a Host of the link's own are kept.
        val upgrade = stream("webtunnel-httpupgrade")
        assertEquals("front.example", upgrade.obj("tlsSettings").str("serverName"))
        assertEquals("cdn.example.net", upgrade.obj("httpupgradeSettings").str("host"))
        // Reality's server name is the site it borrows, never the server's.
        assertEquals("www.microsoft.com", stream("reality").obj("realitySettings").str("serverName"))
        assertEquals("hy.example.com", stream("hysteria2").obj("tlsSettings").str("serverName"))
        val trojan = stream("trojan")
        assertEquals("tr.example.com", trojan.obj("tlsSettings").str("serverName"))
        assertEquals("tr.example.com", trojan.obj("wsSettings").str("host"))
        val vmess = stream("vmess")
        assertEquals("vm.example.com", vmess.obj("tlsSettings").str("serverName"))
        assertEquals("vm.example.com", vmess.obj("wsSettings").str("host"))
    }

    @Test fun aLineTheCoreRefusesForAnotherReasonIsRefusedForThatReason() {
        val oldFlow = "vless://u@de.example.com:443?security=reality&pbk=${IosCoreFixtures.PUBLIC_KEY}" +
            "&sni=a.example&flow=xtls-rprx-direct&type=tcp#old-flow"
        val asAnAddress = assertNotNull(LinkParser.parse(oldFlow.replace("de.example.com", "192.0.2.7")))
        val reason = assertNotNull(IosCore.refusal(asAnAddress))
        assertEquals(IosStart.Refused("Cannot start DE line: $reason"), IosCore.start(located(oldFlow)))
    }

    @Test fun aPlainVlessLineNamedByAHostnameIsNotRefusedForItsName() {
        val plain = "vless://55555555-5555-5555-5555-555555555555@nas.example.com:80?security=none&encryption=none&type=tcp#plain"
        val refused = assertIs<IosStart.Refused>(IosCore.start(located(plain)))
        assertTrue(CoreRefusal.HOSTNAME !in refused.sentence, refused.sentence)
        assertTrue(refused.sentence.startsWith("Cannot start DE line: "), refused.sentence)
        // It says what it is: the core carries VLESS in the clear only to a
        // private server, and the name's address is learnt in the extension.
        assertEquals(IosStart.Refused("Cannot start DE line: ${IosCore.PLAIN_NAMED}"), IosCore.start(located(plain)))
    }

    @Test fun theStartNamesTheServerForTheExtensionAndTellsTheLog() {
        for ((case, link) in named) {
            val spec = assertNotNull(LinkParser.parse(link), case)
            val start = assertIs<IosStart.Core>(IosCore.start(located(link)), case)
            assertEquals(IosHostPin(spec.host, "Cannot start DE line: ${IosCore.HOST_UNRESOLVED}"), start.hostPin, case)
            assertTrue(IosCore.HOST_PIN_NOTE in start.notes, case)
        }
        // A location with no name of its own is named by what it is.
        val unnamed = assertIs<IosStart.Core>(IosCore.start(located(named.getValue("trojan"), name = " ")))
        assertEquals("Cannot start this line: ${IosCore.HOST_UNRESOLVED}", unnamed.hostPin?.unresolved)
        // A line given by its address names no server and tells nothing of one.
        for (line in IosCoreLine.entries) {
            val start = assertIs<IosStart.Core>(IosCore.start(located(IosCoreFixtures.link(line))), "$line")
            assertNull(start.hostPin, "$line")
            assertTrue(IosCore.HOST_PIN_NOTE !in start.notes, "$line")
        }
    }

    @Test fun theHandOverNamesTheServerAtTheHeadAndChangesNothingElse() {
        val start = assertIs<IosStart.Core>(IosCore.start(located(named.getValue("webtunnel-ws"))))
        val built = XrayConfig.buildIosCore(start.spec, login)
        val handed = IosCore.handOver(start, built)
        // The head the extension reads (HostPin.request), in this order and
        // with no space: HostPinTests.swift is written to the same text.
        val head = "{\"meta\":{\"hostPin\":{\"server\":\"edge.example.com\"," +
            "\"unresolved\":\"Cannot start DE line: ${IosCore.HOST_UNRESOLVED}\"}},"
        assertTrue(handed.startsWith(head), handed.take(240))
        assertEquals(built.substring(1), handed.removePrefix(head), "the builder's config follows, as it was written")
        assertTrue(handed.removePrefix(head).startsWith("\"log\":"), "then `log`, as the Swift test's sample has it")
        val root = Json.parseToJsonElement(handed).jsonObject
        assertEquals("edge.example.com", root.obj("meta").obj("hostPin").str("server"))
        assertEquals(Json.parseToJsonElement(built).jsonObject, JsonObject(root - "meta"))
        // A line given by its address is handed over as the builder wrote it.
        val byAddress = assertIs<IosStart.Core>(IosCore.start(located(IosCoreFixtures.link(IosCoreLine.WebTunnelWs))))
        val plain = XrayConfig.buildIosCore(byAddress.spec, login)
        assertEquals(plain, IosCore.handOver(byAddress, plain))
        // A config not written for the placeholder is not handed over as a pinned one.
        assertFailsWith<IllegalArgumentException> { IosCore.handOver(start, plain) }
    }

    @Test fun aLinesNameWithQuotesAndBracesIsWrittenAsJson() {
        val start = assertIs<IosStart.Core>(IosCore.start(located(named.getValue("hysteria2"), name = "My \"best\" {line} \\")))
        val handed = IosCore.handOver(start, XrayConfig.buildIosCore(start.spec, login))
        val meta = Json.parseToJsonElement(handed).jsonObject.obj("meta").obj("hostPin")
        assertEquals("Cannot start My \"best\" {line} \\: ${IosCore.HOST_UNRESOLVED}", meta.str("unresolved"))
        assertEquals("hy.example.com", meta.str("server"))
    }

    @Test fun underBypassRussiaThePlaceholderIsTheServersAlone() {
        val start = assertIs<IosStart.Core>(IosCore.start(located(named.getValue("webtunnel-ws"))))
        val bypass = Routing.BypassRussia("unused-on-the-rust-core", DirectDns.Placeholder)
        val handed = IosCore.handOver(
            start,
            XrayConfig.buildIosCore(start.spec, login, bypass, geodata = IosCoreFixtures.lists),
        )
        assertEquals(1, Regex(Regex.escape("\"$placeholder\"")).findAll(handed).count(), "the server's address, once")
        assertTrue("\"${CoreDns.DIRECT_DNS_PLACEHOLDER}\"" in handed, "the direct resolver's own placeholder beside it")
    }

    @Test fun thePlaceholderIsAnAddressThatCarriesNothingOnItsOwn() {
        assertTrue(XrayConfig.isIpLiteral(placeholder))
        assertTrue(placeholder != CoreDns.DIRECT_DNS_PLACEHOLDER)
        // Not a private address: a plain VLESS line is not carried to it.
        val plainThere = "vless://55555555-5555-5555-5555-555555555555@$placeholder:80?security=none&encryption=none&type=tcp#p"
        assertEquals(VlessShapes.PLAIN_NEEDS_PRIVATE_SERVER, IosCore.refusal(assertNotNull(LinkParser.parse(plainThere))))
    }
}
