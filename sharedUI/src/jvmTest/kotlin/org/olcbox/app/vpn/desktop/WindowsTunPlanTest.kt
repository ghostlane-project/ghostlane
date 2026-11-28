package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Windows tunnel tells hev and PowerShell, read as text; runs on every OS. */
class WindowsTunPlanTest {
    private val plan = WindowsTunPlan(socksPort = 4321, spared = listOf("203.0.113.77"))
    private val physical = PhysicalRoute(alias = "Ethernet 2", index = 12, gateway = "10.1.0.1")

    @Test fun hevIsToldItsAdapterItsAddressAndTheEnginesPort() {
        val text = WindowsTunController.hevConfig(plan)
        assertTrue("  name: Ghostlane-Tun" in text)
        assertTrue("  ipv4: 198.18.0.1" in text)
        assertTrue("  port: 4321" in text)
        assertTrue("  udp: 'tcp'" in text, "in front of ghostlane-engine datagrams ride the FWD UDP lane")
        assertTrue("  address: 1.1.1.1" in text, "the resolver the tunnel names")
        assertFalse("username" in text)
        val login = WindowsTunController.hevConfig(plan.copy(username = "u'x", password = "p"))
        assertTrue("  username: 'u''x'" in login && "  password: 'p'" in login)
    }

    @Test fun theDefaultRouteIsSplitAndEachSparedAddressKeepsThePhysicalWay() {
        val spared = listOf(WindowsRouteRecord("203.0.113.77/32", physical.index))
        val script = WindowsTunController.routesUpScript(plan, physical, spared)
        assertTrue("-DestinationPrefix '0.0.0.0/1' -InterfaceAlias 'Ghostlane-Tun'" in script)
        assertTrue("-DestinationPrefix '128.0.0.0/1' -InterfaceAlias 'Ghostlane-Tun'" in script)
        assertTrue("-DestinationPrefix '203.0.113.77/32' -InterfaceIndex 12 -NextHop '10.1.0.1'" in script)
        assertFalse("§" in script, "every placeholder is replaced")
        assertTrue("\$half" in script)
    }

    @Test fun theGuardBlocksTheDnsClientFromEveryResolverButTheTunnels() {
        assertEquals(listOf("0.0.0.0-1.1.1.0", "1.1.1.2-255.255.255.255"), WindowsDnsGuard.otherThan("1.1.1.1"))
        assertEquals(listOf("0.0.0.2-255.255.255.255"), WindowsDnsGuard.otherThan("0.0.0.1").drop(1))
        val script = WindowsDnsGuard.applyScript("Ghostlane-Tun", "1.1.1.1")
        for (proto in listOf("UDP", "TCP")) for (family in listOf("4", "6")) {
            assertTrue("-Name 'Ghostlane-DNS-Cache-$proto-$family'" in script, "$proto $family")
        }
        assertEquals(4, Regex("New-NetFirewallRule").findAll(script).count())
        assertTrue("-Direction Outbound -Action Block" in script && "-Service Dnscache" in script)
        assertTrue("-RemotePort 53,853" in script)
        assertTrue("Add-DnsClientNrptRule -Namespace '.' -NameServers '1.1.1.1' -DisplayName 'Ghostlane-DNS'" in script)
        assertFalse("§" in script)
        val remove = WindowsDnsGuard.removeScript()
        assertTrue("Remove-NetFirewallRule" in remove && "Remove-DnsClientNrptRule" in remove)
    }

    @Test fun theSweepNamesWhatItTakesAndTheJournalSurvivesARoundTrip() {
        val journal = WindowsTunJournal(Files.createTempDirectory("w2").resolve("j.json"))
        assertNull(journal.read())
        val record = WindowsTunRecord("Ghostlane-Tun", "C:\\x\\hev.exe", 99, listOf(WindowsRouteRecord("203.0.113.77/32", 12)), true)
        journal.write(record)
        assertEquals(record, journal.read())
        journal.update { it.copy(guard = false) }
        assertEquals(false, journal.read()!!.guard)
        val script = WindowsSweeper.removeScript(record.routes)
        assertTrue("Remove-NetRoute -DestinationPrefix \$p -InterfaceIndex \$i" in script)
        assertTrue("'203.0.113.77/32'" in script)
        journal.clear()
        assertNull(journal.read())
    }

    @Test fun thePhysicalRouteIsReadFromTheScriptsLine() {
        assertEquals(physical, WindowsTunController.parsePhysicalRoute("Ethernet 2|12|10.1.0.1\r\n"))
        assertTrue(runCatching { WindowsTunController.parsePhysicalRoute("") }.isFailure)
    }
}
