package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.Routing
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacOsTunControllerTest {

    @Test
    fun everyResolvedServerAddressBecomesAnExclusion() {
        assertEquals(
            listOf("203.0.113.7/32", "198.51.100.9/32", "2001:db8::1/128"),
            MacOsTunController.excludeCidrs(listOf("203.0.113.7", "198.51.100.9", "2001:db8::1"))
        )
    }

    @Test
    fun aHostThatDoesNotResolveIsNotSilentlyLeftUnexcluded() {
        val logs = mutableListOf<String>()
        val controller = MacOsTunController(
            addLog = { logs += it },
            client = TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            resolve = { emptyList() }
        )

        assertFailsWith<IllegalStateException> {
            runBlocking {
                controller.start(
                    corePort = 10810,
                    verifyPort = 10811,
                    username = "",
                    password = "",
                    serverHost = "de1.example.org",
                    upstreamUdpIsLossy = false
                )
            }
        }
        assertTrue(logs.any { "de1.example.org" in it }, "the log has to name the host: $logs")
    }

    @Test
    fun anAbsentDaemonFailsLoudlyRatherThanReportingAStartedTunnel() {
        val logs = mutableListOf<String>()
        val controller = MacOsTunController(
            addLog = { logs += it },
            client = TunnelDaemonClient(Path.of("/var/run/org.olcbox.app.tunneld.missing")),
            resolve = { listOf("203.0.113.7") }
        )

        assertFailsWith<IllegalStateException> {
            runBlocking {
                controller.start(
                    corePort = 10810,
                    verifyPort = 10811,
                    username = "",
                    password = "",
                    serverHost = "de1.example.org",
                    upstreamUdpIsLossy = false
                )
            }
        }
        assertTrue(logs.any { "macOS TUN failed" in it }, "the failure has to reach the log: $logs")
    }
    private val routeOutput = """
           route to: default
        destination: default
               mask: default
            gateway: 192.168.1.1
          interface: en0
              flags: <UP,GATEWAY,DONE,STATIC,PRCLO>
    """.trimIndent()

    @Test fun readsTheInterfaceBehindTheDefaultRoute() {
        assertEquals("en0", MacOsTunController.defaultInterfaceName(routeOutput))
    }

    @Test fun aTunIsNotAnAnswer() {
        // The tunnel of the previous session, still up: binding direct traffic to
        // it would send that traffic into the tunnel being replaced.
        assertNull(MacOsTunController.defaultInterfaceName(routeOutput.replace("en0", "utun7")))
    }

    @Test fun noRouteMeansNoName() {
        assertNull(MacOsTunController.defaultInterfaceName("route: writing to routing socket: not in table"))
        assertNull(MacOsTunController.defaultInterfaceName(""))
    }

    private val bypass = Routing.Rules("/Library/Application Support/org.olcbox.app/rules", DirectDns.System)
    private val ruleFiles = mapOf("geoip-ru.srs" to "eA==")
    private val idle = """{"ok":true,"state":"idle","logTail":"","protocol":2}"""
    private val running = """{"ok":true,"state":"running","pid":5,"logTail":"","protocol":2}"""

    private fun MacOsTunController.connect(routing: Routing, files: Map<String, String>) = runBlocking {
        start(
            corePort = 10810,
            verifyPort = 10811,
            username = "",
            password = "",
            serverHost = null,
            upstreamUdpIsLossy = false,
            routing = routing,
            ruleFiles = files
        )
    }

    @Test fun anOlderHelperIsRefusedBeforeItIsHandedAConfig() = withFakeDaemon(
        // No protocol number: the daemon from before rule files.
        listOf("""{"ok":true,"state":"idle","logTail":""}""")
    ) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        val failure = assertFailsWith<IllegalStateException> { controller.connect(bypass, ruleFiles) }
        assertTrue("launchctl kickstart" in failure.message.orEmpty(), failure.message)
        // Refused on the status alone: a config it cannot honour never reaches it.
        assertEquals(listOf("status"), daemon.verbs)
    }

    @Test fun aHelperSteppingAsideForAnUpdateIsFollowedToTheNewOne() = withFakeDaemon(
        listOf(
            idle,
            """{"ok":false,"error":"the tunnel helper was updated and is restarting","restarting":true,"logTail":""}""",
            idle,
            running
        )
    ) { daemon ->
        val log = mutableListOf<String>()
        val controller = MacOsTunController(log::add, TunnelDaemonClient(daemon.path), { emptyList() }, { "en0" })
        controller.connect(bypass, ruleFiles)
        assertEquals(listOf("status", "start", "status", "start"), daemon.verbs)
        assertTrue(log.any { "macOS TUN running" in it }, log.toString())
        assertTrue("\"geoip-ru.srs\":\"eA==\"" in daemon.requests.last())
    }

    @Test fun aGlobalStartAsksNothingBeforeItStarts() = withFakeDaemon(listOf(running)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { null })
        controller.connect(Routing.Global, emptyMap())
        assertEquals(listOf("start"), daemon.verbs)
        assertTrue("files" !in daemon.requests.single())
    }

    @Test fun aBypassWithNoInterfaceToBindToDoesNotStart() = withFakeDaemon(listOf(idle)) { daemon ->
        val controller = MacOsTunController({}, TunnelDaemonClient(daemon.path), { emptyList() }, { null })
        assertFailsWith<IllegalStateException> { controller.connect(bypass, ruleFiles) }
        assertEquals(emptyList(), daemon.verbs)
    }

    /** A scripted daemon on a real unix socket: one reply per request, in order. */
    private class FakeDaemon(val path: Path) {
        val verbs = mutableListOf<String>()
        val requests = mutableListOf<String>()
    }

    private fun withFakeDaemon(replies: List<String>, body: (FakeDaemon) -> Unit) {
        val dir = Files.createTempDirectory("tunneld-test")
        val daemon = FakeDaemon(dir.resolve("sock"))
        val queue = ArrayDeque(replies)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(daemon.path))
        val thread = Thread {
            runCatching {
                while (true) {
                    server.accept().use { channel ->
                        val request = readLine(channel)
                        synchronized(daemon) {
                            daemon.requests += request
                            daemon.verbs += Regex("\"verb\":\"([a-z]+)\"").find(request)?.groupValues?.get(1) ?: "?"
                        }
                        val reply = queue.removeFirstOrNull() ?: """{"ok":false,"error":"unscripted","logTail":""}"""
                        channel.write(ByteBuffer.wrap((reply + "\n").toByteArray()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            body(daemon)
        } finally {
            server.close()
            thread.interrupt()
            daemon.path.deleteIfExists()
        }
    }

    private fun readLine(channel: SocketChannel): String {
        val out = StringBuilder()
        val buffer = ByteBuffer.allocate(16 * 1024)
        while ('\n' !in out) {
            buffer.clear()
            if (channel.read(buffer) < 0) break
            buffer.flip()
            out.append(Charsets.UTF_8.decode(buffer))
        }
        return out.toString()
    }
}
