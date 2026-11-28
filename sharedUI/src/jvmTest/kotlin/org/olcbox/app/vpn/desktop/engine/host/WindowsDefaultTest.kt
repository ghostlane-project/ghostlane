package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.engine.DesktopLineEngine
import org.olcbox.app.vpn.desktop.engine.DesktopRulesHome
import org.olcbox.app.vpn.desktop.engine.RoomRequest
import org.olcbox.app.vpn.desktop.engine.desktopRulesHome
import org.olcbox.app.vpn.rulesHomeWithoutDaemon
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Windows on ghostlane-engine (WD): what the default engine is made of there
 * is DesktopRustDefaultTest's; here are the rules' home in the Windows tunnel,
 * the way out a room is bound to, and the facts about the manager that no test
 * can start it to see. The real run, hev and Wintun in front of a line and a
 * room, is scripts/desktop-checks/windows-engine.sh.
 */
class WindowsDefaultTest {
    private val scratch: Path = Files.createTempDirectory("windows-default-test")

    @AfterTest
    fun clean() {
        scratch.toFile().deleteRecursively()
    }

    private val main = "src/jvmMain/kotlin/org/olcbox/app/vpn"

    @Test
    fun theWindowsTunnelKeepsARoomsRulesInItsFrontAndACoreLinesInTheCore() {
        for (olcrtc in listOf(true, false)) {
            assertEquals(
                DesktopRulesHome.Core,
                desktopRulesHome(DesktopMode.WindowsTun, olcrtc, needsRules = true, engine = DesktopLineEngine.Rust),
                "olcrtc=$olcrtc on ghostlane-engine",
            )
            assertEquals(
                DesktopRulesHome.Core,
                rulesHomeWithoutDaemon(DesktopMode.WindowsTun, olcrtc, needsRules = true, engine = DesktopLineEngine.Rust),
            )
            assertEquals(
                DesktopRulesHome.Nowhere,
                desktopRulesHome(DesktopMode.WindowsTun, olcrtc, needsRules = false, engine = DesktopLineEngine.Rust),
                "no rules asked for",
            )
            // The Go engines bound no socket of theirs on Windows: what they answered stays.
            assertEquals(
                DesktopRulesHome.Nowhere,
                desktopRulesHome(DesktopMode.WindowsTun, olcrtc, needsRules = true, engine = DesktopLineEngine.Go),
            )
        }
    }

    private val location = LocationConfig(
        id = "tcp://hub.example:7000/room-a",
        key = "ab".repeat(32),
        bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
        transport = LocationConfig.TRANSPORT_DATACHANNEL,
    ).normalized()

    private fun request(bindInterface: String?) = RoomRequest(
        location = location,
        socksHost = "127.0.0.1",
        socksPort = 10808,
        socksUser = "room-user",
        socksPass = "room-pass",
        dnsServer = "192.0.2.53:53",
        directRulesFile = null,
        dtlsProfile = OlcrtcDtls.OFF,
        deviceId = "device-1",
        bindInterface = bindInterface,
    )

    @Test
    fun aRoomAskedForNoBindingOnlyByAnInterfaceNameIsBoundToItAsTheWindowsTunnelAsksIt() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val program = standIn()
        val engine = HostRoom(binary = { program }, defaultInterface = { "must-not-be-asked" })
        val bound = Json.parseToJsonElement(engine.launch(program, request("Ethernet 2")).config()).jsonObject
        assertEquals("Ethernet 2", bound.getValue("bind").jsonObject.getValue("interface").jsonPrimitive.content)
        // No name, and no ask for the way out (proxy mode): no binding at all.
        val free = Json.parseToJsonElement(engine.launch(program, request(null)).config()).jsonObject
        assertFalse("bind" in free.keys, "a room in proxy mode is bound to nothing: ${free.keys}")
    }

    /** Source tests: the manager cannot be started in a test (it owns a tunnel and the system's proxy). */
    @Test
    fun theManagerHandsTheWindowsWayOutToTheLineAndToTheRoom() {
        val manager = File("$main/DesktopVpnManager.kt").readText()
        val room = manager.substring(manager.indexOf("val roomLaunch = engine.room.launch("))
            .substringBefore("val provider = roomLaunch.provider")
        assertTrue("bindInterface = windowsWayOut" in room, "the room's request is not given the Windows way out")
        val line = manager.substring(manager.indexOf("val started = engine.line.start("))
            .substringBefore("lineStartDone()")
        assertTrue("bindInterface = boundInterface" in line, "the line's request is not given its interface")
        assertTrue("else windowsWayOut" in manager, "a core's interface is not the Windows way out")
        assertTrue("windowsWayOut = windowsTunController.physicalRoute().alias" in manager)
    }

    @Test
    fun theEngineHasNoWindowsTunCoreAndTheManagerNeverAskedForOne() {
        val engine = File("$main/desktop/engine/DesktopEngine.kt").readText()
        assertFalse("windowsTun:" in engine, "DesktopEngine still declares a Windows tun core")
        assertFalse("interface TunCore" in engine, "TunCore is still declared")
        val host = File("$main/desktop/engine/host/HostEngine.kt").readText()
        assertFalse("windowsTun" in host.replace("windowsTunOutput", ""), "HostEngine still names a Windows tun core")
        val manager = File("$main/DesktopVpnManager.kt").readText()
        assertFalse("engine.windowsTun" in manager, "the manager asks the engine for a Windows tun core")
        assertTrue("windowsTunController.startTunnel(hev, plan)" in manager, "hev is the Windows tun")
    }

    @Test
    fun noDesktopSystemIsGivenTheGoEnginesByTheFactory() {
        val engines = File("$main/desktop/engine/DesktopEngine.kt").readText()
        val body = engines.substringAfter("fun defaultFor(").substringBefore("\n    }\n")
        assertFalse("GoEngines" in body, "defaultFor still names GoEngines: $body")
    }

    private fun standIn(): Path {
        val file = scratch.resolve("stand-in").toFile()
        file.writeText(
            listOf(
                "#!/bin/sh",
                "case \"${'$'}1\" in",
                "  version)",
                "    echo '{\"event\":\"version\",\"engine\":\"0.10.0\",\"protocol\":1," +
                    "\"pin\":\"0123456789abcdef0123456789abcdef01234567\",\"boringssl\":true,\"rooms\":true,\"target\":\"stand-in\"}'",
                "    ;;",
                "  *) exit 2 ;;",
                "esac",
            ).joinToString("\n", postfix = "\n")
        )
        file.setExecutable(true, true)
        return file.toPath()
    }
}
