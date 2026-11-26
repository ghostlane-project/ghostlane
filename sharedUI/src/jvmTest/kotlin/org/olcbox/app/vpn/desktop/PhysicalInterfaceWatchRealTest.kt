package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.DesktopMode
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.LineStarted
import org.olcbox.app.vpn.desktop.engine.SessionHooks
import org.olcbox.app.vpn.desktop.engine.host.HostEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The watch with the real engine behind a real tun, in network namespaces
 * that scripts/desktop-checks/watch.sh makes (K2, rust-desktop.yml's Linux
 * leg). The machine has two networks, `first` and `second`, and its default
 * route leaves by `first`. hev-socks5-tunnel, started by the script as root,
 * holds the tun; the rules send every socket into it but root's and those
 * bound to an interface, as the app's Linux tunnel does. The test runs as the
 * user: ghostlane-engine, the app's own child ([HostEngine]), is the line
 * behind the tun on the port and login hev's file names, and the test's own
 * fetches enter the tun.
 *
 * Then the first network goes away and the default route leaves by the
 * second. The line's sockets are bound to the first and carry nothing: a
 * fetch fails. The watch sees the move and the session answers it as the
 * manager does: the line is stopped and started again, bound to the second
 * network, behind the tun, which is the same device under the same hev all
 * along, and a fetch through it is carried again, from the second network.
 *
 * Anywhere but under the script the test is skipped, and the script fails a
 * run in which it was skipped or did not run.
 */
