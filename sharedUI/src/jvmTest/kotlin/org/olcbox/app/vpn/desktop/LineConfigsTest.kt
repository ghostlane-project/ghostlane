package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.toRules
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A session's tun, or its system proxy setting, points at one local port and
// offers at most one login, for as long as the session lasts. Another location
// chosen inside the session is started behind it, so its line has to be exactly
// that: these pin what each kind of line is handed to make it so, and that a
// first line is handed what a connect has always written.
class LineConfigsTest {
    // A session that started in a room: the engine's port and the login from the settings.
    private val withLogin = SessionEndpoint(port = 10808, login = SocksLogin("ghost", "pw"))

    // A session that started on a core: its port, and no login.
    private val withoutLogin = SessionEndpoint(port = 10810, login = null)

    private fun vless(host: String) = LinkParser.parse(
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@$host:443" +
            "?type=tcp&security=reality&sni=www.zoom.us&fp=chrome" +
            "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ff#x"
    ) ?: error("the test's own link does not parse")

    private fun xhttp(host: String) = LinkParser.parse(
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@$host:40023" +
            "?type=xhttp&security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ff" +
            "&fp=chrome&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#x"
    ) as? OutboundSpec.Vless ?: error("the test's own link does not parse")

    private val reality = vless("1.2.3.4")

    // Rules of the user's own and no list: what the proxy's core is given, with no rule-set file to read.
    private val rules = RoutingSettings(directRules = listOf("bank.example"))
        .toRules(ruleSetDir = "unused", directDns = DirectDns.System)

    private fun parse(json: String?): JsonObject = Json.parseToJsonElement(json ?: error("no config")).jsonObject
    private fun JsonObject.inbound(): JsonObject = this["inbounds"]!!.jsonArray[0].jsonObject
    private fun JsonObject.outbound(): JsonObject = this["outbounds"]!!.jsonArray[0].jsonObject
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.users(): List<Pair<String, String>> =
        this["users"]!!.jsonArray.map { it.jsonObject.str("username") to it.jsonObject.str("password") }

    @Test
    fun theShapeFollowsTheTransportAndWhetherTheCoreRoutes() {
        assertEquals(CoreShape.SingBox, LineConfigs.shapeOf(reality, Routing.Global))
        assertEquals(CoreShape.SingBox, LineConfigs.shapeOf(reality, rules))
        assertEquals(CoreShape.Xray, LineConfigs.shapeOf(xhttp("1.2.3.4"), Routing.Global))
        assertEquals(CoreShape.XrayBehindSingBox, LineConfigs.shapeOf(xhttp("1.2.3.4"), rules))
    }

    // With no change of location nothing may differ from what a connect wrote
    // before a line could be changed: the right-hand sides are those calls.
    @Test
    fun aFirstLineIsWrittenExactlyAsAConnectAlwaysWroteIt() {
        val plain = LineConfigs.core(
            reality, port = 10810, xrayPort = 10810, routing = Routing.Global, verboseLogs = false
        )
        assertNull(plain.xray)
        assertEquals(
            SingBoxConfig.build(reality, socksPort = 10810, routing = Routing.Global, verboseLogs = false),
            plain.singBox
        )

        // The Linux tunnel: bound to the physical interface, with a resolver for a name.
        val resolver = DirectDns.Servers(listOf("192.168.1.1"))
        val named = vless("vpn.example.com")
        assertEquals(
            SingBoxConfig.build(
                named, socksPort = 10810, routing = Routing.Global, verboseLogs = true,
                serverResolver = resolver, autoDetectInterface = true
            ),
            LineConfigs.core(
                named, port = 10810, xrayPort = 10810, routing = Routing.Global, verboseLogs = true,
                serverResolver = resolver, autoDetectInterface = true
            ).singBox
        )

        val alone = LineConfigs.core(
            xhttp("1.2.3.4"), port = 10810, xrayPort = 10810, routing = Routing.Global, verboseLogs = false,
            bindInterface = "wlan0"
        )
        assertNull(alone.singBox)
        assertEquals(
            XrayConfig.buildXhttp(xhttp("1.2.3.4"), socksPort = 10810, verboseLogs = false, bindInterface = "wlan0"),
            alone.xray
        )

        val fronted = LineConfigs.core(
            xhttp("1.2.3.4"), port = 10810, xrayPort = 10811, routing = rules, verboseLogs = false
        )
        assertEquals(
            XrayConfig.buildXhttp(xhttp("1.2.3.4"), socksPort = 10811, verboseLogs = false, bindInterface = null),
            fronted.xray
        )
        assertEquals(
            SingBoxConfig.buildSocksChain(10811, socksPort = 10810, routing = rules, verboseLogs = false),
            fronted.singBox
        )

        assertEquals(
            SingBoxConfig.buildSocksChain(
                upstreamPort = 10808, socksPort = 10810, username = "ghost", password = "pw",
                routing = rules, verboseLogs = false
            ),
            LineConfigs.front(
                enginePort = 10808, engineUsername = "ghost", enginePassword = "pw",
                port = 10810, routing = rules, verboseLogs = false
            )
        )
    }

