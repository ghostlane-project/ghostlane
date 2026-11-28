package org.olcbox.app.vpn.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The firewall rule for LAN sharing: what it names, and when it goes. */
class WindowsLanFirewallTest {
    private val engine = Path.of("build", "ghostlane engine's", "ghostlane-engine.exe")

    @Test fun theRuleNamesTheEngineTheAddressThePortAndTheLocalSubnetOnly() {
        val script = WindowsLanFirewall.addScript("Ghostlane-LAN-1", "192.168.1.20", 10809, engine)
        val path = engine.toAbsolutePath().normalize().toString().replace("'", "''")
        assertTrue("-Program '$path'" in script, script)
        for (part in listOf("-Direction Inbound", "-Action Allow", "-Protocol TCP", "-LocalAddress '192.168.1.20'",
            "-LocalPort 10809", "-RemoteAddress LocalSubnet", "-Profile Private", "-Name 'Ghostlane-LAN-1'")) {
            assertTrue(part in script, "$part in $script")
        }
        assertFalse("Any" in script)
    }

    @Test fun anAddressThatIsNotAnIpv4OrAPortThatIsNotAPortIsRefusedBeforeAnyScript() {
        for (host in listOf("", "localhost", "192.168.1.20'; Remove-Item x; '", "999.1.1.1", "::1")) {
            assertFailsWith<IllegalArgumentException>(host) { WindowsLanFirewall.addScript("r", host, 1, engine) }
        }
        for (port in listOf(0, -1, 65536)) {
            assertFailsWith<IllegalArgumentException> { WindowsLanFirewall.addScript("r", "10.0.0.2", port, engine) }
        }
    }

    @Test fun theNameIsKeptBeforeTheScriptRunsSoATimeoutStillLeavesItToRemove() {
        val scripts = mutableListOf<String>()
        val firewall = WindowsLanFirewall(run = { scripts += it; if (scripts.size == 1) error("timed out") else "" }, newId = { "7" })
        assertFailsWith<IllegalStateException> { firewall.add("10.0.0.2", 1080, engine) }
        assertEquals("Ghostlane-LAN-7", firewall.rule)
        assertTrue(firewall.remove())
        assertTrue(WindowsLanFirewall.removeScript("Ghostlane-LAN-7") == scripts.last())
        assertNull(firewall.rule)
    }

    @Test fun aRemoveThatFailsKeepsTheNameForTheSweep() {
        var fail = false
        val firewall = WindowsLanFirewall(run = { if (fail) error("busy") else "" }, newId = { "9" })
        firewall.add("10.0.0.2", 1080, engine)
        fail = true
        assertFalse(firewall.remove())
        assertEquals("Ghostlane-LAN-9", firewall.rule)
    }

    @Test fun theSweepIsByThePrefixAndRemovesWhoeverAddedTheRule() {
        val script = WindowsLanFirewall.sweepScript()
        assertTrue("-Name 'Ghostlane-LAN-*'" in script)
        assertTrue("Remove-NetFirewallRule" in script)
    }
}
