package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

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
