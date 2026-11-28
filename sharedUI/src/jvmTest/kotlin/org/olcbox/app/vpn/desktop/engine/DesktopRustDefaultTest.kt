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

    /** The watch reads the way out by `ip route` or `route get`: on Windows it would read nothing, so none wraps it. */
    @Test
    fun windowsEngineIsNotWrappedInAWatchThatReadsNothing() {
        assertTrue(DesktopEngines.defaultFor(DesktopOs.Windows, host) is HostEngine)
        for (os in listOf(DesktopOs.Linux, DesktopOs.MacOS)) {
            assertTrue(DesktopEngines.defaultFor(os, host) is WatchedEngine, "$os is watched")
        }
    }

    @Test
    fun theFactoryAManagerIsGivenRunsTheRustEngineOnTheSystemItRunsOn() {
        val os = DesktopPaths.os
        assertTrue(DesktopEngines.runsOnRust(DesktopEngines.create(host)), "the default factory on $os")
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
