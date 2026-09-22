package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What the Android tun's hev-socks5-tunnel is started with. hev reads the file
// once, at start, and a line it reads differently from how it was meant does
// not fail the start: the tunnel comes up and the traffic that line governs
// simply does not cross. So the lines are pinned here.
class HevTunnelConfigTest {
    private fun yaml(corePort: Int?) = HevTunnelConfig.yaml(
        mtu = 1500,
        ipv4 = "10.0.88.88",
        socksAddress = "127.0.0.1",
        socksPort = 10808,
        corePort = corePort,
        username = "olcbox-user",
        password = "s3cret"
    )

    private fun socks5Block(yaml: String): List<String> =
        yaml.lines().dropWhile { it != "socks5:" }.takeWhile { it.isNotEmpty() }

    @Test
    fun olcrtcGetsTheWholeDocument() {
        assertEquals(
            """
            tunnel:
              name: tun0
              mtu: 1500
              multi-queue: false
              ipv4: 10.0.88.88

            socks5:
              address: 127.0.0.1
              port: 10808
              udp: 'udp'
              pipeline: false
              username: 'olcbox-user'
              password: 's3cret'

            mapdns:
              address: 1.1.1.1
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent(),
            yaml(corePort = null)
        )
    }

    // Anything but 'udp' makes hev ask for its own FWD UDP command, which
    // sing-box, Xray and olcRTC all refuse: the tun then carries no UDP.
    @Test
    fun udpIsRelayedByUdpAssociateToEveryServer() {
        for (corePort in listOf(null, 10810)) {
            val socks5 = socks5Block(yaml(corePort))
            assertEquals(listOf("  udp: 'udp'"), socks5.filter { it.startsWith("  udp") }, socks5.toString())
        }
    }

    // olcRTC refuses a client that offers no login when it was started with one.
    @Test
    fun olcrtcIsReachedOnItsPortWithTheLogin() {
        val socks5 = socks5Block(yaml(corePort = null))
        assertTrue("  port: 10808" in socks5, socks5.toString())
        assertTrue("  username: 'olcbox-user'" in socks5, socks5.toString())
        assertTrue("  password: 's3cret'" in socks5, socks5.toString())
    }

    // A sing-box or Xray inbound has no auth, and hev offering only
    // username/password to it is a tunnel that comes up and carries nothing.
    @Test
    fun aCoreIsReachedOnItsPortWithNoLogin() {
        val yaml = yaml(corePort = 10810)
        val socks5 = socks5Block(yaml)
        assertTrue("  port: 10810" in socks5, socks5.toString())
        assertFalse(yaml.contains("username"), yaml)
        assertFalse(yaml.contains("password"), yaml)
    }

    // The login is the user's to type; a quote left bare ends the scalar and
    // hev refuses the whole file.
    @Test
    fun aQuoteInTheLoginIsDoubled() {
        val socks5 = socks5Block(
            HevTunnelConfig.yaml(
                mtu = 1500,
                ipv4 = "10.0.88.88",
                socksAddress = "127.0.0.1",
                socksPort = 10808,
                corePort = null,
                username = "o'neil",
                password = "it's''"
            )
        )
        assertTrue("  username: 'o''neil'" in socks5, socks5.toString())
        assertTrue("  password: 'it''s'''''" in socks5, socks5.toString())
    }

    @Test
    fun mapdnsAnswersTheResolverTheTunAnnounces() {
        for (corePort in listOf(null, 10810)) {
            val mapdns = yaml(corePort).lines()
                .dropWhile { it != "mapdns:" }.takeWhile { it.isNotEmpty() }
            assertEquals(
                listOf(
                    "mapdns:",
                    "  address: ${HevTunnelConfig.MAPDNS_ADDRESS}",
                    "  port: 53",
                    "  network: 100.64.0.0",
                    "  netmask: 255.192.0.0",
                    "  cache-size: 10000"
                ),
                mapdns
            )
        }
        assertEquals("1.1.1.1", HevTunnelConfig.MAPDNS_ADDRESS)
    }
}
