package org.olcbox.app.vpn.desktop.engine.host

import org.olcbox.app.vpn.desktop.engine.DesktopLineEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source tests: ghostlane-engine's desktop engine hands nothing to the Go
 * engines, and the manager asks for the rules' home of the engine it runs on.
 * GoEngines.kt is gone.
 */
class HostEngineNoGoTest {
    private val main = "src/jvmMain/kotlin/org/olcbox/app/vpn"

    private fun hostSources(): List<File> =
        File("$main/desktop/engine/host").listFiles { file -> file.extension == "kt" }.orEmpty().sortedBy { it.name }

    @Test
    fun nothingOfTheHostEngineReachesTheGoEngines() {
        val sources = hostSources()
        assertTrue(sources.any { it.name == "HostEngine.kt" } && sources.any { it.name == "HostRoom.kt" }, sources.toString())
        for (file in sources) {
            val code = file.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }
                .joinToString("\n")
            for (name in listOf(
                "GoEngines", "GoLine", "GoRoom", "GoProbes", "DesktopSingBoxController", "DesktopXrayController",
                "resolveSingBoxBinary", "resolveXrayBinary", "resolveOlcRtcBinaryCandidates", "LineConfigs",
            )) {
                assertFalse(name in code, "${file.name} reaches $name")
            }
        }
        assertFalse(File("$main/desktop/engine/GoEngines.kt").exists(), "GoEngines.kt is gone, and stays gone")
    }

    @Test
    fun theManagerAsksForTheRulesHomeOfTheEngineItRunsOn() {
        val manager = File("$main/DesktopVpnManager.kt").readText()
        var from = manager.indexOf("rulesHomeWithoutDaemon(")
        var calls = 0
        while (from >= 0) {
            val head = manager.substring(maxOf(0, from - 12), from)
            if (!head.contains("fun ")) {
                val call = manager.substring(from, manager.indexOf(")\n", from) + 1)
                assertTrue("lineEngineKind()" in call, "a call of the 3-argument rules home: $call")
                calls++
            }
            from = manager.indexOf("rulesHomeWithoutDaemon(", from + 1)
        }
        assertEquals(2, calls, "the connect and a line behind the tunnel each ask")
        assertTrue("DesktopLineEngine.Rust" in manager)
    }

    @Test
    fun aRoomThatCarriesItsFrontIsNotGivenASecondProcessForIt() {
        val manager = File("$main/DesktopVpnManager.kt").readText()
        assertTrue("engine.room.frontInRoom" in manager, "the manager never asks whether the room carries its front")
        assertEquals(DesktopLineEngine.Rust, DesktopLineEngine.valueOf("Rust"))
    }
}
