package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import org.olcbox.app.net.CustomRules
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.Routing
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.MacOsTunController
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * "Only blocked sites" in the session a Mac tunnel starts: the app's own
 * HostEngine writes the blocked-only config, puts Re:filter's list in the
 * session's geodata directory and binds the engine's sockets to the
 * interface the machine leaves by, as [DesktopVpnManager] asks for in the
 * macOS tunnel. The real engine then answers the SOCKS port hev is given:
 * a listed name and a listed address go through the line (Xray 26.4.25, whose
 * freedom turns every destination into one page), anything else straight
 * out.
 *
 * Run by scripts/desktop-checks/mac-routing.sh (rust-desktop.yml), which
 * writes what these tests read into the file [CHECK_ENV] names. Anywhere
 * else each test is skipped, and the script fails a run in which one was.
 */
class HostEngineBlockedOnlyRealTest {
    private class Check(
        val link: String,
        val listedName: String,
        val listedAddress: String,
        val unlistedAddress: String,
        val unlistedPort: Int,
        val directAddress: String,
        val directPort: Int,
        val outcomes: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                fun i(name: String) = o.getValue(name).jsonPrimitive.int
                return Check(
                    link = s("link"), listedName = s("listed_name"), listedAddress = s("listed_address"),
                    unlistedAddress = s("unlisted_address"), unlistedPort = i("unlisted_port"),
                    directAddress = s("direct_address"), directPort = i("direct_port"), outcomes = File(s("outcomes")),
                )
            }
        }
    }

    private lateinit var check: Check
    private lateinit var sessions: Path
    private val log = mutableListOf<String>()

    private val host = DesktopEngineHost(
        log = { line -> synchronized(log) { log += line }; println(line) },
        windowsTunOutput = {},
        session = object : SessionHooks {
            override fun lineDied(what: String) {}
            override fun wayOutChanged(interfaceName: String?) {}
        },
    )

    @BeforeTest
    fun onlyUnderTheCheck() {
        val file = System.getenv(CHECK_ENV) ?: System.getenv(LINUX_CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/mac-routing.sh or linux-routing.sh", !file.isNullOrBlank())
        check = Check.read(File(file))
        sessions = Files.createTempDirectory("host-engine-blocked-only")
    }

    @AfterTest
    fun clean() {
        if (::sessions.isInitialized) sessions.toFile().deleteRecursively()
    }

    @Test
    fun aListedHostGoesByTheLineAndAnyOtherStraightOut(): Unit = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        // The interface the machine leaves by, as PhysicalInterfaceWatch reads it for the session: the macOS
        // tunnel's and the Linux tunnel's own readings.
        val linux = System.getenv(LINUX_CHECK_ENV) != null
        val engine = HostEngine(
            host,
            defaultInterface = {
                if (linux) DesktopDnsResolver.linuxDefaultInterface() else MacOsTunController.defaultInterfaceName()
            },
            sessionParent = sessions,
        )
        val started = engine.line.start(
            LineRequest(
                spec = requireNotNull(LinkParser.parse(check.link)),
                kind = "Vless",
                port = port,
                routing = Routing.Rules("/unused", DirectDns.Servers(listOf("192.0.2.53")), Routing.Policy.BlockedOnly, CustomRules.of(emptyList(), emptyList())),
                verboseLogs = false,
                login = null,
                serverResolver = null,
                autoDetectInterface = true,
                bindInterface = null,
                anotherPort = { error("a line of one process asks for no second port") },
            )
        )
        try {
            assertTrue(waitForPort(port, started.alive), "the engine's port did not open\n${logTail()}")
            assertTrue(started.said.contains("bound to"), "the session did not bind the engine's sockets: ${started.said}")

            val byName = eventually { fetch(port, check.listedName, 80) }
            assertEquals("page", byName, "a name on the list goes by the line")
            val byAddress = eventually { fetch(port, check.listedAddress, 80) }
            assertEquals("page", byAddress, "an address on the list goes by the line")

            // This machine's own address is on no list: the page of the server beside it answers, and the line,
            // whose server would only redirect it, is not asked.
            val direct = eventually { fetch(port, check.directAddress, check.directPort) }
            assertEquals("direct", direct, "an address on no list goes straight out")
            // Only the line's server turns this address into a page; straight out it is not answered.
            assertFailsWith<IOException>("an address on no list went by the line") {
                fetch(port, check.unlistedAddress, check.unlistedPort, timeoutMs = 8_000)
            }
            check.outcomes.appendText(
                "Only blocked sites, the ${if (linux) "Linux" else "Mac"} tunnel's session: a listed name and a listed address by the line, " +
                    "this machine's address straight out, a TEST-NET address not by the line\n"
            )
            assertTrue(started.alive(), "the engine ended while it served\n${logTail()}")
        } finally {
            engine.line.stopNow()
        }
    }

    /** One GET through the engine's SOCKS5 port (no login) to [target]:[targetPort]; the body, or an IOException. */
    private fun fetch(port: Int, target: String, targetPort: Int, timeoutMs: Int = 30_000): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) throw IOException("the engine wants a login")
            val literal = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").matches(target)
            val where = if (literal) byteArrayOf(1) + InetAddress.getByName(target).address
            else byteArrayOf(3, target.length.toByte()) + target.toByteArray()
            out.write(byteArrayOf(5, 1, 0) + where + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
            out.flush()
            val reply = ByteArray(4)
            input.readFully(reply)
            if (reply[1].toInt() != 0) throw IOException("CONNECT answered ${reply[1]}")
            when (reply[3].toInt()) {
                1 -> input.readFully(ByteArray(6))
                4 -> input.readFully(ByteArray(18))
                3 -> input.readFully(ByteArray(input.readUnsignedByte() + 2))
                else -> throw IOException("CONNECT answered an address of type ${reply[3]}")
            }
            out.write("GET / HTTP/1.1\r\nHost: $target\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val text = String(input.readAllBytes())
            if (!text.startsWith("HTTP/1.")) throw IOException("no HTTP answer: ${text.take(40)}")
            return text.substringAfter("\r\n\r\n")
        }
    }

    private fun <T> eventually(tries: Int = 3, body: () -> T): T {
        var last: Exception? = null
        repeat(tries) {
            try {
                return body()
            } catch (e: IOException) {
                last = e
                Thread.sleep(500)
            }
        }
        throw AssertionError("still failing after $tries tries: $last\n${logTail()}", last)
    }

    private fun waitForPort(port: Int, alive: () -> Boolean, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && alive()) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun logTail(lines: Int = 40): String = synchronized(log) { log.takeLast(lines).joinToString("\n") }

    private companion object {
        const val CHECK_ENV = "MAC_ROUTING_CHECK"
        const val LINUX_CHECK_ENV = "LINUX_ROUTING_CHECK"
    }
}
