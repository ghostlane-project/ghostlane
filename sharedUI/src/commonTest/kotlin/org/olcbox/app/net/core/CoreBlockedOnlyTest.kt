package org.olcbox.app.net.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.CustomRules
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.IosCoreFixtures
import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.Routing
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.XrayRustSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Only blocked sites" on the Rust core (CoreBlockedOnly): Re:filter's names
 * and addresses by the line, everything else straight out, the user's own
 * rules first; the list read from refilter.dat beside the core, named twice.
 */
class CoreBlockedOnlyTest {
    private val dns = DirectDns.Servers(listOf("10.20.30.40"))
    private val login = CoreFixtures.login
    private val reality = IosCoreFixtures.spec(IosCoreLine.RealityVision)

    private fun rules(direct: List<String> = emptyList(), tunnel: List<String> = emptyList()) =
        Routing.Rules("/unused", dns, Routing.Policy.BlockedOnly, CustomRules.of(direct, tunnel))

    private fun text(routing: Routing = rules(), refilterDat: Boolean = true, spec: org.olcbox.app.net.OutboundSpec = reality): String =
        CoreConfig.build(spec, login, routing, refilterDat = refilterDat)

    private fun build(routing: Routing = rules()): JsonObject = Json.parseToJsonElement(text(routing)).jsonObject

    private fun JsonObject.outbounds() = getValue("outbounds").jsonArray.map { it.jsonObject }
    private fun JsonObject.routing() = getValue("routing").jsonObject
    private fun JsonObject.routingRules() = routing().getValue("rules").jsonArray.map { it.jsonObject }
    private fun JsonObject.dnsOut() = outbounds().single { it.getValue("tag").jsonPrimitive.content == "dns-out" }.getValue("settings").jsonObject
    private fun JsonObject.dnsRules() = dnsOut().getValue("rules").jsonArray.map { it.jsonObject }
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val hijack53 = json("""{"type":"field","inboundTag":["in"],"network":"tcp,udp","port":53,"outboundTag":"dns-out"}""")
    private val dnsDirect = json("""{"type":"field","inboundTag":["dns-direct"],"outboundTag":"direct"}""")
    private val privateNets = json(
        """{"type":"field","ip":[${XrayConfig.PRIVATE_RANGES.joinToString(",") { "\"$it\"" }}],"outboundTag":"direct"}"""
    )
    private val listedNames = json("""{"type":"field","domain":["ext:refilter.dat:domains"],"outboundTag":"out"}""")
    private val listedAddresses = json("""{"type":"field","ip":["ext:refilter.dat:ips"],"outboundTag":"out"}""")
    private val everythingElse = json("""{"type":"field","ip":["0.0.0.0/0","::/0"],"outboundTag":"direct"}""")
    private val fakeTheListed = json("""{"action":"hijack","domain":["ext:refilter.dat:domains"],"rCode":5}""")
    private val askTheNetwork = json("""{"action":"direct"}""")

    @Test fun theListIsTheBundledFileByTheCodesItHolds() {
        assertEquals(XrayGeodata.REFILTER, CoreBlockedOnly.FILE)
        assertEquals("ext:refilter.dat:domains", CoreBlockedOnly.NAMES)
        assertEquals("ext:refilter.dat:ips", CoreBlockedOnly.ADDRESSES)
    }

    @Test fun aCoreNotHandedTheFileIsRefusedTheModeWithAReason() {
        val e = assertFailsWith<IllegalArgumentException> { text(refilterDat = false) }
        assertEquals(CoreConfig.BLOCKED_ONLY_REFUSAL, e.message)
        // The iPhone never had the mode, and its builder hands the core no file.
        val iphone = assertFailsWith<IllegalArgumentException> { XrayConfig.buildIosCore(reality, login, rules(), IosCoreFixtures.lists) }
        assertEquals(CoreConfig.BLOCKED_ONLY_REFUSAL, iphone.message)
    }

    @Test fun theListedGoByTheLineAndEverythingElseStraightOut() {
        val config = build()
        assertEquals(listOf("out", "direct", "dns-out"), config.outbounds().map { it.getValue("tag").jsonPrimitive.content })
        assertEquals(listOf(hijack53, dnsDirect, privateNets, listedNames, listedAddresses, everythingElse), config.routingRules())
        // A name no rule claims is resolved on the network and its address
        // asked, as the Go cores' `resolve`; one that does not resolve rides
        // the first outbound, the line.
        assertEquals("IPIfNonMatch", config.routing().getValue("domainStrategy").jsonPrimitive.content)
        assertEquals(json("""[{"tag":"dns-direct","address":"10.20.30.40","port":53}]"""), config.getValue("dns").jsonObject["servers"])
    }

