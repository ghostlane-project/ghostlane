package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDnsResolverTest {

    @Test
    fun aProviderUrlGivesItsAddressesInOrder() {
        assertEquals(
            listOf("192.168.1.1", "2a01:4ff:ff00::add:2", "fe80::1%en0", "10.0.0.53"),
            DesktopDnsResolver.providerUrlServers(
                "dns://192.168.1.1 dns://[2a01:4ff:ff00::add:2] dns://[fe80::1%en0]:53 dns://10.0.0.53:53/example.com"
            )
        )
    }

    /** Windows lists fec0::/10 on an adapter with no IPv6 server; nobody answers there. */
    @Test
    fun placeholdersNamesAndRepeatsAreDropped() {
        assertEquals(
            listOf("192.168.1.1"),
            DesktopDnsResolver.providerUrlServers(
                "dns://[fec0:0:0:ffff::1%1] dns://[fec0:0:0:ffff::2%1] dns://localhost dns:// dns://192.168.1.1 dns://192.168.1.1"
            )
        )
    }

    /**
     * The resolver sing-box gives its tun's adapter sits in the tun's own
     * network. A core beside the tun cannot ask it: its query leaves by the
     * physical interface, where nobody has that address. It is on the system's
     * list while a tun is up, and for a moment after one was stopped.
     */
    @Test
    fun theTunsOwnResolverIsNotOneOfTheMachines() {
        assertEquals(
            listOf("192.168.1.1", "172.19.0.4", "fe80::1%en0", "2a01:4ff:ff00::add:2"),
            DesktopDnsResolver.withoutTunResolvers(
                listOf(
                    "172.19.0.2", "192.168.1.1", "172.19.0.1", "172.19.0.4",
                    "fdfe:dcba:9876::2", "fe80::1%en0", "2a01:4ff:ff00::add:2"
                )
            )
        )
        assertEquals(emptyList(), DesktopDnsResolver.withoutTunResolvers(listOf("172.19.0.2")))
    }

    /**
     * On Windows the tunnel's adapter names 1.1.1.1 as its resolver, an address a
     * machine may use for real as well. The tunnel's adapters are dropped by
     * their names (the tunnel's and the old one's), not by the address, and a
     * real adapter that names 1.1.1.1 keeps it.
     */
    @Test
    fun theWindowsTunnelAdaptersResolversAreLeftOutByAdapterNotByAddress() {
        val read = "Ethernet|192.168.1.1,8.8.8.8\r\nGhostlane-Tun|1.1.1.1\r\nOlcbox|172.19.0.2\r\nWi-Fi|1.1.1.1,192.168.1.1\r\n"
        assertEquals(
            listOf("192.168.1.1", "8.8.8.8", "1.1.1.1"),
            WindowsTunController.physicalDnsServers(read)
        )
        assertEquals(emptyList(), WindowsTunController.physicalDnsServers("Ghostlane-Tun|1.1.1.1\n"))
        assertEquals(emptyList(), WindowsTunController.physicalDnsServers("garbage with no bar\n\n"))
        assertEquals(listOf("10.0.0.1"), WindowsTunController.physicalDnsServers("ethernet 2|10.0.0.1,fe80::1,,x\n"))
    }

    @Test
    fun theWindowsReadsAreTheTunnelsOwnScriptsAndTheOtherAdaptersAreNotTouched() {
        assertTrue(WindowsTunController.isTunAdapter("Ghostlane-Tun"))
        assertTrue(WindowsTunController.isTunAdapter("ghostlane-tun2"))
        assertTrue(WindowsTunController.isTunAdapter("Olcbox"))
        assertFalse(WindowsTunController.isTunAdapter("Ethernet"))
        assertFalse(WindowsTunController.isTunAdapter("Wi-Fi"))
        val route = WindowsTunController.PHYSICAL_ROUTE_SCRIPT
        assertTrue("-notlike 'Ghostlane-*'" in route && "'Olcbox'" in route, "the way out must leave the tunnel's adapters out")
        assertTrue("0.0.0.0/0" in route && "RouteMetric" in route)
        assertTrue("Get-DnsClientServerAddress" in WindowsTunController.DNS_BY_ADAPTER_SCRIPT)
    }

    /** Source test: the watch's reader on Windows is the manager's own question, and the resolver read is by adapter. */
    @Test
    fun theWatchAsksWindowsTheWayTheManagerDoes() {
        val watch = java.io.File("src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/PhysicalInterfaceWatch.kt").readText()
        assertTrue("DesktopOs.Windows -> WindowsTunController.physicalAliasNow()" in watch)
        val resolver = java.io.File("src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopDnsResolver.kt").readText()
        assertTrue("DesktopOs.Windows -> WindowsTunController.physicalDnsServersNow()" in resolver)
        val tun = java.io.File("src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/WindowsTunController.kt").readText()
        assertTrue(tun.contains("windowsPowerShell(psScript(PHYSICAL_ROUTE_SCRIPT)"), "physicalRoute and the watch share one script")
    }

    /**
     * The JDK's DNS provider on a Unix host reads `/etc/resolv.conf`, as it does on
     * macOS, where configd writes that file from the primary resolver. So on the test
     * host the list has to be the file's nameservers, the first five of them.
     */
    @Test
    fun theSystemsServersAreTheOnesItLists() {
        val conf = Path.of("/etc/resolv.conf")
        if (!Files.isReadable(conf)) return
        val listed = Files.readAllLines(conf)
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("nameserver ") }
            .map { it.removePrefix("nameserver ").trim() }
            .take(5)
        val asUrl = listed.joinToString(" ") { if (':' in it) "dns://[$it]" else "dns://$it" }
        assertEquals(DesktopDnsResolver.providerUrlServers(asUrl), DesktopDnsResolver.systemServers())
    }
}
