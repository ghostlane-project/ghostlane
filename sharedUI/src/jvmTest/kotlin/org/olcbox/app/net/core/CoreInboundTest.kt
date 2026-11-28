package org.olcbox.app.net.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.IosCoreFixtures
import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.XrayRustSurface
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Rust core's inbounds (CoreInbound): proxy mode, the listen address and
 * the LAN inbound with its accounts, each held to what the Go cores are given
 * for the same settings and to what xray-rust's parser takes.
 */
class CoreInboundTest {
    private val tunLogin = SocksLogin("tun-user", "tun-password")
    private val userLogin = SocksLogin("proxy-user", "proxy-password")
    private val lanLogin = SocksLogin("ghostlane-guest", "generated-lan-password")
    private val reality = IosCoreFixtures.spec(IosCoreLine.RealityVision)
    private val xhttp = IosCoreFixtures.spec(IosCoreLine.XhttpReality) as OutboundSpec.Vless
    private val bypass = Routing.BypassRussia("unused-on-the-rust-core", DirectDns.Placeholder)

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun inbounds(json: String): List<JsonObject> = parse(json)["inbounds"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    private fun plan(own: CoreInbound.Own, lan: CoreInbound.Lan? = null) = CoreInbound.Plan(own, lan)
    private fun lan(address: String = "192.168.50.10", port: Int = 10818, login: SocksLogin = lanLogin) =
        CoreInbound.Lan(address, port, login)

    /** A listener as both families of config name it: where, which port, and the logins it takes. */
    private data class Listener(val tag: String?, val listen: String, val port: Int, val logins: List<Pair<String, String>>)

    /** Xray's spelling, the Rust core's: `port`, `settings.accounts[] {user, pass}`. */
    private fun xray(inbound: JsonObject): Listener = Listener(
        inbound.str("tag"),
        inbound.str("listen")!!,
        inbound["port"]!!.jsonPrimitive.int,
        inbound["settings"]?.jsonObject?.get("accounts")?.jsonArray.orEmpty().map {
            it.jsonObject.str("user")!! to it.jsonObject.str("pass")!!
        },
    )

    // The tunnel's inbound is what the core has always been written, and
    // build's writing over gives the writer's config byte for byte, for every
    // case of every kind under Global and under Bypass Russia.
    @Test fun aPlanOfTheTunnelAloneIsTheConfigTheCoreHasAlwaysBeenWritten() {
        for (case in CoreFixtures.cases) {
            val spec = case.spec()
            for (port in listOf(10810, 41001)) {
                assertEquals(
                    CoreConfig.build(spec, tunLogin, socksPort = port),
                    CoreInbound.build(spec, plan(CoreInbound.Tunnel(port, tunLogin))),
                    case.name,
                )
                assertEquals(
                    CoreConfig.build(spec, tunLogin, routing = bypass, geodata = CoreFixtures.lists, socksPort = port),
                    CoreInbound.build(spec, plan(CoreInbound.Tunnel(port, tunLogin)), routing = bypass, geodata = CoreFixtures.lists),
                    case.name,
                )
            }
        }
    }

    // Proxy mode as the cores have always listened in it: 127.0.0.1, the settings' port,
    // no login (the core line never took the proxy settings' login), UDP on.
    @Test fun proxyModeListensOnTheLoopbackPortAndAsksNoLogin() {
        val config = CoreInbound.build(reality, plan(CoreInbound.Proxy(10808)))
        val inbound = inbounds(config).single()
        assertEquals(Listener("in", "127.0.0.1", 10808, emptyList()), xray(inbound))
        assertEquals(
            xray(inbounds(XrayConfig.buildXhttp(xhttp, socksPort = 10808)).single()),
            xray(inbounds(CoreInbound.build(xhttp, plan(CoreInbound.Proxy(10808)))).single()),
        )
        val settings = inbound.obj("settings")
        assertEquals("noauth", settings.str("auth"))
        assertNull(settings["accounts"])
        assertEquals("true", settings.str("udp"))
        assertNull(settings["allowUnauthenticatedLan"])
        // Everything but the inbound is the tunnel's config for the same line.
        val tunnel = parse(CoreConfig.build(reality, tunLogin, socksPort = 10808))
        assertEquals(tunnel - "inbounds", parse(config) - "inbounds")
        assertEquals(tunnel["inbounds"]!!.jsonArray.single().jsonObject.obj("sniffing"), inbound.obj("sniffing"))
    }

    @Test fun proxyModeWithTheUsersLoginDemandsIt() {
        val inbound = inbounds(CoreInbound.build(reality, plan(CoreInbound.Proxy(10808, userLogin)))).single()
        assertEquals(Listener("in", "127.0.0.1", 10808, listOf("proxy-user" to "proxy-password")), xray(inbound))
        assertEquals("password", inbound.obj("settings").str("auth"))
    }

    // Loopback in any spelling the core binds as the address it means; any
    // other address, a wildcard and a name are refused before anything is written.
    @Test fun proxyModesListenAddressIsALoopbackAddress() {
        val taken = mapOf("127.0.0.1" to "127.0.0.1", "127.0.0.2" to "127.0.0.2", "::1" to "::1", "[::1]" to "::1", " 127.0.0.1 " to "127.0.0.1")
        for ((asked, written) in taken) {
            assertNull(CoreInbound.refusal(plan(CoreInbound.Proxy(10808, listen = asked))), asked)
            val inbound = inbounds(CoreInbound.build(reality, plan(CoreInbound.Proxy(10808, listen = asked)))).single()
            assertEquals(written, inbound.str("listen"), asked)
        }
        val refused = listOf(
            "0.0.0.0", "::", "[::]", "192.168.1.5", "10.0.0.2", "203.0.113.5", "fd00::1",
            "localhost", "vpn.example.com", "", "127.0.0.01", "127.1", "127.0.0.256",
        )
        for (asked in refused) {
            assertEquals(CoreInbound.PROXY_LISTEN_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Proxy(10808, listen = asked))), asked)
            val thrown = assertFailsWith<IllegalArgumentException>(asked) {
                CoreInbound.build(reality, plan(CoreInbound.Proxy(10808, listen = asked)))
            }
            assertTrue(CoreInbound.PROXY_LISTEN_REFUSAL in thrown.message.orEmpty(), asked)
        }
    }

    // LAN sharing: one more inbound in the same core, on the private address
    // and the port the user chose, demanding the LAN login, UDP on, with the
    // parser's flag for a listener off loopback. The core's own inbound and
    // everything else stay as they were.
    @Test fun theLanInboundDemandsItsAccountsOnOnePrivateAddress() {
        for (own in listOf<CoreInbound.Own>(CoreInbound.Tunnel(10810, tunLogin), CoreInbound.Proxy(10808))) {
            val alone = CoreInbound.build(reality, plan(own))
            val shared = CoreInbound.build(reality, plan(own, lan()))
            val (first, second) = inbounds(shared).also { assertEquals(2, it.size) }
            assertEquals(inbounds(alone).single(), first)
            assertEquals(Listener("lan-in", "192.168.50.10", 10818, listOf("ghostlane-guest" to "generated-lan-password")), xray(second))
            val settings = second.obj("settings")
            assertEquals("password", settings.str("auth"))
            assertEquals("true", settings.str("udp"))
            assertEquals("true", settings.str("allowUnauthenticatedLan"))
            assertEquals(first.obj("sniffing"), second.obj("sniffing"))
            assertEquals(parse(alone) - "inbounds", parse(shared) - "inbounds")
        }
    }

    // What a LAN listener may not be given, the Rust core refuses in a
    // sentence; and what it takes, this takes.
    @Test fun whatNoLanListenerMayBeGivenIsRefused() {
        val upstream = SubscriptionFetchProxy("127.0.0.1", 10810, tunLogin.username, tunLogin.password)
        val own = CoreInbound.Tunnel(upstream.port, tunLogin)
        val refused = listOf(
            Triple("0.0.0.0", 10818, lanLogin), Triple("203.0.113.5", 10818, lanLogin), Triple("172.32.0.1", 10818, lanLogin),
            Triple("172.15.0.1", 10818, lanLogin), Triple("192.169.0.1", 10818, lanLogin), Triple("11.0.0.1", 10818, lanLogin),
            Triple("10.0.0", 10818, lanLogin), Triple("192.168.1.256", 10818, lanLogin), Triple("fd00::1", 10818, lanLogin),
            Triple("192.168.1.5", 80, lanLogin), Triple("192.168.1.5", 1023, lanLogin), Triple("192.168.1.5", 65536, lanLogin),
            Triple("192.168.1.5", upstream.port, lanLogin),
            Triple("192.168.1.5", 10818, SocksLogin("", "")), Triple("192.168.1.5", 10818, SocksLogin("guest", " ")),
            Triple("192.168.1.5", 10818, SocksLogin(" ", "password")),
        )
        for ((address, port, login) in refused) {
            val what = "$address:$port"
            val why = CoreInbound.refusal(plan(own, lan(address, port, login)))
            assertTrue(
                why in setOf<String?>(CoreInbound.LAN_ADDRESS_REFUSAL, CoreInbound.LAN_PORT_REFUSAL, CoreInbound.LAN_LOGIN_REFUSAL),
                "$what: $why",
            )
            assertFailsWith<IllegalArgumentException>(what) { CoreInbound.build(reality, plan(own, lan(address, port, login))) }
        }
        val taken = listOf(
            "10.0.0.1" to 1024, "10.255.255.254" to 65535, "172.16.0.1" to 10818, "172.31.255.254" to 10818,
            "192.168.0.1" to 10818, "192.168.255.254" to 10818,
        )
        for ((address, port) in taken) {
            assertNull(CoreInbound.refusal(plan(own, lan(address, port))), "$address:$port")
        }
    }

    // The core takes 255 bytes of UTF-8 for a username and as much for a
    // password, whatever the inbound; a byte more is refused, and so is a
    // blank half.
    @Test fun aLoginIsBothHalvesAndNoLongerThanSocksCarries() {
        val longest = "u".repeat(255)
        val tooLong = "u".repeat(256)
        val twoByteTooLong = "é".repeat(128)
        val twoByteLongest = "é".repeat(127)
        for (fits in listOf(SocksLogin(longest, longest), SocksLogin(twoByteLongest, "p"), SocksLogin("u", twoByteLongest))) {
            assertNull(CoreInbound.refusal(plan(CoreInbound.Tunnel(10810, fits))))
            assertNull(CoreInbound.refusal(plan(CoreInbound.Proxy(10808, fits))))
            assertNull(CoreInbound.refusal(plan(CoreInbound.Proxy(10808), lan(login = fits))))
        }
        for (long in listOf(SocksLogin(tooLong, "p"), SocksLogin("u", tooLong), SocksLogin(twoByteTooLong, "p"), SocksLogin("u", twoByteTooLong))) {
            assertEquals(CoreInbound.LONG_LOGIN_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Tunnel(10810, long))))
            assertEquals(CoreInbound.LONG_LOGIN_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Proxy(10808, long))))
            assertEquals(CoreInbound.LONG_LOGIN_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Proxy(10808), lan(login = long))))
            // The tunnel's writer itself refuses it: the core would refuse the whole config over it.
            assertFailsWith<IllegalArgumentException> { CoreConfig.build(reality, long) }
        }
        for (blank in listOf(SocksLogin("", "p"), SocksLogin("u", ""), SocksLogin(" ", "p"))) {
            assertEquals(CoreInbound.LOGIN_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Tunnel(10810, blank))))
            assertEquals(CoreInbound.HALF_LOGIN_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Proxy(10808, blank))))
            assertEquals(CoreInbound.LAN_LOGIN_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Proxy(10808), lan(login = blank))))
            val thrown = assertFailsWith<IllegalArgumentException> { CoreConfig.build(reality, blank) }
            assertEquals(CoreInbound.LOGIN_REFUSAL, thrown.message)
        }
    }

    @Test fun aPortNoListenerCanHaveIsRefused() {
        for (port in listOf(0, -1, 65536)) {
            assertEquals(CoreInbound.PORT_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Tunnel(port, tunLogin))), "$port")
            assertEquals(CoreInbound.PORT_REFUSAL, CoreInbound.refusal(plan(CoreInbound.Proxy(port))), "$port")
        }
        assertNull(CoreInbound.refusal(plan(CoreInbound.Proxy(1))))
        assertNull(CoreInbound.refusal(plan(CoreInbound.Proxy(65535))))
    }

    // build hands the tunnel's writer a login of its own for the inbound it
    // writes over; none of it may reach a config.
    @Test fun theLoginBuildWritesOverIsInNoConfig() {
        val configs = listOf(
            CoreInbound.build(reality, plan(CoreInbound.Proxy(10808))),
            CoreInbound.build(reality, plan(CoreInbound.Proxy(10808), lan()), routing = bypass, geodata = CoreFixtures.lists),
            CoreInbound.build(reality, plan(CoreInbound.Tunnel(10810, tunLogin), lan())),
        )
        for (config in configs) assertFalse("written-over" in config, config)
    }

    /**
     * What xray-rust refuses of a SOCKS inbound, read at the fork's
     * integration head: `crates/xray-config/src/parser.rs` (`parse_inbound`:
     * off loopback without `allowUnauthenticatedLan`), `parser/socks.rs`
     * (`auth`, its 1 to 64 accounts, none with noauth), `surface.rs`
     * (`SOCKS_ACCOUNT`: 255 bytes), and the runtime's start, which binds the
     * listen address as it is written (`crates/xray-core-rs/src/lib.rs`).
     */
    private fun refusedByTheCore(config: JsonObject): List<String> {
        val found = mutableListOf<String>()
        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }
        val tags = inbounds.map { it.str("tag") }
        if (tags.toSet().size != tags.size) found += "two inbounds have one tag: $tags"
        for (inbound in inbounds) {
            val tag = inbound.str("tag")
            if (inbound.str("protocol") != "socks") found += "$tag: not socks"
            val listen = inbound.str("listen") ?: "127.0.0.1"
            val settings = inbound["settings"]?.jsonObject
            val lanFlag = settings?.get("allowUnauthenticatedLan")?.jsonPrimitive?.booleanOrNull == true
            // A literal only: InetAddress parses one without a lookup, and the start binds nothing else as written.
            val address = listen.takeIf { ':' in it || it.matches(Regex("[0-9.]+")) }
                ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            if (address == null) found += "$tag: $listen is not an address the core binds as written"
            if (address?.isLoopbackAddress != true && !lanFlag) found += "$tag: off loopback without allowUnauthenticatedLan"
            val accounts = settings?.get("accounts")?.jsonArray.orEmpty().map { it.jsonObject }
            when (val auth = settings?.str("auth") ?: "noauth") {
                "", "noauth" -> if (accounts.isNotEmpty()) found += "$tag: accounts with noauth"
                "password" -> if (accounts.size !in 1..64) found += "$tag: password auth with ${accounts.size} accounts"
                else -> found += "$tag: auth $auth"
            }
            for (account in accounts) {
                for (key in listOf("user", "pass")) {
                    if (account.str(key).orEmpty().encodeToByteArray().size > 255) found += "$tag: $key longer than 255 bytes"
                }
            }
        }
        // The routing's queries are taken from the core's own inbound by its tag.
        val dnsRule = config["routing"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        if (dnsRule["inboundTag"]!!.jsonArray.single().jsonPrimitive.content !in tags) found += "the DNS rule names no inbound"
        return found
    }

    @Test fun everyPlanWrittenIsOneTheCoreTakes() {
        val owns = listOf(
            CoreInbound.Tunnel(10810, tunLogin), CoreInbound.Proxy(10808), CoreInbound.Proxy(10808, userLogin),
            CoreInbound.Proxy(10808, listen = "::1"), CoreInbound.Proxy(10808, SocksLogin("u".repeat(255), "é".repeat(127))),
        )
        val lans = listOf(null, lan(), lan("10.1.2.3", 1024), lan("172.20.0.9", 65535))
        for (case in CoreFixtures.cases) {
            for (own in owns) for (lan in lans) {
                val plan = plan(own, lan)
                for (routing in listOf(Routing.Global, bypass)) {
                    val json = CoreInbound.build(case.spec(), plan, routing, CoreFixtures.lists.takeIf { routing == bypass })
                    val config = parse(json)
                    val what = "${case.name} ${own::class.simpleName} lan=${lan?.address}"
                    assertEquals(emptyList(), XrayRustSurface.violations(config), what)
                    assertEquals(emptyList(), refusedByTheCore(config), what)
                }
            }
        }
    }

    // The rules above can fail: the LAN inbound without the parser's flag, on
    // a name, accounts under noauth and one tag for two inbounds are each found.
    @Test fun theCoresRulesAboveSeeWhatItWouldRefuse() {
        val shared = parse(CoreInbound.build(reality, plan(CoreInbound.Proxy(10808), lan())))
        assertEquals(emptyList(), refusedByTheCore(shared))
        val (own, lanIn) = shared["inbounds"]!!.jsonArray.map { it.jsonObject }
        fun withInbounds(vararg list: JsonObject) = shared.replacing("inbounds", JsonArray(list.toList()))
        val broken = mapOf(
            "no flag" to withInbounds(own, lanIn.replacing("settings", lanIn.obj("settings").dropping("allowUnauthenticatedLan"))),
            "a name" to withInbounds(own, lanIn.replacing("listen", JsonPrimitive("my-laptop.local"))),
            "accounts under noauth" to withInbounds(
                own.replacing("settings", own.obj("settings").replacing("accounts", lanIn.obj("settings")["accounts"]!!)),
                lanIn,
            ),
            "one tag twice" to withInbounds(own, lanIn.replacing("tag", JsonPrimitive("in"))),
        )
        for ((what, config) in broken) assertNotEquals(emptyList(), refusedByTheCore(config), what)
    }

    private fun JsonObject.replacing(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))
    private fun JsonObject.dropping(key: String): JsonObject = JsonObject(this - key)
}
