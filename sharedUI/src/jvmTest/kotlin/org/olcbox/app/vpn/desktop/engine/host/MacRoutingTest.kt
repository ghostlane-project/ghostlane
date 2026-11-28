package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.net.CustomRules
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.core.CoreBlockedOnly
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.engine.DesktopLineEngine
import org.olcbox.app.vpn.desktop.engine.DesktopRulesHome
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.desktopRulesHome
import org.olcbox.app.vpn.rulesHomeWithoutDaemon
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The routing modes in the macOS tunnel on ghostlane-engine. The helper has no
 * sing-box: hev hands every connection to the engine's core, so the core
 * holds the rules and sends what they do not claim straight out of the
 * physical interface (the session's `bind`). "Only blocked sites" is the
 * core's, read from refilter.dat in the session's geodata directory.
 */
class MacRoutingTest {
    private val reality =
        "vless://11111111-1111-1111-1111-111111111111@192.0.2.7:443?security=reality&encryption=none" +
            "&pbk=${CoreFixtures.PUBLIC_KEY}&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#line"

    private val blockedOnly =
        Routing.Rules("/unused", DirectDns.Servers(listOf("192.0.2.53")), Routing.Policy.BlockedOnly, CustomRules.of(emptyList(), emptyList()))

    private fun request(routing: Routing) = LineRequest(
        spec = requireNotNull(LinkParser.parse(reality)),
        kind = "Vless",
        port = 10808,
        routing = routing,
        verboseLogs = false,
        login = SocksLogin("session-user", "session-pass"),
        serverResolver = null,
        autoDetectInterface = true,
        bindInterface = "en0",
        anotherPort = { error("one process, one port") },
    )

    private val noName: suspend (String, DirectDns?) -> String = { host, _ -> error("asked for $host") }

    @Test
    fun aCoreLineInTheMacTunnelOnTheEngineKeepsItsRulesInTheCore() {
        assertEquals(
            DesktopRulesHome.Core,
            rulesHomeWithoutDaemon(DesktopMode.MacTun, isOlcrtc = false, needsRules = true, engine = DesktopLineEngine.Rust),
        )
        assertEquals(
            DesktopRulesHome.Nowhere,
            rulesHomeWithoutDaemon(DesktopMode.MacTun, isOlcrtc = false, needsRules = false, engine = DesktopLineEngine.Rust),
        )
        // The Go answer is what it was: a daemon with no sing-box has nowhere to put them.
        assertEquals(
            DesktopRulesHome.Nowhere,
            rulesHomeWithoutDaemon(DesktopMode.MacTun, isOlcrtc = false, needsRules = true),
        )
        // A room on the engine carries its rules in its own front, the core half of the room's process (HR).
        assertEquals(
            DesktopRulesHome.Core,
            rulesHomeWithoutDaemon(DesktopMode.MacTun, isOlcrtc = true, needsRules = true, engine = DesktopLineEngine.Rust),
        )
        // LR: a core line in the Linux tunnel keeps its rules in the core too; the Go answer stays.
        assertEquals(
            DesktopRulesHome.Core,
            desktopRulesHome(DesktopMode.LinuxTun, isOlcrtc = false, needsRules = true, engine = DesktopLineEngine.Rust),
        )
        assertEquals(
            DesktopRulesHome.Nowhere,
            desktopRulesHome(DesktopMode.LinuxTun, isOlcrtc = false, needsRules = true, engine = DesktopLineEngine.Go),
        )
        assertEquals(
            DesktopRulesHome.Nowhere,
            desktopRulesHome(DesktopMode.LinuxTun, isOlcrtc = false, needsRules = false, engine = DesktopLineEngine.Rust),
        )
        // WD: the Windows tunnel's are the core's too (bound by the adapter's alias).
        assertEquals(
            DesktopRulesHome.Core,
            desktopRulesHome(DesktopMode.WindowsTun, isOlcrtc = false, needsRules = true, engine = DesktopLineEngine.Rust),
        )
    }

    @Test
    fun theMacSessionsCoreCarriesTheBlockedOnlyRoutingAndTheGeodataDirectory() = runTest {
        var asked = 0
        val line = HostSession.line(request(blockedOnly), noName, geodataDir = { asked++; "/data/geodata" }) { "en0" }
        assertEquals(1, asked)
        assertEquals("en0", line.bind)
        assertEquals("/data/geodata", line.geodataDir)

        val core = Json.parseToJsonElement(line.core).jsonObject
        val rules = core.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val byLine = rules.filter { it["outboundTag"]?.jsonPrimitive?.content == "out" }
        assertTrue(
            byLine.any { CoreBlockedOnly.NAMES in it["domain"]?.jsonArray?.map { d -> d.jsonPrimitive.content }.orEmpty() },
            "the listed names go by the line",
        )
        assertTrue(
            byLine.any { CoreBlockedOnly.ADDRESSES in it["ip"]?.jsonArray?.map { d -> d.jsonPrimitive.content }.orEmpty() },
            "the listed addresses go by the line",
        )
        val last = rules.last()
        assertEquals("direct", last.getValue("outboundTag").jsonPrimitive.content, "what no rule claims goes straight out")

        val session = Json.parseToJsonElement(
            HostSession.text(line.core, bind = line.bind, geodataDir = line.geodataDir)
        ).jsonObject
        assertEquals("/data/geodata", session.getValue("geodata_dir").jsonPrimitive.content)
        assertEquals("en0", session.getValue("bind").jsonObject.getValue("interface").jsonPrimitive.content)
    }

    @Test
    fun aSessionWithoutAGeodataDirectoryStillRefusesBlockedOnlyAndOthersNameNone() = runTest {
        val refused = assertFailsWith<IllegalStateException> { HostSession.line(request(blockedOnly), noName) { null } }
        assertEquals("${HostSession.REFUSED}: ${CoreConfig.BLOCKED_ONLY_REFUSAL}", refused.message)

        var asked = 0
        val global = HostSession.line(request(Routing.Global), noName, geodataDir = { asked++; "/never" }) { null }
        assertEquals(0, asked)
        assertNull(global.geodataDir)
        assertFalse("geodata_dir" in HostSession.text(global.core, geodataDir = global.geodataDir))
    }

    @Test
    fun theListIsPutInTheDirectoryAsBuiltAndRepaired() = runTest {
        val dir = Files.createTempDirectory("mac-routing-geodata").resolve("geodata")
        try {
            assertEquals(dir.toString(), HostGeodata.refilterIn(dir))
            val file = dir.resolve(CoreBlockedOnly.FILE.name)
            val first = Files.readAllBytes(file)
            Files.write(file, byteArrayOf(1, 2, 3))
            HostGeodata.refilterIn(dir)
            assertTrue(first.contentEquals(Files.readAllBytes(file)), "a file that is not the built one is replaced")
            assertFalse(Files.exists(dir.resolve("${CoreBlockedOnly.FILE.name}.partial")))
        } finally {
            dir.parent.toFile().deleteRecursively()
        }
    }

    /** A source test: the manager cannot be started in a test (it owns a tunnel and the system's proxy). */
    @Test
    fun theManagerAsksWhereARulesLiveByTheEngineItRuns() {
        val manager = File("src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt").readText()
        assertTrue(
            manager.contains("rulesHomeWithoutDaemon(desktopMode, isOlcrtc, routingSettings.needsRules, lineEngineKind())"),
            "a session's rules home ignores the engine"
        )
        assertTrue(
            manager.contains("rulesHomeWithoutDaemon(mode, isOlcrtc, routingSettings.needsRules, lineEngineKind())") &&
                manager.split("coreDirectDns()").size == 4,
            "a line added behind the tunnel ignores the engine"
        )
    }
}
