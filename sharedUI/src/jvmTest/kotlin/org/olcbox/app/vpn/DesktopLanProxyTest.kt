package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
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

    @Test
    fun lanListenerRequiresItsOwnCredentialsAndKeepsUpstreamCredentials() {
        val document = Json.parseToJsonElement(
            DesktopLanProxy.config(
                host = "192.168.50.10",
                port = 10818,
                username = "guest",
                password = "generated-password",
                upstream = upstream
            )
        ).jsonObject

        val inbound = document["inbounds"]!!.jsonArray.single().jsonObject
        val user = inbound["users"]!!.jsonArray.single().jsonObject
        assertEquals("192.168.50.10", inbound["listen"]!!.jsonPrimitive.content)
        assertEquals("guest", user["username"]!!.jsonPrimitive.content)
        assertEquals("generated-password", user["password"]!!.jsonPrimitive.content)
        val outbound = document["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("internal", outbound["username"]!!.jsonPrimitive.content)
        assertEquals("secret", outbound["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun publicWildcardAndMissingCredentialsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            DesktopLanProxy.config("0.0.0.0", 10818, "guest", "password", upstream)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopLanProxy.config("203.0.113.5", 10818, "guest", "password", upstream)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopLanProxy.config("192.168.1.5", 10818, "", "", upstream)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopLanProxy.config("192.168.1.5", upstream.port, "guest", "password", upstream)
        }
    }

    @Test
    fun ownershipInboundIsSeparateAndAuthenticated() {
        val document = Json.parseToJsonElement(
            DesktopLanProxy.config(
                host = "192.168.50.10",
                port = 10818,
                username = "guest",
                password = "generated-password",
                upstream = upstream,
                ownershipPort = 18888,
                ownershipUsername = "owner-user",
                ownershipPassword = "owner-password"
            )
        ).jsonObject
        val inbounds = document["inbounds"]!!.jsonArray.map { it.jsonObject }
        val owner = inbounds.single { it["tag"]!!.jsonPrimitive.content == "owner-in" }
        val ownerUser = owner["users"]!!.jsonArray.single().jsonObject
        assertEquals("127.0.0.1", owner["listen"]!!.jsonPrimitive.content)
        assertEquals("18888", owner["listen_port"]!!.jsonPrimitive.content)
        assertEquals("owner-user", ownerUser["username"]!!.jsonPrimitive.content)
        assertEquals("owner-password", ownerUser["password"]!!.jsonPrimitive.content)
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