class PhysicalInterfaceWatchRealTest {
    private class Check(
        val link: String,
        val port: Int,
        val login: SocksLogin,
        val origin: String,
        val originPort: Int,
        val body: String,
        val first: String,
        val second: String,
        val firstAddress: String,
        val secondAddress: String,
        val move: List<String>,
        val lineLog: File,
        val originLog: File,
        val tun: String,
        val tunIndex: Int,
        val hevPid: Long,
        val outcomes: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                fun i(name: String) = o.getValue(name).jsonPrimitive.int
                return Check(
                    link = s("link"), port = i("port"), login = SocksLogin(s("username"), s("password")),
                    origin = s("origin"), originPort = i("origin_port"), body = s("body"),
                    first = s("first"), second = s("second"),
                    firstAddress = s("first_address"), secondAddress = s("second_address"),
                    move = o.getValue("move").jsonArray.map { it.jsonPrimitive.content },
                    lineLog = File(s("line_log")), originLog = File(s("origin_log")),
                    tun = s("tun"), tunIndex = i("tun_index"), hevPid = o.getValue("hev_pid").jsonPrimitive.long,
                    outcomes = File(s("outcomes")),
                )
            }
        }
    }

    private lateinit var check: Check
    private lateinit var sessions: Path
    private val log = mutableListOf<String>()
    private val restarts = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun onlyUnderTheCheck() {
        val file = System.getenv(CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/watch.sh", !file.isNullOrBlank())
        check = Check.read(File(file))
        sessions = Files.createTempDirectory("watch-real")
    }

    @AfterTest
    fun clean() {
        restarts.cancel()
        if (::sessions.isInitialized) sessions.toFile().deleteRecursively()
    }

    @Test
    fun aMoveOfTheDefaultRouteStartsTheLineAgainOnTheNewNetworkBehindTheStandingTun(): Unit = runBlocking {
        val request = LineRequest(
            spec = requireNotNull(LinkParser.parse(check.link)) { "the check's link does not parse" },
            kind = "Vless",
            port = check.port,
            routing = Routing.Global,
            verboseLogs = false,
            login = check.login,
            serverResolver = null,
            // As the manager asks for a line beside the Linux tun.
            autoDetectInterface = true,
            bindInterface = null,
            anotherPort = { error("a line of one process asks for no second port") },
        )
        val moves = mutableListOf<String?>()
        val restarting = AtomicBoolean(false)
        val restarted = AtomicReference<Result<LineStarted>?>(null)
        lateinit var engine: WatchedEngine
        val host = DesktopEngineHost(
            log = { line -> synchronized(log) { log += line }; println(line) },
            windowsTunOutput = {},
            session = object : SessionHooks {
                override fun lineDied(what: String) {
                    synchronized(log) { log += "the line died: $what" }
                }

                // As the manager answers it (restartBehindTunOnNewWayOut): what
                // runs of the line is stopped, and the line is started again
                // behind what the session holds, which is not touched; an
                // attempt that fails is followed by another, as the manager's
                // attempts are. A tell while one restart runs is dropped, as
                // the manager drops it: the attempt starts a dead core bound
                // anew, so it needs no second restart of its own.
                override fun wayOutChanged(interfaceName: String?) {
                    synchronized(moves) { moves += interfaceName }
                    if (!restarting.compareAndSet(false, true)) return
                    restarts.launch {
                        try {
                            engine.line.stopNow()
                            var result = runCatching { engine.line.start(request) }
                            repeat(4) {
                                if (result.isSuccess) return@repeat
                                synchronized(log) { log += "a restart attempt failed: ${result.exceptionOrNull()?.message}" }
                                delay(1_000)
                                result = runCatching { engine.line.start(request) }
                            }
                            restarted.set(result)
                        } finally {
                            restarting.set(false)
                        }
                    }
                }
            },
        )
        engine = PhysicalInterfaceWatch.around(HostEngine(host, sessionParent = sessions), host)
        try {
            val first = engine.line.start(request)
            assertTrue(waitForPort(check.port, first.alive), "the engine's port did not open\n${logTail()}")
            assertTrue(waitForLog("the sockets leave by ${check.first}"), "the line is not bound to ${check.first}\n${logTail()}")
            engine.sessionUp(DesktopMode.LinuxTun)

            val before = eventually(5) { fetch() }
            assertEquals(check.body, before, "the page through the tun")
            assertTrue(carriedFrom(check.firstAddress) > 0, "the line did not leave by ${check.first}\n${check.lineLog.readText()}")
            check.outcomes.appendText("Before the move: the page through the tun, the line leaving by ${check.first}\n")

            // The first network goes away, and the default route leaves by the second.
            val moving = ProcessBuilder(check.move).redirectErrorStream(true).start()
            assertTrue(moving.waitFor(20, TimeUnit.SECONDS), "the move did not finish")
            val said = moving.inputStream.bufferedReader().readText()
            assertEquals(0, moving.exitValue(), "the move failed: $said")
            val movedAt = System.currentTimeMillis()

            // The control: the line is bound to the network that went away and
            // carries nothing, until the watch has seen the move. Asked at once:
            // the watch acts two reads, at least two seconds, after the move.
            val control = runCatching { fetch(timeoutMs = 600) }
            val toldBeforeControl = synchronized(moves) { moves.toList() }
            assertTrue(control.isFailure, "the line carried after its network went away, before any restart")
            assertTrue(
                toldBeforeControl.isEmpty() && System.currentTimeMillis() - movedAt < PhysicalInterfaceWatch.INTERVAL_MS,
                "the control was not made before the watch could act (told $toldBeforeControl)"
            )
            check.outcomes.appendText("Right after the move: nothing passes, the line still bound to ${check.first}\n")

            assertTrue(waitForLog("the sockets leave by ${check.second}", 30_000), "the line was not started again on ${check.second}\n${logTail()}")
            val again = waitFor(20_000) { restarted.get() }
                ?: fail("the restart did not return\n${logTail()}")
            val second = again.getOrElse { fail("the restart failed: ${it.message}\n${logTail()}", it) }
            assertTrue(waitForPort(check.port, second.alive), "the restarted engine's port did not open\n${logTail()}")
            val told = synchronized(moves) { moves.toList() }
            assertTrue(
                told.isNotEmpty() && told.all { it == check.second },
                "every tell named the interface there now: $told",
            )
            check.outcomes.appendText(
                "The watch told the move to ${check.second} in ${(System.currentTimeMillis() - movedAt) / 1000} s or less; " +
                    "the line was started again bound to it\n"
            )

            val after = eventually(10) { fetch() }
            assertEquals(check.body, after, "the page through the tun after the move")
            assertTrue(carriedFrom(check.secondAddress) > 0, "the line did not leave by ${check.second}\n${check.lineLog.readText()}")

            // The tun stood through it all.
            val tunNow = NetworkInterface.getByName(check.tun)?.index
            assertEquals(check.tunIndex, tunNow, "the tun is not the device the session started with")
            val hev = File("/proc/${check.hevPid}/comm")
            assertTrue(hev.isFile && hev.readText().trim().startsWith("hev-socks5"), "hev is not the process the session started with")
            assertFalse(
                check.originLog.readLines().any { it.contains(" ${check.firstAddress} ") || it.contains(" ${check.secondAddress} ") },
                "the page was asked for from the machine's own address, around the line\n${check.originLog.readText()}"
            )
            check.outcomes.appendText(
                "After the move: the page through the same tun (index ${check.tunIndex}, the same hev), the line leaving by ${check.second}\n"
            )
        } finally {
            engine.sessionDown()
            engine.line.stopNow()
        }
        assertFalse(engine.line.anyRunning(), "the engine outlived its stop")
    }

    // ---------------------------------------------------------------- the network

    /** The page, asked for by this process at the origin's address: the rules send it into the tun. */
    private fun fetch(timeoutMs: Int = 10_000): String = Socket().use { socket ->
        socket.connect(InetSocketAddress(check.origin, check.originPort), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.getOutputStream().apply {
            write("GET /page HTTP/1.1\r\nHost: ${check.origin}\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        val answer = ByteArrayOutputStream()
        socket.getInputStream().copyTo(answer)
        val text = answer.toString(Charsets.UTF_8)
        val status = text.lineSequence().firstOrNull().orEmpty()
        if (status.split(' ').getOrNull(1) != "200") throw IOException("the page answered '${status.take(80)}'")
        text.substringAfter("\r\n\r\n")
    }

    /** How many connections the line's server took from [address]. */
    private fun carriedFrom(address: String): Int =
        check.lineLog.readLines().count { it.startsWith("carried $address ") }

    // ---------------------------------------------------------------- the rest

    private fun <T> eventually(tries: Int, body: () -> T): T {
        var last: IOException? = null
        repeat(tries) {
            try {
                return body()
            } catch (e: IOException) {
                last = e
                Thread.sleep(1_000)
            }
        }
        fail("failed $tries times, last: ${last?.message}\n${logTail()}", last)
    }

    private fun waitForLog(text: String, timeoutMs: Long = 10_000): Boolean =
        waitFor(timeoutMs) { if (synchronized(log) { log.any { text in it } }) true else null } == true

    private fun <T : Any> waitFor(timeoutMs: Long, value: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        return value()
    }

    private fun waitForPort(port: Int, alive: () -> Boolean, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!alive()) return false
            val open = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
            if (open) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun logTail(lines: Int = 60): String = synchronized(log) { log.takeLast(lines).joinToString("\n") }

    private companion object {
        /** The file watch.sh writes the check's networks, line and page into. */
        const val CHECK_ENV = "INTERFACE_WATCH_CHECK"
    }
}