    @Test
    fun aSingBoxCoreThatComesLaterListensOnTheEndpointAndDemandsItsLogin() {
        val inbound = parse(
            LineConfigs.core(
                reality, port = withLogin.port, xrayPort = withLogin.port, routing = Routing.Global,
                verboseLogs = false, login = withLogin.login
            ).singBox
        ).inbound()
        assertEquals(10808, inbound.str("listen_port").toInt())
        assertEquals(listOf("ghost" to "pw"), inbound.users())

        // The endpoint of a session that started on a core asks for nothing,
        // and a core that asked for something would refuse the tun.
        val bare = parse(
            LineConfigs.core(
                reality, port = withoutLogin.port, xrayPort = withoutLogin.port, routing = Routing.Global,
                verboseLogs = false, login = withoutLogin.login
            ).singBox
        ).inbound()
        assertEquals(10810, bare.str("listen_port").toInt())
        assertFalse("users" in bare, bare.toString())
    }

    @Test
    fun anXrayCoreThatComesLaterListensOnTheEndpointAndDemandsItsLogin() {
        val configs = LineConfigs.core(
            xhttp("1.2.3.4"), port = withLogin.port, xrayPort = withLogin.port, routing = Routing.Global,
            verboseLogs = false, login = withLogin.login
        )
        assertNull(configs.singBox)
        val inbound = parse(configs.xray).inbound()
        assertEquals(10808, inbound.str("port").toInt())
        val settings = inbound["settings"]!!.jsonObject
        assertEquals("password", settings.str("auth"))
        assertEquals(
            listOf("ghost" to "pw"),
            settings["accounts"]!!.jsonArray.map { it.jsonObject.str("user") to it.jsonObject.str("pass") }
        )
    }

    // Xray does not route, so under rules a sing-box stands in front of it. The
    // front is what the session points at; Xray keeps a port of its own behind
    // it, and asks for nothing, since only the front reaches it.
    @Test
    fun underRulesTheFrontOfAnXhttpLineIsTheEndpoint() {
        val login = SocksLogin("ghost", "pw")
        val configs = LineConfigs.core(
            xhttp("1.2.3.4"), port = 10810, xrayPort = 10811, routing = rules, verboseLogs = false, login = login
        )
        val front = parse(configs.singBox)
        assertEquals(10810, front.inbound().str("listen_port").toInt())
        assertEquals(listOf("ghost" to "pw"), front.inbound().users())
        assertEquals(10811, front.outbound().str("server_port").toInt())
        assertFalse("username" in front.outbound(), front.outbound().toString())

        val behind = parse(configs.xray).inbound()
        assertEquals(10811, behind.str("port").toInt())
        assertFalse("auth" in behind["settings"]!!.jsonObject, behind.toString())
    }

    // The engine takes its port and its login from the settings object it is
    // started with. These are the settings it is handed, and the yaml it reads.
    @Test
    fun anEngineThatComesLaterIsStartedAsTheEndpoint() {
        val settings = DesktopSocksProxySettings(
            port = 10900, username = "set", password = "setpw", shareOnLan = true, lanPort = 10999
        )

        // Into a room, in a session that started on a core: the core's port and
        // no login, although the settings have both.
        val entered = LineConfigs.engineSettings(settings, withoutLogin)
        assertEquals(10810, entered.port)
        assertEquals("", entered.username)
        assertEquals("", entered.password)
        // Nothing else of the settings is the endpoint's to change.
        assertEquals(settings.copy(port = 10810, username = "", password = ""), entered)
        val bare = yaml(entered)
        assertContains(bare, "socks:\n  host: '127.0.0.1'\n  port: 10810\n")
        assertFalse("user:" in bare, bare)
        assertFalse("pass:" in bare, bare)

        // Between rooms, in a session that started in one: the port and login it started with.
        val moved = LineConfigs.engineSettings(settings, withLogin)
        assertContains(yaml(moved), "socks:\n  host: '127.0.0.1'\n  port: 10808\n  user: 'ghost'\n  pass: 'pw'\n")
    }

    private fun yaml(settings: DesktopSocksProxySettings): String = OlcRtcCommand(
        binary = Path.of("/tmp/olcrtc"),
        location = LocationConfig(
            id = "https://meet.example/room",
            key = "ab".repeat(32),
            bypassProvider = LocationConfig.PROVIDER_TELEMOST,
            transport = LocationConfig.TRANSPORT_DATACHANNEL
        ),
        socksHost = settings.host,
        socksPort = settings.port,
        socksUser = settings.username,
        socksPass = settings.password,
        dnsServer = "1.1.1.1:53"
    ).yaml()

