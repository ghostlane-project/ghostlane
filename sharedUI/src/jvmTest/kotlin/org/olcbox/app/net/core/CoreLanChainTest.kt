package org.olcbox.app.net.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.IosCoreFixtures
import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.SocksLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The core of a LAN listener that is not in the session's own core (CoreLanChain). Pure. */
class CoreLanChainTest {
    private val own = CoreInbound.Tunnel(18888, SocksLogin("owner", "owner-pass"))
    private val lan = CoreInbound.Lan("192.168.50.10", 10818, SocksLogin("guest", "lan-pass"))
    private val plan = CoreInbound.Plan(own, lan)
    private val login = SocksLogin("room-user", "room-pass")

    private fun build(login: SocksLogin? = this.login, port: Int = 10812): JsonObject =
        Json.parseToJsonElement(CoreLanChain.build(plan, "127.0.0.1", port, login)).jsonObject

    @Test fun theLanInboundIsTheOneACoreLineGetsAndTheWayOutIsOneSocksPort() {
        val config = build()
        val inbounds = config.getValue("inbounds").jsonArray.map { it.jsonObject }
        assertEquals(listOf("in", "lan-in"), inbounds.map { it.getValue("tag").jsonPrimitive.content })
        val line = Json.parseToJsonElement(CoreInbound.build(IosCoreFixtures.spec(IosCoreLine.RealityVision), plan))
            .jsonObject.getValue("inbounds").jsonArray.map { it.jsonObject }
        assertEquals(line, inbounds, "the same two inbounds a core line holds")
        val outbounds = config.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertEquals(listOf("out", "dns-out"), outbounds.map { it.getValue("tag").jsonPrimitive.content })
        val way = outbounds.first()
        assertEquals("socks", way.getValue("protocol").jsonPrimitive.content)
        val settings = way.getValue("settings").jsonObject
        assertEquals("127.0.0.1", settings.getValue("address").jsonPrimitive.content)
        assertEquals("10812", settings.getValue("port").jsonPrimitive.content)
        assertEquals("room-user", settings.getValue("user").jsonPrimitive.content)
        assertEquals("room-pass", settings.getValue("pass").jsonPrimitive.content)
    }

    @Test fun aPortWithNoLoginIsChainedWithNone() {
        for (none in listOf(null, SocksLogin("", ""))) {
            val settings = build(none).getValue("outbounds").jsonArray.first().jsonObject.getValue("settings").jsonObject
            assertFalse("user" in settings || "pass" in settings, settings.toString())
        }
    }

    @Test fun noRuleSendsAnythingButTheNamesQueriesAnywhereElse() {
        val rules = build().getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals(listOf("dns-out"), rules.map { it.getValue("outboundTag").jsonPrimitive.content })
    }

    @Test fun whatTheListenerOrThePortRefusesIsRefusedWithASentence() {
        assertNull(CoreLanChain.refusal(plan, 10812, login))
        assertEquals(CoreInbound.PORT_REFUSAL, CoreLanChain.refusal(plan, 0, login))
        assertEquals(CoreInbound.LAN_PORT_REFUSAL, CoreLanChain.refusal(plan, 10818, login))
        assertEquals(CoreInbound.HALF_LOGIN_REFUSAL, CoreLanChain.refusal(plan, 10812, SocksLogin("user", "")))
        assertEquals(CoreInbound.LONG_LOGIN_REFUSAL, CoreLanChain.refusal(plan, 10812, SocksLogin("u".repeat(256), "p")))
        val publicLan = CoreInbound.Plan(own, CoreInbound.Lan("203.0.113.5", 10818, SocksLogin("guest", "lan-pass")))
        assertEquals(CoreInbound.LAN_ADDRESS_REFUSAL, CoreLanChain.refusal(publicLan, 10812, login))
        assertFailsWith<IllegalArgumentException> { CoreLanChain.build(publicLan, "127.0.0.1", 10812, login) }
    }

    @Test fun noAccessLogNamesTheConnectionsAClientMakes() {
        val log = build().getValue("log").jsonObject
        assertEquals("none", log.getValue("access").jsonPrimitive.content)
        assertEquals("warning", log.getValue("loglevel").jsonPrimitive.content)
    }
}
