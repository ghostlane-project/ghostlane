package org.olcbox.app.net.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.IosCoreFixtures
import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.toRules
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.engine.DesktopLineEngine
import org.olcbox.app.vpn.desktop.engine.DesktopRulesHome
import org.olcbox.app.vpn.desktop.engine.desktopRulesHome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A room behind its front on the desktops: the core half of a room's
 * session on ghostlane-engine ([CoreRoomFront]), and where a session's rules
 * live on that engine ([desktopRulesHome] with its engine).
 *
 * The front is held here to what the engine's `front.rs` takes (one `socks`
 * outbound tagged `room`, first, plain SOCKS with the room's login), to the
 * core's field lists, and to the line's config it is: the same writers, the
 * same rules, with the room where the line was.
 *
 * Run by scripts/desktop-checks/room-front.sh (rust-desktop.yml's Linux
 * leg), [writesTheFrontsTheCheckAsksFor] also writes the fronts that script
 * hands the real engine, with a room on the `none` carrier behind each, from
 * the request the file [CHECK_ENV] names. Anywhere else that one test is
 * skipped, and the script fails a run in which it was skipped or did not run.
 */
class RoomFrontDumpTest {
    private val roomLogin = SocksLogin("room-user", "room-password")
    private val userLogin = SocksLogin("proxy-user", "proxy-password")
    private val tunLogin = SocksLogin("tun-user", "tun-password")
    private val lanLogin = SocksLogin("ghostlane-guest", "generated-lan-password")
    private val resolver = DirectDns.Servers(listOf("192.0.2.53"))
    private val reality = IosCoreFixtures.spec(IosCoreLine.RealityVision)

    private val proxy = CoreInbound.Plan(CoreInbound.Proxy(10808))
    private val shared = CoreInbound.Plan(CoreInbound.Proxy(10808), CoreInbound.Lan("192.168.50.10", 10818, lanLogin))

    private fun rules(mode: RoutingMode, direct: List<String> = emptyList(), tunnel: List<String> = emptyList()): Routing =
        RoutingSettings(mode = mode, directRules = direct, tunnelRules = tunnel).toRules("unused-on-the-rust-core", resolver)

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun routingRules(front: JsonObject): List<JsonObject> = front.obj("routing").objects("rules")
    private fun dnsRules(front: JsonObject): List<JsonObject> =
        front.objects("outbounds").single { it.str("tag") == "dns-out" }.obj("settings").objects("rules")

    /** The way to the room as the engine's `front.rs` takes it: plain SOCKS, one server written flat, the room's login. */
    private fun roomOutbound(login: SocksLogin): JsonObject = buildJsonObject {
        put("tag", "room")
        put("protocol", "socks")
        putJsonObject("settings") {
            put("address", "127.0.0.1")
            put("port", 1080)
            put("user", login.username)
            put("pass", login.password)
        }
    }

