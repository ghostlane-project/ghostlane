package org.olcbox.app.vpn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** LAN sharing's config for ghostlane-engine: what listens, who may use it, and where it leads. */
class DesktopLanProxyRustTest {
    private val upstream = SubscriptionFetchProxy("127.0.0.1", 10808, "internal", "secret")

    private fun config(
        host: String = "192.168.50.10",
        port: Int = 10818,
        username: String = "guest",
        password: String = "generated-password",
        upstream: SubscriptionFetchProxy = this.upstream,
        ownershipPort: Int = 18888,
    ): String = DesktopLanProxy.rustConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        upstream = upstream,
        ownershipPort = ownershipPort,
        ownershipUsername = "owner-user",
        ownershipPassword = "owner-password",
    )

    private fun inbound(document: JsonObject, tag: String): JsonObject =
        document["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == tag }

    @Test
    fun theLanListenerDemandsItsOwnLoginOnThePrivateAddress() {
        val document = Json.parseToJsonElement(config()).jsonObject
        val lan = inbound(document, "lan-in")
        assertEquals("socks", lan["protocol"]!!.jsonPrimitive.content)
        assertEquals("192.168.50.10", lan["listen"]!!.jsonPrimitive.content)
        assertEquals(10818, lan["port"]!!.jsonPrimitive.int)
        val settings = lan["settings"]!!.jsonObject
        assertEquals("password", settings["auth"]!!.jsonPrimitive.content)
        assertTrue(settings["udp"]!!.jsonPrimitive.boolean, "a LAN client asks for a datagram relay")
        assertTrue(settings["allowUnauthenticatedLan"]!!.jsonPrimitive.boolean, "the core's name for a listener off loopback")
        val account = settings["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("guest", account["user"]!!.jsonPrimitive.content)
        assertEquals("generated-password", account["pass"]!!.jsonPrimitive.content)
    }

    @Test
    fun theOwnershipListenerIsOnLoopbackWithALoginOfItsOwn() {
        val document = Json.parseToJsonElement(config()).jsonObject
        val owner = inbound(document, "in")
        assertEquals("127.0.0.1", owner["listen"]!!.jsonPrimitive.content)
        assertEquals(18888, owner["port"]!!.jsonPrimitive.int)
        val settings = owner["settings"]!!.jsonObject
        assertEquals("password", settings["auth"]!!.jsonPrimitive.content)
        val account = settings["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("owner-user", account["user"]!!.jsonPrimitive.content)
        assertEquals("owner-password", account["pass"]!!.jsonPrimitive.content)
    }

    @Test
    fun everyConnectionLeadsToTheSessionsPortWithItsLogin() {
        val document = Json.parseToJsonElement(config()).jsonObject
        val outbound = document["outbounds"]!!.jsonArray.map { it.jsonObject }.single()
        assertEquals("socks", outbound["protocol"]!!.jsonPrimitive.content)
        val settings = outbound["settings"]!!.jsonObject
        assertEquals("127.0.0.1", settings["address"]!!.jsonPrimitive.content)
        assertEquals(10808, settings["port"]!!.jsonPrimitive.int)
        assertEquals("internal", settings["user"]!!.jsonPrimitive.content)
        assertEquals("secret", settings["pass"]!!.jsonPrimitive.content)
        val tag = outbound["tag"]!!.jsonPrimitive.content
        val rule = document["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }.single()
        assertEquals(tag, rule["outboundTag"]!!.jsonPrimitive.content)
        assertEquals(setOf("lan-in", "in"), rule["inboundTag"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun aSessionPortWithNoLoginIsWrittenWithNone() {
        val open = SubscriptionFetchProxy("127.0.0.1", 10808)
        val settings = Json.parseToJsonElement(config(upstream = open)).jsonObject["outbounds"]!!
            .jsonArray.single().jsonObject["settings"]!!.jsonObject
        assertFalse("user" in settings)
        assertFalse("pass" in settings)
    }

    @Test
    fun theChainSniffsNothingSoThatNoServerWaitsOnAClientThatSpeaksSecond() {
        val document = Json.parseToJsonElement(config()).jsonObject
        for (tag in listOf("lan-in", "in")) {
            val sniffing = assertNotNull(inbound(document, tag)["sniffing"], tag).jsonObject
            assertFalse(sniffing["enabled"]!!.jsonPrimitive.boolean, "sniffing on $tag")
        }
    }

    @Test
    fun noLoginInTheConfigReachesALogLevelThatWouldPrintIt() {
        val log = Json.parseToJsonElement(config()).jsonObject["log"]!!.jsonObject
        assertEquals("warning", log["loglevel"]!!.jsonPrimitive.content)
        assertEquals("none", log["access"]!!.jsonPrimitive.content)
    }

    @Test
    fun wildcardPublicBlankAndClashingListenersAreRefused() {
        assertFailsWith<IllegalArgumentException> { config(host = "0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { config(host = "203.0.113.5") }
        assertFailsWith<IllegalArgumentException> { config(host = "::") }
        assertFailsWith<IllegalArgumentException> { config(username = "", password = "") }
        assertFailsWith<IllegalArgumentException> { config(username = "guest", password = " ") }
        assertFailsWith<IllegalArgumentException> { config(port = 80) }
        assertFailsWith<IllegalArgumentException> { config(port = upstream.port) }
        assertFailsWith<IllegalArgumentException> { config(port = 18888, ownershipPort = 18888) }
    }

    @Test
    fun theGoAndTheRustCoresAreTwoCoresOfOneProxy() {
        val seen = mutableListOf<String>()
        val go = LanCores.go { seen += it }
        val rust = LanCores.rust({ seen += it })
        assertEquals("sing-box", go.name)
        assertEquals("ghostlane-engine", rust.name)
        assertFalse(go.isRunning())
        assertFalse(rust.isRunning())
        go.stopNow()
        rust.stopNow()
    }

    @Test
    fun goIsTheDefault() {
        assertEquals("sing-box", LanCores.create { }.name)
    }
}
