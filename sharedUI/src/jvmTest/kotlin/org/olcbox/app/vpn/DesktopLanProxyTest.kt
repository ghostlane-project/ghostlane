package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopLanProxyTest {
    private val upstream = SubscriptionFetchProxy("127.0.0.1", 10808, "internal", "secret")

    private fun chain(
        host: String = "192.168.50.10",
        port: Int = 10818,
        username: String = "guest",
        password: String = "generated-password",
        upstream: SubscriptionFetchProxy = this.upstream,
    ) = Json.parseToJsonElement(
        DesktopLanProxy.chainConfig(
            host, port, username, password, upstream,
            ownershipPort = 18888, ownershipUsername = "owner-user", ownershipPassword = "owner-password",
        )
    ).jsonObject

    private fun JsonObject.inbound(tag: String) =
        getValue("inbounds").jsonArray.map { it.jsonObject }.single { it.getValue("tag").jsonPrimitive.content == tag }

    @Test
    fun theLanListenerRequiresItsOwnLoginAndTheOnlyWayOutIsTheSessionsPort() {
        val document = chain()
        val lan = document.inbound("lan-in")
        val account = lan.getValue("settings").jsonObject.getValue("accounts").jsonArray.single().jsonObject
        assertEquals("192.168.50.10", lan.getValue("listen").jsonPrimitive.content)
        assertEquals("10818", lan.getValue("port").jsonPrimitive.content)
        assertEquals("password", lan.getValue("settings").jsonObject.getValue("auth").jsonPrimitive.content)
        assertEquals("guest", account.getValue("user").jsonPrimitive.content)
        assertEquals("generated-password", account.getValue("pass").jsonPrimitive.content)
        val way = document.getValue("outbounds").jsonArray.first().jsonObject
        assertEquals("socks", way.getValue("protocol").jsonPrimitive.content)
        val settings = way.getValue("settings").jsonObject
        assertEquals("127.0.0.1", settings.getValue("address").jsonPrimitive.content)
        assertEquals("10808", settings.getValue("port").jsonPrimitive.content)
        assertEquals("internal", settings.getValue("user").jsonPrimitive.content)
        assertEquals("secret", settings.getValue("pass").jsonPrimitive.content)
    }

    @Test
    fun aSessionPortWithNoLoginIsChainedWithNone() {
        val way = chain(upstream = SubscriptionFetchProxy("127.0.0.1", 10808)).getValue("outbounds").jsonArray.first().jsonObject
        val settings = way.getValue("settings").jsonObject
        assertEquals("10808", settings.getValue("port").jsonPrimitive.content)
        assertFalse("user" in settings || "pass" in settings, settings.toString())
    }

    @Test
    fun publicWildcardAndMissingCredentialsAreRejected() {
        assertFailsWith<IllegalArgumentException> { chain(host = "0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { chain(host = "203.0.113.5") }
        assertFailsWith<IllegalArgumentException> { chain(host = "192.168.1.5", username = "", password = "") }
        assertFailsWith<IllegalArgumentException> { chain(host = "192.168.1.5", port = upstream.port) }
    }

    @Test
    fun ownershipInboundIsSeparateAndAuthenticated() {
        val owner = chain().inbound("in")
        val account = owner.getValue("settings").jsonObject.getValue("accounts").jsonArray.single().jsonObject
        assertEquals("127.0.0.1", owner.getValue("listen").jsonPrimitive.content)
        assertEquals("18888", owner.getValue("port").jsonPrimitive.content)
        assertEquals("owner-user", account.getValue("user").jsonPrimitive.content)
        assertEquals("owner-password", account.getValue("pass").jsonPrimitive.content)
    }

    @Test
    fun virtualAdaptersAreNeverOfferedForLanSharing() {
        for (name in listOf("docker0", "br-acde12", "vEthernet (WSL)", "vboxnet0", "VMware Network Adapter VMnet8")) {
            assertTrue(DesktopLanProxy.isVirtualAdapter(name), name)
        }
        assertFalse(DesktopLanProxy.isVirtualAdapter("Intel(R) Wi-Fi 6 AX201"))
        assertFalse(DesktopLanProxy.isVirtualAdapter("Realtek PCIe Ethernet"))
    }

    @Test
    fun generatedLanCredentialsAreIndependentAndBounded() {
        val first = DesktopSocksProxySettings(username = "core", password = "core-secret")
            .withGeneratedLanCredentials()
        val second = first.withGeneratedLanCredentials()
        assertTrue(first.lanUsername.startsWith("ghostlane-"))
        assertTrue(first.lanUsername.length <= DesktopSocksProxySettings.MAX_CREDENTIAL_LENGTH)
        assertEquals(24, first.lanPassword.length)
        assertNotEquals(first.lanPassword, second.lanPassword)
        assertEquals("core-secret", first.password)
    }

    @Test
    fun lanPortNeverCollidesWithLocalCorePort() {
        val settings = DesktopSocksProxySettings(port = 10818, lanPort = 10818).normalized()
        assertNotEquals(settings.port, settings.lanPort)
    }
}