    // Proxy mode under rules: the front is the endpoint, and the engine keeps
    // its own port and its own login behind it.
    @Test
    fun theFrontBeforeARoomIsTheEndpointAndTheEngineKeepsItsOwnPort() {
        val front = parse(
            LineConfigs.front(
                enginePort = 10808, engineUsername = "ghost", enginePassword = "pw",
                port = 10810, routing = rules, verboseLogs = false, login = SocksLogin("held", "heldpw")
            )
        )
        assertEquals(10810, front.inbound().str("listen_port").toInt())
        assertEquals(listOf("held" to "heldpw"), front.inbound().users())
        assertEquals(10808, front.outbound().str("server_port").toInt())
        assertEquals("ghost", front.outbound().str("username"))
        assertEquals("pw", front.outbound().str("password"))
    }

    // With the tun up, the system's resolver is reached through the tun, whose
    // way out is the very core that is asking. So a core started beside a tun
    // asks the resolvers it is given, and its own query leaves by the tun's
    // rule on its binary. Xray has no such part.
    @Test
    fun aCoreThatComesLaterAsksTheResolversItIsGivenForItsServersName() {
        val resolver = DirectDns.Servers(listOf("192.168.1.1", "10.0.0.53"))
        val root = parse(
            LineConfigs.core(
                vless("vpn.example.com"), port = 10810, xrayPort = 10810, routing = Routing.Global,
                verboseLogs = false, serverResolver = resolver
            ).singBox
        )
        val server = root["dns"]!!.jsonObject["servers"]!!.jsonArray.single().jsonObject
        assertEquals("udp", server.str("type"))
        assertEquals("192.168.1.1", server.str("server"))
        assertEquals("dns-direct", root["route"]!!.jsonObject.str("default_domain_resolver"))

        val xray = parse(
            LineConfigs.core(
                xhttp("vpn.example.com"), port = 10810, xrayPort = 10810, routing = Routing.Global,
                verboseLogs = false, serverResolver = resolver
            ).xray
        )
        assertFalse("dns" in xray, xray.toString())
    }

    // A process rule that names a path the system does not know the process
    // by lets nothing out and says nothing, so each binary is named both as it
    // is started and with its links resolved.
    @Test
    fun theProcessRuleNamesEachBinaryAsStartedAndAsResolved() {
        val dir = Files.createTempDirectory("line-binaries").toRealPath()
        val real = Files.createDirectory(dir.resolve("real"))
        val binary = Files.createFile(real.resolve("sing-box"))
        // Not every system lets a test make a link. Without one the two
        // spellings are the same, and the list has to say it once.
        val link = runCatching { Files.createSymbolicLink(dir.resolve("linked"), real) }.getOrNull()
        try {
            val started = (link ?: real).resolve("sing-box")
            val paths = LineConfigs.processPaths(
                running = listOf("/proc/reported/olcrtc"),
                binaries = listOf(started, started)
            )
            assertEquals("/proc/reported/olcrtc", paths.first())
            assertTrue(started.toString() in paths, paths.toString())
            assertTrue(binary.toString() in paths, paths.toString())
            assertEquals(paths.distinct(), paths)
            // One that is not on this machine is still named as it would be started.
            assertEquals(
                listOf(dir.resolve("absent").toString()),
                LineConfigs.processPaths(running = emptyList(), binaries = listOf(dir.resolve("absent")))
            )
        } finally {
            link?.let { Files.deleteIfExists(it) }
            Files.deleteIfExists(binary)
            Files.deleteIfExists(real)
            Files.deleteIfExists(dir)
        }
    }

    // What a later line is started with, written out for the pinned binaries:
    // `sing-box check` and `xray -test` in PR Checks read these as they read
    // every other shape the app builds.
    @Test
    fun dumpTheConfigsOfALineThatComesLater() {
        val login = withLogin.login
        val resolver = DirectDns.Servers(listOf("192.168.1.1"))
        val singBox = File("build/singbox-configs").apply { mkdirs() }
        val xray = File("build/xray-configs").apply { mkdirs() }

        val core = LineConfigs.core(
            vless("vpn.example.com"), port = 10808, xrayPort = 10808, routing = Routing.Global,
            verboseLogs = false, login = login, serverResolver = resolver
        )
        File(singBox, "desktop-later-line.json").writeText(core.singBox ?: error("no sing-box config"))

        val alone = LineConfigs.core(
            xhttp("127.0.0.1"), port = 10808, xrayPort = 10808, routing = Routing.Global,
            verboseLogs = false, login = login
        )
        File(xray, "desktop-later-xhttp.json").writeText(alone.xray ?: error("no xray config"))

        val fronted = LineConfigs.core(
            xhttp("127.0.0.1"), port = 10810, xrayPort = 10811, routing = rules, verboseLogs = false, login = login
        )
        File(singBox, "desktop-later-xhttp-front.json").writeText(fronted.singBox ?: error("no sing-box config"))
        File(xray, "desktop-later-xhttp-behind-front.json").writeText(fronted.xray ?: error("no xray config"))

        File(singBox, "desktop-later-room-front.json").writeText(
            LineConfigs.front(
                enginePort = 10808, engineUsername = "ghost", enginePassword = "pw",
                port = 10810, routing = rules, verboseLogs = false, login = login
            )
        )
        assertTrue(File(singBox, "desktop-later-line.json").exists())
    }
}
