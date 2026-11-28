package org.olcbox.app.vpn.desktop.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The seam between the desktop session and what carries its lines, read from
 * the source: the manager decides what a session is and asks a
 * [DesktopEngine] for everything a line runs, so that another engine is
 * another implementation and not another manager.
 *
 * A source test, because the manager cannot be started in a test: it owns the
 * system's proxy, a tunnel and child processes.
 */
class DesktopEngineSeamTest {
    private val main = "src/jvmMain/kotlin/org/olcbox/app/vpn"
    private val manager = File("$main/DesktopVpnManager.kt").readText()
    private fun engineFile(name: String) = File("$main/desktop/engine/$name")

    @Test
    fun theManagerNamesNoCoreOfALine() {
        for (name in listOf("DesktopSingBoxController", "DesktopXrayController")) {
            assertFalse(name in manager, "DesktopVpnManager.kt still names $name; a line's cores belong to an engine")
        }
        assertFalse(engineFile("GoEngines.kt").exists(), "GoEngines.kt is back; no Go core is run by the desktop")
        assertTrue(engineFile("DesktopEngine.kt").isFile, "DesktopEngine.kt is missing")
    }

    /** Moved, and nothing else: the function reads as it did in the manager. */
    @Test
    fun theRulesHomeMovedAsItWas() {
        assertFalse("fun desktopRulesHome" in manager, "desktopRulesHome is still defined in DesktopVpnManager.kt")
        assertFalse("enum class DesktopRulesHome" in manager, "DesktopRulesHome is still defined in DesktopVpnManager.kt")
        val home = engineFile("RulesHome.kt")
        assertTrue(home.isFile, "RulesHome.kt is missing")
        val text = home.readText().replace("\r\n", "\n")
        val asItWas = """
            internal fun desktopRulesHome(mode: DesktopMode, isOlcrtc: Boolean, needsRules: Boolean): DesktopRulesHome =
                when {
                    !needsRules -> DesktopRulesHome.Nowhere
                    mode == DesktopMode.SystemProxy -> DesktopRulesHome.Core
                    mode == DesktopMode.MacTun -> DesktopRulesHome.Daemon
                    mode == DesktopMode.LinuxTun && isOlcrtc -> DesktopRulesHome.Engine
                    else -> DesktopRulesHome.Nowhere
                }
        """.trimIndent()
        assertTrue(asItWas in text, "desktopRulesHome in RulesHome.kt is not the function the manager had")
    }

    @Test
    fun theManagerNamesNoRoomCommand() {
        assertFalse("OlcRtcCommand" in manager, "DesktopVpnManager.kt still names OlcRtcCommand; a room's command belongs to its engine")
        // What the room's program prints is its engine's to read.
        assertFalse("SOCKS5 server listening" in manager, "the manager still reads the olcRTC engine's own words")
    }

    /** Which programs a line runs is the engine's to say, down to their paths. */
    @Test
    fun theManagerResolvesNoProgramOfALine() {
        for (name in listOf("resolveSingBoxBinary", "resolveXrayBinary", "resolveOlcRtcBinaryCandidates")) {
            assertFalse(name in manager, "DesktopVpnManager.kt still calls DesktopNativeAssets.$name")
        }
    }

    /**
     * The two things an engine may ask of a session, and that the manager
     * answers both with the restart behind the tun.
     */
    @Test
    fun anEngineCanAskForTheRestartBehindTheTun() {
        val seam = engineFile("DesktopEngine.kt").readText()
        assertTrue("interface SessionHooks" in seam, "DesktopEngine.kt has no SessionHooks")
        for (hook in listOf("fun lineDied(", "fun wayOutChanged(")) {
            assertTrue(hook in seam, "SessionHooks has no $hook")
            assertTrue("override $hook" in manager, "the manager does not answer $hook")
        }
        for (answer in listOf("restartBehindTunForEngine", "restartBehindTunOnNewWayOut")) {
            val body = manager.substringAfter("private fun $answer(", "").substringBefore("\n    }\n")
            assertTrue(
                "restartLineBehindTun(" in body || "handleUnexpectedProcessExit(" in body,
                "$answer does not end in the restart behind the tun"
            )
        }
        // An interface watch has a session to belong to: told when one is up and when it goes.
        assertTrue("engine.sessionUp(" in manager, "the manager does not tell the engine a session is up")
        assertTrue("engine.sessionDown()" in manager, "the manager does not tell the engine a session is stopped")
    }
}
