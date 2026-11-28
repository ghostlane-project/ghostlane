package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.WatchedEngine
import org.olcbox.app.vpn.desktop.engine.host.HostEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a desktop build runs when nothing installs an engine. Linux's and
 * macOS's packages carry ghostlane-engine and no Go core, so a manager that
 * got a Go engine there would find no program to start.
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
    fun windowsRunsTheRustEngineToo_andNoGoEngineIsTheDefaultOnAnySystem() {
        for (os in DesktopOs.entries) {
            val engine = DesktopEngines.defaultFor(os, host)
            assertTrue(DesktopEngines.runsOnRust(engine), "$os still gets the Go engines")
        }
    }

    /** Every desktop system that carries a tun is watched; the watch reads the way out by its own system's call. */
    @Test
    fun linuxMacAndWindowsAreWatchedForAChangeOfTheWayOut() {
        for (os in listOf(DesktopOs.Linux, DesktopOs.MacOS, DesktopOs.Windows)) {
            val engine = DesktopEngines.defaultFor(os, host)
            assertTrue(engine is WatchedEngine, "$os is watched")
            assertTrue((engine as WatchedEngine).inner is HostEngine, "$os is watched on ghostlane-engine")
        }
    }

    /** A source test: the manager cannot be started in a test. On a move the next start binds to the new alias. */
    @Test
    fun aMoveOnWindowsGivesTheNewAliasToTheNextLineAndRoom() {
        val manager = File("src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt").readText()
        val body = manager.substringAfter("private fun restartBehindTunOnNewWayOut").substringBefore("\n    }\n")
        assertTrue(
            Regex("""DesktopMode\.WindowsTun\) windowsWayOut = interfaceName""").containsMatchIn(body),
            "a move on Windows must replace windowsWayOut before the line restarts: $body"
        )
        assertTrue(body.indexOf("windowsWayOut = interfaceName") < body.indexOf("restartLineBehindTun"))
    }

    @Test
    fun theFactoryAManagerIsGivenRunsTheRustEngineOnTheSystemItRunsOn() {
        val os = DesktopPaths.os
        assertTrue(DesktopEngines.runsOnRust(DesktopEngines.create(host)), "the default factory on $os")
    }

    /** A source test: the manager cannot be started in a test (it owns a tunnel and the system's proxy). */
    @Test
    fun theMacTunnelIsStartedOnTheEngineVerbAndNeverWithAConfigOfTheApps() {
        val manager = File("src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt").readText()
        val from = manager.indexOf("private suspend fun startMacTun(")
        assertTrue(from >= 0, "DesktopVpnManager has no startMacTun")
        val body = manager.substring(from, manager.indexOf("\n    }\n", from))
        assertTrue("macOsTunController.start(" in body, "startMacTun does not start the helper's tunnel")
        assertFalse("rustEngine" in body, "startMacTun still has a second kind of tunnel to choose")
        assertFalse("ruleFiles" in body, "startMacTun still hands the helper rule-set files for a sing-box")
        assertFalse("SingBoxTunTraffic" in body, "startMacTun still counts through a sing-box API")
        assertFalse("newTrafficApi" in body, "startMacTun still asks for a sing-box API")
    }
}
