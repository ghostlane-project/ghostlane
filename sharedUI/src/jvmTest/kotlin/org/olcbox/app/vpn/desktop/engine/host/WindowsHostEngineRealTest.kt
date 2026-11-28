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
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.desktop.WindowsAdapterTraffic
import org.olcbox.app.vpn.desktop.WindowsTunController
import org.olcbox.app.vpn.desktop.WindowsTunJournal
import org.olcbox.app.vpn.desktop.WindowsTunPlan
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import org.olcbox.app.vpn.desktop.windowsPowerShellBlocking
import org.olcbox.app.vpn.desktop.powershellString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Windows desktop on ghostlane-engine, as the manager runs it and not as
 * the engine alone: the app's own [HostEngine] (the default engine of every
 * system) starts a VLESS REALITY line to the official Xray on loopback, bound
 * to the interface the machine leaves by (the adapter's alias, read before the
 * tun as the manager reads it), and hev with its Wintun adapter
 * ([WindowsTunController], the Windows tun) hands the machine's traffic to the
 * engine's SOCKS port with the login the manager gives it. A page is then
 * fetched by the plain stack of this JVM, with no proxy set, from an address
 * that only the server's redirect turns into a page (TEST-NET-3): it can only
 * have crossed the adapter, hev, the engine and the line.
 *
 * The second test gives the session the rules the Windows tunnel now keeps in
 * the core ([org.olcbox.app.vpn.desktop.engine.desktopRulesHome]): one address
 * the user sends straight out. The engine's socket for it is bound to the
 * physical interface and the line's server would have turned it into the page,
 * so a page that does not come back means it did not go by the line, and the
 * address that is not listed still does.
 *
 * Run by scripts/desktop-checks/windows-engine.sh (rust-desktop.yml's Windows
 * leg), as an administrator, with HEV_OUT holding hev.exe and wintun.dll.
 * Anywhere else each test is skipped, and the script fails a run in which one
 * was.
 */
