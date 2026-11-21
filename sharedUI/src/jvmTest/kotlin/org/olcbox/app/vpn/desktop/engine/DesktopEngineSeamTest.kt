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
            assertFalse(name in manager, "DesktopVpnManager.kt still names $name; a line's cores belong to GoEngines.kt")
        }
        val go = engineFile("GoEngines.kt")
        assertTrue(go.isFile, "GoEngines.kt is missing")
        val text = go.readText()
        assertTrue("DesktopSingBoxController" in text && "DesktopXrayController" in text, "GoEngines.kt holds the two cores")
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
}