    /** Every `outboundTag` in [element], wherever it is: the routing's rules and the `dns` outbound's. */
    private fun outboundTags(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            if (key == "outboundTag") listOf(value.jsonPrimitive.content) else outboundTags(value)
        }
        is JsonArray -> element.flatMap { outboundTags(it) }
        else -> emptyList()
    }

    /** [element] with every `outboundTag` that named the line naming the room: this test's own reading of the front. */
    private fun toTheRoom(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (key == "outboundTag" && value == JsonPrimitive(CoreConfig.LINE)) JsonPrimitive("room") else toTheRoom(value)
            }
        )
        is JsonArray -> JsonArray(element.map { toTheRoom(it) })
        else -> element
    }

    /**
     * The line's config for [routing] (the iPhone's Reality line, whose
     * outbound is the only part of it a kind writes), listening as [plan]
     * says, with the way to the room in the line's place and every rule that
     * sent a connection by the line sending it to the room.
     */
    private fun lineWithTheRoom(plan: CoreInbound.Plan, routing: Routing, lists: XrayGeodata.Lists?, verbose: Boolean): String {
        val blockedOnly = (CoreRouting.effective(routing) as? Routing.RuleBased)?.policy == Routing.Policy.BlockedOnly
        val line = parse(
            CoreConfig.build(reality, tunLogin, routing = routing, geodata = lists, verboseLogs = verbose, refilterDat = blockedOnly)
        )
        val outbounds = line.getValue("outbounds").jsonArray
        assertEquals(CoreConfig.LINE, outbounds.first().jsonObject.str("tag"), "the line's outbound is first")
        val swapped = JsonObject(
            line + mapOf(
                "inbounds" to buildJsonArray { addInbounds(plan) },
                "outbounds" to JsonArray(listOf(roomOutbound(roomLogin)) + outbounds.drop(1)),
            )
        )
        return toTheRoom(swapped).toString()
    }

    /** Null when the two are one text, else where they part, shortly. */
    private fun parting(expected: String, actual: String): String? {
        if (expected == actual) return null
        val at = expected.indices.firstOrNull { it >= actual.length || expected[it] != actual[it] } ?: expected.length
        fun around(text: String) = text.substring(maxOf(0, at - 60), minOf(text.length, at + 80))
        return "they part at character $at: expected …${around(expected)}…, written …${around(actual)}…"
    }

    // ---- the way to the room ----

    @Test
    fun theWayToTheRoomIsFirstPlainSocksWithTheRoomsLogin() {
        val front = parse(CoreRoomFront.build(proxy, roomLogin, rules(RoutingMode.Global, direct = listOf("direct.example"))))
        val outbounds = front.objects("outbounds")
        assertEquals(roomOutbound(roomLogin), outbounds.first(), "the way to the room, first: what no rule claims rides it")
        assertEquals(1, outbounds.count { it.str("tag") == CoreRoomFront.ROOM }, "one outbound is the way to the room")
        assertEquals(CoreRoomFront.ROOM, "room", "the engine's front::ROOM_OUTBOUND")
        assertTrue(outbounds.none { it.str("tag") == CoreConfig.LINE }, "no line's outbound is left in a front")
        val tags = outbounds.mapNotNull { it.str("tag") }.toSet()
        val named = outboundTags(front)
        assertTrue(CoreRoomFront.ROOM in named, "a rule sends something to the room: $named")
        assertTrue(CoreConfig.LINE !in named, "no rule sends anything to a line: $named")
        assertTrue(named.all { it in tags }, "every rule names an outbound the front has: $named of $tags")
    }

    @Test
    fun theFrontIsALinesConfigWhoseLineIsTheRoom() = runTest {
        val ru = XrayGeodata.lists("ru")
        val ir = XrayGeodata.lists("ir")
        val routings: List<Triple<String, Routing, XrayGeodata.Lists?>> = listOf(
            Triple("global", Routing.Global, null),
            Triple("global with tunnel rules only", rules(RoutingMode.Global, tunnel = listOf("tunnel.example")), null),
            Triple("global with direct rules", rules(RoutingMode.Global, direct = listOf("direct.example", "192.0.2.0/24")), null),
            Triple(
                "bypass russia with rules",
                rules(RoutingMode.BypassRussia, direct = listOf("direct.example"), tunnel = listOf("via-room.ru", "198.51.100.0/24")),
                ru,
            ),
            Triple("bypass iran", rules(RoutingMode.BypassIran), ir),
            Triple("only blocked sites", rules(RoutingMode.BlockedOnly, tunnel = listOf("tunnel.example")), null),
        )
        val plans = listOf(
            "proxy" to proxy,
            "proxy with a login" to CoreInbound.Plan(CoreInbound.Proxy(10808, userLogin)),
            "proxy on ::1" to CoreInbound.Plan(CoreInbound.Proxy(10808, listen = "::1")),
            "tunnel" to CoreInbound.Plan(CoreInbound.Tunnel(10808, tunLogin)),
            "proxy and LAN" to shared,
        )
        val partings = mutableListOf<String>()
        for ((name, routing, lists) in routings) for ((planName, plan) in plans) for (verbose in listOf(false, true)) {
            val blockedOnly = (CoreRouting.effective(routing) as? Routing.RuleBased)?.policy == Routing.Policy.BlockedOnly
            val front = CoreRoomFront.build(plan, roomLogin, routing, lists, verboseLogs = verbose, refilterDat = blockedOnly)
            parting(lineWithTheRoom(plan, routing, lists, verbose), front)?.let { partings += "$name, $planName, verbose=$verbose: $it" }
        }
        assertEquals(emptyList<String>(), partings)
    }

    @Test
    fun theFrontHoldsOnlyWhatTheCoresParserTakes() = runTest {
        val fronts = listOf(
            CoreRoomFront.build(proxy, roomLogin),
            CoreRoomFront.build(shared, roomLogin, rules(RoutingMode.BypassRussia, tunnel = listOf("via-room.ru")), XrayGeodata.lists("ru")),
            CoreRoomFront.build(shared, roomLogin, rules(RoutingMode.BlockedOnly), refilterDat = true),
        )
        for (json in fronts) {
            // Everything but the way to the room is what a line's config holds, and is held to the core's lists by the
            // shared walk; the walk knows no `socks` outbound, which no line writes.
            assertEquals(listOf(SOCKS_NOT_WALKED), XrayRustSurface.violations(json))
            val settings = parse(json).objects("outbounds").first().obj("settings")
            assertEquals(emptySet<String>(), settings.keys - SOCKS_OUTBOUND, "the room's way holds only what the parser's socks outbound takes")
        }
    }

    // ---- the rules a room keeps ----

    @Test
    fun aTunnelNameUnderAWholeTldEntryGoesToTheRoom() = runTest {
        val routing = rules(RoutingMode.BypassRussia, tunnel = listOf("via-room.ru"))
        val front = parse(CoreRoomFront.build(proxy, roomLogin, routing, XrayGeodata.lists("ru")))

        val route = routingRules(front)
        val tunnel = route.indexOfFirst { "domain:via-room.ru" in it.strings("domain") }
        val tld = route.indexOfFirst { "domain:ru" in it.strings("domain") }
        assertTrue(tunnel >= 0, "a routing rule names the user's tunnel name")
        assertTrue(tld >= 0, "Bypass Russia's lists hold the whole TLD, domain:ru")
        assertTrue(tunnel < tld, "the user's tunnel rule is read before the region's whole-TLD entry")
        assertEquals(CoreRoomFront.ROOM, route[tunnel].str("outboundTag"))
        assertEquals("direct", route[tld].str("outboundTag"))
        assertEquals("AsIs", front.obj("routing").str("domainStrategy"), "no name is resolved to be held to the address rules")

        // Its name is the room's to resolve: the `dns` outbound forwards it through the room before the
        // region's names are asked of the network.
        val names = dnsRules(front)
        val forward = names.indexOfFirst { it.str("action") == "forward" && "domain:via-room.ru" in it.strings("domain") }
        val direct = names.indexOfFirst { it.str("action") == "direct" && "domain:ru" in it.strings("domain") }
        assertTrue(forward >= 0 && direct >= 0, "the dns outbound names both: $names")
        assertTrue(forward < direct, "the tunnel name is answered before the TLD is asked of the network")
        assertEquals(CoreRoomFront.ROOM, names[forward].str("outboundTag"))
    }

    @Test
    fun onlyBlockedSitesSendsTheListToTheRoomAndTheRestStraightOut() {
        val routing = rules(RoutingMode.BlockedOnly)
        assertEquals(CoreConfig.BLOCKED_ONLY_REFUSAL, CoreRoomFront.refusal(proxy, roomLogin, routing, refilterDat = false))
        assertFailsWith<IllegalArgumentException> { CoreRoomFront.build(proxy, roomLogin, routing) }

        val front = parse(CoreRoomFront.build(proxy, roomLogin, routing, refilterDat = true))
        assertEquals(CoreBlockedOnly.DOMAIN_STRATEGY, front.obj("routing").str("domainStrategy"))
        val route = routingRules(front)
        assertEquals(CoreRoomFront.ROOM, route.single { it.strings("domain") == listOf(CoreBlockedOnly.NAMES) }.str("outboundTag"))
        assertEquals(CoreRoomFront.ROOM, route.single { it.strings("ip") == listOf(CoreBlockedOnly.ADDRESSES) }.str("outboundTag"))
        assertEquals(CoreBlockedOnly.EVERY_ADDRESS, route.last().strings("ip"), "the last rule takes every address")
        assertEquals("direct", route.last().str("outboundTag"), "what the list does not hold goes straight out")
    }

    @Test
    fun aLanClientComesInBesideTheProxyAndRidesTheSameRules() = runTest {
        val front = parse(
            CoreRoomFront.build(shared, roomLogin, rules(RoutingMode.BypassRussia, tunnel = listOf("via-room.ru")), XrayGeodata.lists("ru"))
        )
        val inbounds = front.objects("inbounds")
        assertEquals(listOf(CoreInbound.TAG, CoreInbound.LAN_TAG), inbounds.map { it.str("tag") })
        val own = inbounds[0]
        assertEquals("127.0.0.1", own.str("listen"))
        assertEquals(10808, own.number("port"))
        assertEquals("noauth", own.obj("settings").str("auth"), "proxy mode with no login of the user's, as the Go cores listen")
        val lan = inbounds[1]
        assertEquals("192.168.50.10", lan.str("listen"))
        assertEquals(10818, lan.number("port"))
        val settings = lan.obj("settings")
        assertEquals("password", settings.str("auth"))
        assertEquals(
            listOf(lanLogin.username to lanLogin.password),
            settings.objects("accounts").map { it.str("user") to it.str("pass") },
        )
        assertEquals(true, settings.getValue("udp").jsonPrimitive.booleanOrNull, "a LAN client's datagrams are carried")
        assertEquals(true, settings.getValue("allowUnauthenticatedLan").jsonPrimitive.booleanOrNull)
        // No rule tells the LAN's connections from the proxy's: the same rules, to the same room.
        val inboundRules = routingRules(front).flatMap { it.strings("inboundTag") }.toSet()
        assertEquals(setOf(CoreInbound.TAG, CoreDns.DNS_DIRECT), inboundRules)
    }

    // ---- what is refused ----

    @Test
    fun whatTheFrontCannotBeIsRefusedWithASentence() = runTest {
        val routing = rules(RoutingMode.BypassRussia)
        val ru = XrayGeodata.lists("ru")
        val logins = listOf(
            SocksLogin("", "room-password") to CoreRoomFront.ROOM_LOGIN_REFUSAL,
            SocksLogin("room-user", " ") to CoreRoomFront.ROOM_LOGIN_REFUSAL,
            SocksLogin("u".repeat(256), "room-password") to CoreInbound.LONG_LOGIN_REFUSAL,
            SocksLogin("room-user", "п".repeat(128)) to CoreInbound.LONG_LOGIN_REFUSAL,
        )
        for ((login, why) in logins) {
            assertEquals(why, CoreRoomFront.refusal(proxy, login, routing), "$login")
            val thrown = assertFailsWith<IllegalArgumentException> { CoreRoomFront.build(proxy, login, routing, ru) }
            assertTrue(why in thrown.message.orEmpty(), "${thrown.message}")
        }
        // A login of 255 bytes of each is one.
        assertEquals(null, CoreRoomFront.refusal(proxy, SocksLogin("u".repeat(255), "p".repeat(255)), routing))

        assertEquals(
            CoreInbound.PROXY_LISTEN_REFUSAL,
            CoreRoomFront.refusal(CoreInbound.Plan(CoreInbound.Proxy(10808, listen = "0.0.0.0")), roomLogin),
        )
        assertEquals(
            CoreInbound.LAN_ADDRESS_REFUSAL,
            CoreRoomFront.refusal(CoreInbound.Plan(CoreInbound.Proxy(10808), CoreInbound.Lan("203.0.113.5", 10818, lanLogin)), roomLogin),
        )
        assertEquals(CoreInbound.LOGIN_REFUSAL, CoreRoomFront.refusal(CoreInbound.Plan(CoreInbound.Tunnel(10808, SocksLogin("", ""))), roomLogin))

        // The system's resolver cannot be named to the core; under no rules none is named at all.
        val system = RoutingSettings(mode = RoutingMode.BypassRussia).toRules("unused-on-the-rust-core", DirectDns.System)
        val why = assertNotNull(CoreRoomFront.refusal(proxy, roomLogin, system))
        assertTrue("system resolver" in why, why)
        assertFailsWith<IllegalArgumentException> { CoreRoomFront.build(proxy, roomLogin, system, ru) }
        val noRules = RoutingSettings().toRules("unused-on-the-rust-core", DirectDns.System)
        assertEquals(null, CoreRoomFront.refusal(proxy, roomLogin, noRules))
        CoreRoomFront.build(proxy, roomLogin, noRules)

        // A bypass carries its region's lists, which the caller reads.
        assertEquals(null, CoreRoomFront.refusal(proxy, roomLogin, routing))
        assertFailsWith<IllegalArgumentException> { CoreRoomFront.build(proxy, roomLogin, routing) }
    }

    // ---- where a session's rules live on ghostlane-engine ----

    @Test
    fun onTheEngineARoomsRulesAreItsFrontsInTheProxyAndEveryTunnel() {
        for (mode in listOf(DesktopMode.SystemProxy, DesktopMode.LinuxTun, DesktopMode.MacTun, DesktopMode.WindowsTun)) {
            assertEquals(
                DesktopRulesHome.Core,
                desktopRulesHome(mode, isOlcrtc = true, needsRules = true, engine = DesktopLineEngine.Rust),
                "a room in $mode on ghostlane-engine",
            )
        }
        for (mode in DesktopMode.entries) {
            assertEquals(
                DesktopRulesHome.Nowhere,
                desktopRulesHome(mode, isOlcrtc = true, needsRules = false, engine = DesktopLineEngine.Rust),
                "no rules asked for in $mode",
            )
        }
    }

    @Test
    fun theGoEnginesAndACoreLineAreAnsweredAsBefore() {
        for (mode in DesktopMode.entries) for (needsRules in listOf(true, false)) {
            for (olcrtc in listOf(true, false)) {
                assertEquals(
                    desktopRulesHome(mode, olcrtc, needsRules),
                    desktopRulesHome(mode, olcrtc, needsRules, DesktopLineEngine.Go),
                    "the Go engines, $mode olcrtc=$olcrtc rules=$needsRules",
                )
            }
            // The tunnels' core lines are the exceptions (MacRoutingTest, WindowsDefaultTest): in the macOS,
            // the Linux and the Windows tunnel their rules are the core's.
            if ((mode == DesktopMode.MacTun || mode == DesktopMode.LinuxTun || mode == DesktopMode.WindowsTun) && needsRules) continue
            assertEquals(
                desktopRulesHome(mode, isOlcrtc = false, needsRules = needsRules),
                desktopRulesHome(mode, isOlcrtc = false, needsRules = needsRules, engine = DesktopLineEngine.Rust),
                "a core line on ghostlane-engine, $mode rules=$needsRules",
            )
        }
        // What the Go engines did for a room in the tunnels, the root olcRTC engine's own rules and the macOS
        // daemon's sing-box, is still theirs.
        assertEquals(DesktopRulesHome.Engine, desktopRulesHome(DesktopMode.LinuxTun, true, true, DesktopLineEngine.Go))
        assertEquals(DesktopRulesHome.Daemon, desktopRulesHome(DesktopMode.MacTun, true, true, DesktopLineEngine.Go))
    }

    // ---- the check's fronts ----

    /**
     * The fronts room-front.sh hands the real engine: for each case of its
     * request, the routing the app makes of the mode and the user's own
     * rules ([toRules], with the check's resolver), proxy mode's inbound on
     * the case's port and LAN sharing's when the case names one, behind the
     * room's login; written to `<out>/<case>.json`.
     */
    @Test
    fun writesTheFrontsTheCheckAsksFor() {
        val path = System.getenv(CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/room-front.sh", !path.isNullOrBlank())
        runTest { writeTheFronts(File(requireNotNull(path))) }
    }

    private suspend fun writeTheFronts(file: File) {
        val request = parse(file.readText())
        val out = File(requireNotNull(request.str("out")) { "the request names no out directory" })
        out.mkdirs()
        val checkResolver = DirectDns.Servers(listOf(requireNotNull(request.str("resolver")) { "the request names no resolver" }))
        val verbose = request["verbose"]?.jsonPrimitive?.booleanOrNull ?: false
        val room = request.obj("room_login").let { SocksLogin(it.str("user").orEmpty(), it.str("pass").orEmpty()) }
        val cases = request.objects("cases")
        assertTrue(cases.isNotEmpty(), "the check asks for no front")
        for (case in cases) {
            val name = requireNotNull(case.str("name"))
            assertTrue(Regex("[a-z0-9-]+").matches(name), "a case's name is a file's: $name")
            val mode = RoutingMode.valueOf(requireNotNull(case.str("mode")))
            val routing = RoutingSettings(mode = mode, directRules = case.strings("direct"), tunnelRules = case.strings("tunnel"))
                .toRules("unused-on-the-rust-core", checkResolver)
            val lan = (case["lan"] as? JsonObject)?.let {
                CoreInbound.Lan(requireNotNull(it.str("address")), it.number("port"), SocksLogin(it.str("user").orEmpty(), it.str("pass").orEmpty()))
            }
            val plan = CoreInbound.Plan(CoreInbound.Proxy(case.number("port")), lan)
            val lists = mode.region?.let { XrayGeodata.lists(it) }
            val json = CoreRoomFront.build(plan, room, routing, lists, verboseLogs = verbose, refilterDat = mode == RoutingMode.BlockedOnly)
            assertEquals(listOf(SOCKS_NOT_WALKED), XrayRustSurface.violations(json), name)
            File(out, "$name.json").writeText(json)
            println("room-front: wrote $name.json, ${mode.name}, ${json.length} bytes")
        }
    }

    private companion object {
        /** The file room-front.sh writes its request to. */
        const val CHECK_ENV = "ROOM_FRONT_CHECK"

        /** What the shared walk says of the way to the room, the one outbound no line writes. */
        const val SOCKS_NOT_WALKED = "\$.outbounds[0].protocol socks is not checked here"

        /**
         * `$.outbounds[].settings (socks)` in `crates/xray-config/src/surface.rs`
         * of ghostlane-project/xray-rust at add3245c: one server written flat
         * or as the one `servers` entry; `user` and `pass` both or neither.
         */
        val SOCKS_OUTBOUND = setOf("address", "port", "user", "pass", "level", "email", "servers")
    }
}
