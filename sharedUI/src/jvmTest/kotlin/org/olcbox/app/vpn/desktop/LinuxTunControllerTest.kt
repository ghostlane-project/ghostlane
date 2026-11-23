package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file hev reads in the Linux tunnel, for each engine the tunnel can
 * stand in front of. The Go engines take UDP ASSOCIATE and nothing else;
 * ghostlane-engine's SOCKS inbound with a login refuses an association that
 * names no port, which is what hev sends, and takes hev's own FWD UDP lane.
 * scripts/desktop-checks/linux-tun.sh runs both with the real hev.
 */
class LinuxTunControllerTest {
    // What hev was given before the Rust engine, to the byte: the Go engines'
    // tunnel is not changed by it.
    @Test
    fun theGoEnginesTunnelIsWhatItWas() {
        val config = LinuxTunController.configContent(
            socksPort = 10810,
            postUpScript = "/home/a/.olcbox/linux-tun-up.sh",
            preDownScript = "/home/a/.olcbox/linux-tun-down.sh",
            username = "ghost",
            password = "it's"
        )

        assertEquals(
            """
            tunnel:
              name: olcbox0
              mtu: 1500
              multi-queue: false
              ipv4: 10.0.88.88
              post-up-script: /home/a/.olcbox/linux-tun-up.sh
              pre-down-script: /home/a/.olcbox/linux-tun-down.sh

            socks5:
              address: 127.0.0.1
              port: 10810
              username: 'ghost'
              password: 'it''s'
              udp: 'udp'
              pipeline: false

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
            config
        )
        assertEquals(config, LinuxTunController.configContent(
            socksPort = 10810,
            postUpScript = "/home/a/.olcbox/linux-tun-up.sh",
            preDownScript = "/home/a/.olcbox/linux-tun-down.sh",
            username = "ghost",
            password = "it's",
            udp = LinuxTunController.Udp.Associate
        ))
    }

    // In front of ghostlane-engine the datagrams ride hev's FWD UDP lane, and
    // nothing else of the file differs.
    @Test
    fun beforeGhostlaneEngineTheDatagramsRideTheForwardLane() {
        val associate = LinuxTunController.configContent(socksPort = 10810, username = "u", password = "p")
        val inTcp = LinuxTunController.configContent(
            socksPort = 10810,
            username = "u",
            password = "p",
            udp = LinuxTunController.Udp.InTcp
        )

        assertTrue("\n  udp: 'tcp'\n" in inTcp, inTcp)
        assertEquals(associate.replace("\n  udp: 'udp'\n", "\n  udp: 'tcp'\n"), inTcp)
        // The port is still what a leftover tunnel is asked by.
        assertEquals(10810, LinuxTunController.socksPortOf(inTcp))
    }

    // The words hev reads, one per lane: anything but 'udp' is its FWD UDP.
    @Test
    fun eachLaneIsTheWordHevReads() {
        assertEquals("udp", LinuxTunController.Udp.Associate.word)
        assertEquals("tcp", LinuxTunController.Udp.InTcp.word)
    }
}
