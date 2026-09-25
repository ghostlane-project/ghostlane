package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The login a core's local SOCKS inbound demands (see [SocksLogin]): present in
 * each core's own spelling when given, and absent, exactly as before, when not.
 */
class SocksLoginTest {
    private val login = SocksLogin("gl-user", "gl-pass")

    private fun vless() = OutboundSpec.Vless(
        "u", "1.2.3.4", 443, "sni.x", "PBK", "sid", "chrome",
        "xtls-rprx-vision", TransportSpec.Tcp, "DE"
    )

    private fun xhttp() = OutboundSpec.Vless(
        "u", "1.2.3.4", 443, "sni.x", "PBK", "sid", "chrome", null,
        TransportSpec.Xhttp("/dl", "sni.x", "packet-up"), "T"
    )

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun JsonObject.inbound(): JsonObject = this["inbounds"]!!.jsonArray[0].jsonObject
    private fun JsonObject.outbound(): JsonObject = this["outbounds"]!!.jsonArray[0].jsonObject
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test fun aBlankHalfMeansNoLogin() {
        assertNull(SocksLogin.of("", "p"))
        assertNull(SocksLogin.of("u", ""))
        assertNull(SocksLogin.of("  ", "p"))
        assertEquals(SocksLogin("u", "p"), SocksLogin.of("u", "p"))
    }

    @Test fun singBoxDemandsTheLoginItIsGiven() {
        val inbound = parse(SingBoxConfig.build(vless(), socksPort = 41234, login = login)).inbound()
        assertEquals(41234, inbound.str("listen_port").toInt())
        val users = inbound["users"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, users.size)
        assertEquals("gl-user", users[0].str("username"))
        assertEquals("gl-pass", users[0].str("password"))
    }

    @Test fun singBoxWithoutALoginStaysAsItWas() {
        val json = SingBoxConfig.build(vless(), socksPort = 10808)
        assertFalse("users" in parse(json).inbound(), json)
        assertEquals(json, SingBoxConfig.build(vless(), socksPort = 10808, login = null))
    }

    // The fronted xhttp path: sing-box routes in front of Xray, both on loopback,
    // so both demand the login and the front sends it on to Xray.
    @Test fun aChainDemandsTheLoginAndSendsItOnToTheCoreBehindIt() {
        val config = parse(
            SingBoxConfig.buildSocksChain(
                41000,
                socksPort = 41001,
                username = login.username,
                password = login.password,
                login = login,
            )
        )
        val users = config.inbound()["users"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("gl-user" to "gl-pass"), users.map { it.str("username") to it.str("password") })
        val out = config.outbound()
        assertEquals(41000, out.str("server_port").toInt())
        assertEquals("gl-user", out.str("username"))
        assertEquals("gl-pass", out.str("password"))
    }

    @Test fun xrayDemandsTheLoginInItsOwnSpelling() {
        val settings = parse(XrayConfig.buildXhttp(xhttp(), socksPort = 41002, login = login))
            .inbound()["settings"]!!.jsonObject
        assertEquals("password", settings.str("auth"))
        val accounts = settings["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("gl-user" to "gl-pass"), accounts.map { it.str("user") to it.str("pass") })
        // hev relays the tun's UDP by UDP ASSOCIATE; a login must not cost it that.
        assertEquals("true", settings.str("udp"))
    }

    @Test fun xrayWithoutALoginStaysAsItWas() {
        val json = XrayConfig.buildXhttp(xhttp(), socksPort = 10810)
        val settings = parse(json).inbound()["settings"]!!.jsonObject
        assertFalse("auth" in settings, json)
        assertFalse("accounts" in settings, json)
        assertEquals(json, XrayConfig.buildXhttp(xhttp(), socksPort = 10810, login = null))
    }
}
