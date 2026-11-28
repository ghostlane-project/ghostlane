package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a desktop build runs when nothing installs an engine. Linux's and
 * macOS's packages carry ghostlane-engine and no Go core, so a manager that
 * got [GoEngines] there would find no program to start.
 */
class DesktopRustDefaultTest {
    private val host = DesktopEngineHost(
        log = {},
        windowsTunOutput = {},
        session = object : SessionHooks {
            override fun lineDied(what: String) = Unit
            override fun wayOutChanged(interfaceName: String?) = Unit
        }
    )

    @Test
    fun linuxAndMacRunTheRustEngineByDefault() {
        for (os in listOf(DesktopOs.Linux, DesktopOs.MacOS)) {
            assertTrue(DesktopEngines.runsOnRust(DesktopEngines.defaultFor(os, host)), "$os still gets the Go engines")
        }
    }

    @Test
    fun windowsKeepsTheGoEnginesUntilItsOwnFlip() {
        assertFalse(DesktopEngines.runsOnRust(DesktopEngines.defaultFor(DesktopOs.Windows, host)))
    }

    @Test
    fun theFactoryAManagerIsGivenFollowsTheSystemItRunsOn() {
        val os = DesktopPaths.os
        assertEquals(
            os == DesktopOs.Linux || os == DesktopOs.MacOS,
            DesktopEngines.runsOnRust(DesktopEngines.create(host)),
            "the default factory on $os"
        )
    }

    /** A source test: the manager cannot be started in a test (it owns a tunnel and the system's proxy). */
    @Test
    fun theMacTunnelIsStartedOnTheEngineVerbAndNeverWithASingBoxConfig() {
        val manager = File("src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt").readText()
        val from = manager.indexOf("private suspend fun startMacTun(")
        assertTrue(from >= 0, "DesktopVpnManager has no startMacTun")
        val body = manager.substring(from, manager.indexOf("\n    }\n", from))
        assertTrue("rustEngine = true" in body, "startMacTun does not ask for the engine's tunnel")
        assertFalse("ruleFiles" in body, "startMacTun still hands the helper rule-set files for a sing-box")
        assertFalse("SingBoxTunTraffic" in body, "startMacTun still counts through a sing-box API")
        assertFalse("newTrafficApi" in body, "startMacTun still asks for a sing-box API")
    }
}
