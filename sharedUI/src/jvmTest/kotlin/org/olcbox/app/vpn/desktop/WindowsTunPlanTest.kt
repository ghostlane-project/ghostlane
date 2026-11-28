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
        val record = WindowsTunRecord("Ghostlane-Tun", "C:\\x\\hev.exe", 99, listOf(WindowsRouteRecord("203.0.113.77/32", 12, "10.1.0.1")), true)
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

    private val moved = PhysicalRoute(alias = "Wi-Fi", index = 7, gateway = "192.168.1.1")

    @Test fun aMoveOfTheWayOutTakesTheOldSparedRoutesAndLaysThemOnTheNewOne() {
        val old = listOf(WindowsRouteRecord("203.0.113.77/32", 12, "10.1.0.1"), WindowsRouteRecord("198.51.100.5/32", 12, "10.1.0.1"))
        val move = WindowsTunController.planSparedMove(old, moved, addresses = null)
        assertEquals(old, move.remove, "no route stays on the old interface")
        assertEquals(
            listOf(WindowsRouteRecord("203.0.113.77/32", 7, "192.168.1.1"), WindowsRouteRecord("198.51.100.5/32", 7, "192.168.1.1")),
            move.add,
        )
        assertTrue(move.keep.isEmpty())
        val script = WindowsTunController.moveSparedScript(move, moved)
        assertTrue("Remove-NetRoute -DestinationPrefix '203.0.113.77/32' -InterfaceIndex 12" in script)
        assertTrue("New-NetRoute -DestinationPrefix '203.0.113.77/32' -InterfaceIndex 7 -NextHop '192.168.1.1'" in script)
        assertTrue(script.indexOf("Remove-NetRoute") < script.indexOf("New-NetRoute"), "the old go first")
        assertFalse("§" in script)
    }

    @Test fun aReResolvedSetReplacesTheOldOneAndOneAlreadyThereStays() {
        val old = listOf(WindowsRouteRecord("203.0.113.77/32", 7, "192.168.1.1"), WindowsRouteRecord("198.51.100.5/32", 7, "192.168.1.1"))
        val move = WindowsTunController.planSparedMove(old, moved, addresses = listOf("203.0.113.77", "192.0.2.9", "100.64.3.4", "not-an-address"))
        assertEquals(listOf(WindowsRouteRecord("203.0.113.77/32", 7, "192.168.1.1")), move.keep)
        assertEquals(listOf(WindowsRouteRecord("198.51.100.5/32", 7, "192.168.1.1")), move.remove)
        assertEquals(listOf(WindowsRouteRecord("192.0.2.9/32", 7, "192.168.1.1")), move.add, "the tunnel's own answer is no server's address")
    }

    @Test fun nothingUsableToResolveKeepsTheAddressesAndAnUnmovedRouteIsLeftAlone() {
        val old = listOf(WindowsRouteRecord("203.0.113.77/32", 12, "10.1.0.1"))
        val fake = WindowsTunController.planSparedMove(old, moved, addresses = listOf("100.64.0.9", "100.127.255.1"))
        assertEquals(listOf(WindowsRouteRecord("203.0.113.77/32", 7, "192.168.1.1")), fake.add)
        val same = WindowsTunController.planSparedMove(old, physical, addresses = null)
        assertTrue(same.remove.isEmpty() && same.add.isEmpty() && same.keep == old)
        val gatewayOnly = WindowsTunController.planSparedMove(old, physical.copy(gateway = "10.1.0.254"), null)
        assertEquals(old, gatewayOnly.remove, "the same interface with another next hop is moved too")
        assertTrue(WindowsTunController.isMapdnsAddress("100.64.0.1") && !WindowsTunController.isMapdnsAddress("100.128.0.1"))
    }
}