    @Test fun aListedNameIsFakedAndEveryOtherNameAskedOfTheNetwork() {
        val config = build()
        assertEquals(listOf(fakeTheListed, askTheNetwork), config.dnsRules())
        assertEquals("10.20.30.40", config.dnsOut().getValue("rewriteAddress").jsonPrimitive.content)
        assertEquals(53, config.dnsOut().getValue("rewritePort").jsonPrimitive.content.toInt())
        assertTrue(config.getValue("dns").jsonObject.getValue("fakeIp").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean())
    }

    @Test fun theUsersOwnRulesComeFirst() {
        val config = build(rules(direct = listOf("bank.example", "10.8.0.0/16"), tunnel = listOf("x.example", "203.0.113.0/24")))
        assertEquals(
            listOf(
                hijack53, dnsDirect, privateNets,
                json("""{"type":"field","domain":["domain:bank.example"],"outboundTag":"direct"}"""),
                json("""{"type":"field","ip":["10.8.0.0/16"],"outboundTag":"direct"}"""),
                json("""{"type":"field","domain":["domain:x.example"],"outboundTag":"out"}"""),
                json("""{"type":"field","ip":["203.0.113.0/24"],"outboundTag":"out"}"""),
                listedNames, listedAddresses, everythingElse,
            ),
            config.routingRules()
        )
        // A tunnel name of the user's as under a bypass: a fake A, every
        // other type but HTTPS through the line.
        assertEquals(
            listOf(
                json("""{"action":"direct","domain":["domain:bank.example"]}"""),
                json(
                    """{"action":"forward","qType":"2-27,29-64,66-65535","domain":["domain:x.example"],""" +
                        """"address":"${XrayConfig.REMOTE_DNS_SERVER}","port":53,"outboundTag":"out"}"""
                ),
                json("""{"action":"hijack","domain":["domain:x.example"],"rCode":5}"""),
                fakeTheListed, askTheNetwork,
            ),
            config.dnsRules()
        )
    }

    @Test fun theListIsNamedTwiceAndCarriedInNone() {
        // Each rule that names the names costs 81,000 of the core's 250,000
        // matchers a config: the routing's and the DNS's, no third.
        val config = text(rules(direct = listOf("bank.example"), tunnel = listOf("x.example")))
        assertEquals(2, Regex(Regex.escape(CoreBlockedOnly.NAMES)).findAll(config).count())
        assertEquals(1, Regex(Regex.escape(CoreBlockedOnly.ADDRESSES)).findAll(config).count())
        // Read from the file, not written into the config.
        assertTrue(config.length < 8 * 1024, "the config is ${config.length} bytes")
        assertFalse("geosite:" in config || "geoip:" in config)
    }

    @Test fun everyKindOfLineWritesTheModeAsTheCoreReadsIt() {
        val routing = rules(direct = listOf("bank.example", "10.8.0.0/16"), tunnel = listOf("x.example", "203.0.113.0/24"))
        for (case in CoreFixtures.cases) {
            val config = Json.parseToJsonElement(text(routing, spec = case.spec())).jsonObject
            assertEquals(emptyList<String>(), XrayRustSurface.violations(config), case.name)
            assertEquals(listOf(listedNames, listedAddresses, everythingElse), config.routingRules().takeLast(3), case.name)
            assertEquals(listOf(fakeTheListed, askTheNetwork), config.dnsRules().takeLast(2), case.name)
            for (rule in config.routingRules()) {
                assertFalse(rule.containsKey("domain") && rule.containsKey("ip"), "Xray ANDs a rule's fields: $rule")
            }
        }
    }

    @Test fun theFileChangesNoOtherMode() {
        val lists = CoreFixtures.lists
        val others = listOf(
            Routing.Global to null,
            Routing.Rules("/unused", dns, Routing.Policy.Tunnel, CustomRules.of(listOf("bank.example"), emptyList())) to null,
            Routing.Rules("/unused", dns, Routing.Policy.Bypass("ru"), CustomRules.of(listOf("bank.example"), listOf("x.example"))) to lists,
        )
        for ((routing, geodata) in others) {
            assertEquals(
                CoreConfig.build(reality, login, routing, geodata),
                CoreConfig.build(reality, login, routing, geodata, refilterDat = true),
                "$routing"
            )
            assertFalse("refilter" in CoreConfig.build(reality, login, routing, geodata, refilterDat = true), "$routing")
        }
    }
}