class WindowsHostEngineRealTest {
    private class Check(
        val link: String,
        val target: String,
        val unlisted: String,
        val downBytes: Int,
        val downSha256: String,
        val outcomes: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                return Check(
                    link = s("link"), target = s("target"), unlisted = s("unlisted"),
                    downBytes = o.getValue("down_bytes").jsonPrimitive.int, downSha256 = s("down_sha256"),
                    outcomes = File(s("outcomes")),
                )
            }
        }
    }

    private lateinit var check: Check
    private lateinit var work: Path
    private lateinit var controller: WindowsTunController
    private lateinit var engine: HostEngine
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
        val file = System.getenv(CHECK_ENV)
        Assume.assumeTrue(
            "set by scripts/desktop-checks/windows-engine.sh",
            !file.isNullOrBlank() && System.getProperty("os.name").startsWith("Windows"),
        )
        check = Check.read(File(file))
        work = Files.createTempDirectory("windows-host-engine")
        controller = WindowsTunController(
            addLog = { line -> synchronized(log) { log += line }; println("tun: $line") },
            journal = WindowsTunJournal(work.resolve("windows-tun.json")),
        )
        engine = HostEngine(host, sessionParent = work)
    }

    @AfterTest
    fun undo() {
        if (!::work.isInitialized) return
        runCatching { engine.line.stopNow() }
        runCatching { runBlocking { controller.stopTunnel() } }
        runCatching { runBlocking { controller.sweep() } }
        runCatching { work.toFile().deleteRecursively() }
    }

    private fun hev(): Path {
        val out = System.getenv("HEV_OUT") ?: error("HEV_OUT is not set")
        return Path.of(out, "hev.exe").also { require(Files.isRegularFile(it)) { "no hev.exe in $out" } }
    }

    private val login = SocksLogin("tun-user", "tun-pass")

    private fun request(port: Int, routing: Routing, wayOut: String) = LineRequest(
        spec = requireNotNull(LinkParser.parse(check.link)) { "the check's link does not parse" },
        kind = "Vless",
        port = port,
        routing = routing,
        verboseLogs = false,
        login = login,
        serverResolver = null,
        // As the manager asks on Windows: no search for a way out, the one it read before the tun.
        autoDetectInterface = false,
        bindInterface = wayOut,
        anotherPort = { error("a line of one process asks for no second port") },
    )

    /** The session as the manager starts it: sweep, read the way out, the line bound to it, then hev in front of it. */
    private fun connect(routing: Routing): String = runBlocking {
        controller.sweep()
        val way = controller.physicalRoute()
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val started = engine.line.start(request(port, routing, way.alias))
        assertTrue(waitForPort(port, started.alive), "the engine's port did not open\n${logTail()}")
        assertTrue(
            started.said.contains("bound to ${way.alias}"),
            "the line's sockets are not bound to the way out `${way.alias}`: ${started.said}",
        )
        val plan = WindowsTunPlan(socksPort = port, username = login.username, password = login.password)
        val process = controller.startTunnel(hev(), plan)
        assertTrue(process.isAlive, "hev ended at once")
        assertTrue(started.alive(), "the engine ended while the tunnel came up\n${logTail()}")
        way.alias
    }

    @Test
    fun aLineOfTheAppsEngineIsCarriedByTheWindowsTunnelAndEverythingIsGoneAfterTheStop(): Unit = runBlocking {
        val way = connect(Routing.Global)
        val counters = WindowsAdapterTraffic(WindowsTunController.HEV_ADAPTER)
        val before = counters.read()
        assertTrue(before != null, "the adapter has no counters")

        val page = eventually { get(check.target, "/") }
        assertEquals("page", page, "the page behind the line, asked of an address only the line reaches")
        val down = eventually { getBytes(check.target, "/down") }
        assertEquals(check.downBytes, down.size, "the download's size")
        assertEquals(check.downSha256, sha256(down), "the download, byte for byte")
        val after = counters.read()!!
        assertTrue(
            after.bytesIn > before!!.bytesIn && after.bytesOut > before.bytesOut,
            "the adapter carried it: $before then $after",
        )

        engine.line.stopNow()
        controller.stopTunnel()
        assertEquals("0", powershell("@(Get-NetAdapter -Name ${WindowsTunController.HEV_ADAPTER.powershellString()} -ErrorAction SilentlyContinue).Count").trim(), "the adapter is gone")
        assertEquals(null, controller.journal.read(), "nothing is owed after the stop")
        assertEquals(way, controller.physicalRoute().alias, "the machine leaves as it did")
        check.outcomes.appendText(
            "Windows tunnel: HostEngine's line bound to `$way` carried a page and ${check.downBytes} bytes byte for byte " +
                "through hev and Wintun, the adapter's counters moved, and the stop left no adapter and no record\n"
        )
    }

    @Test
    fun anAddressTheUserSendsDirectLeavesTheCoreByTheBoundSocketAndTheRestStillRidesTheLine(): Unit = runBlocking {
        val rules = Routing.Rules(
            "/unused", DirectDns.Servers(listOf("192.0.2.53")), Routing.Policy.Tunnel,
            CustomRules.of(direct = listOf(check.unlisted), tunnel = emptyList()),
        )
        connect(rules)
        assertEquals("page", eventually { get(check.target, "/") }, "an address on no list still rides the line")
        // The line's server would turn this address into the page; straight out, from the physical interface,
        // it is TEST-NET-3 and answers nothing.
        val direct = runCatching { get(check.unlisted, "/", timeoutMs = 8_000) }
        assertNotEquals("page", direct.getOrNull(), "an address sent direct went by the line")
        check.outcomes.appendText(
            "Windows tunnel with rules in the core: an address on no list rode the line, the one sent direct did not\n"
        )
        assertTrue(engine.line.anyRunning(), "the engine ended while it served\n${logTail()}")
    }

    private fun powershell(script: String) = windowsPowerShellBlocking(script, timeoutSeconds = 90)

    /** The body of a plain HTTP/1.0 GET to [address]:80 by this machine's own stack, or an IOException. */
    private fun get(address: String, path: String, timeoutMs: Int = 30_000): String =
        String(getBytes(address, path, timeoutMs), Charsets.ISO_8859_1)

    private fun getBytes(address: String, path: String, timeoutMs: Int = 30_000): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 80), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.0\r\nHost: $address\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val all = ByteArrayOutputStream()
            socket.getInputStream().copyTo(all)
            val bytes = all.toByteArray()
            val head = bytes.indexOfSequence("\r\n\r\n".toByteArray())
            if (head < 0 || !String(bytes, 0, minOf(8, bytes.size)).startsWith("HTTP/1.")) {
                throw IOException("no HTTP answer from $address: ${String(bytes, 0, minOf(40, bytes.size))}")
            }
            return bytes.copyOfRange(head + 4, bytes.size)
        }
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> eventually(tries: Int = 4, body: () -> T): T {
        var last: Exception? = null
        repeat(tries) {
            try {
                return body()
            } catch (e: IOException) {
                last = e
                Thread.sleep(750)
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
        const val CHECK_ENV = "WINDOWS_ENGINE_CHECK"
    }
}
