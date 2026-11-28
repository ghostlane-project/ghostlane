package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * K9, the Windows tun, against a real Windows as an administrator: hev's
 * Wintun adapter, the split default route with the server's address left out,
 * the engine's sockets leaving by the physical interface, the DNS guard, and
 * the sweep after a run that was killed. Run by
 * scripts/desktop-checks/windows-tun.ps1 (WINDOWS_TUN_CHECK=1, HEV_OUT,
 * GHOSTLANE_ENGINE), which fails a run in which one of them was skipped or did
 * not run. Anywhere else each test is skipped.
 */
class WindowsTunRealTest {
    private val on = System.getenv("WINDOWS_TUN_CHECK") == "1" &&
        System.getProperty("os.name").startsWith("Windows")
    private val work: Path = Files.createTempDirectory("windows-tun-")
    private val journal = WindowsTunJournal(work.resolve("windows-tun.json"))
    private val said = mutableListOf<String>()
    private val controller = WindowsTunController(addLog = { synchronized(said) { said += it; println("tun: $it") } }, journal = journal)
    private val engines = mutableListOf<Process>()

    @AfterTest fun undo() {
        if (!on) return
        runCatching { runBlocking { controller.stopTunnel() } }
        engines.forEach { runCatching { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) } }
        runCatching { runBlocking { controller.sweep() } }
        runCatching { work.toFile().deleteRecursively() }
    }

    private fun hev(): Path {
        val out = System.getenv("HEV_OUT") ?: error("HEV_OUT is not set")
        return Path.of(out, "hev.exe").also { check(Files.isRegularFile(it)) { "no hev.exe in $out" } }
    }

    private fun powershell(script: String) = windowsPowerShellBlocking(script, timeoutSeconds = 90)

    private class Engine(val process: Process, val port: Int)

    /** ghostlane-engine as the app runs it: a SOCKS5 inbound on loopback and a direct way out, bound to [wayOut]. */
    private fun startEngine(wayOut: String): Engine {
        val exe = System.getenv("GHOSTLANE_ENGINE") ?: error("GHOSTLANE_ENGINE is not set")
        check(Files.isRegularFile(Path.of(exe))) { "no engine at $exe" }
        val session = work.resolve("session-${engines.size}.json")
        Files.writeString(
            session,
            """{"version":1,"core":{"log":{"loglevel":"warning"},"inbounds":[{"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":0}],"outbounds":[{"tag":"direct","protocol":"freedom"}]},"bind":{"interface":${jsonString(wayOut)}}}"""
        )
        val process = ProcessBuilder(exe, "run", "--session", session.toString())
            .redirectError(work.resolve("engine-${engines.size}.err").toFile())
            .start()
        engines += process
        val lines = LinkedBlockingQueue<String>()
        thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
        val seen = mutableListOf<String>()
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val line = lines.poll(500, TimeUnit.MILLISECONDS)
            if (line != null) {
                seen += line
                Regex(""""address"\s*:\s*"127\.0\.0\.1:(\d+)"""").find(line)?.let { return Engine(process, it.groupValues[1].toInt()) }
            }
            check(process.isAlive) { "the engine ended: ${seen.joinToString("\n")}\n${engineErr()}" }
        }
        error("the engine said no ready line in 30 s: ${seen.joinToString("\n")}\n${engineErr()}")
    }

    private fun engineErr(): String = work.toFile().listFiles { f -> f.name.endsWith(".err") }
        ?.joinToString("\n") { it.readText() }.orEmpty()

    private fun jsonString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun adapterThere(name: String = WindowsTunController.HEV_ADAPTER): Boolean =
        powershell("@(Get-NetAdapter -Name ${name.powershellString()} -ErrorAction SilentlyContinue).Count").trim() != "0"

    private fun routeCount(prefix: String): Int =
        powershell("@(Get-NetRoute -DestinationPrefix ${prefix.powershellString()} -ErrorAction SilentlyContinue).Count").trim().toInt()

    private fun guardPieces(): Pair<Int, Int> {
        val rules = powershell("@(Get-NetFirewallRule -Name '${WindowsDnsGuard.RULE_PREFIX}-*' -ErrorAction SilentlyContinue).Count").trim().toInt()
        val nrpt = powershell("@(Get-DnsClientNrptRule -ErrorAction SilentlyContinue | Where-Object { \$_.DisplayName -eq '${WindowsDnsGuard.NRPT_NAME}' }).Count").trim().toInt()
        return rules to nrpt
    }

    private fun aliveHev(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private val plan = { engine: Engine, spared: List<String> ->
        WindowsTunPlan(socksPort = engine.port, spared = spared)
    }

    @Test fun aFetchCrossesTheTunnelAndTheEnginesSocketsLeaveByTheWayOut() = runBlocking {
        Assume.assumeTrue(on)
        val way = controller.physicalRoute()
        val physicalAddress = powershell(
            "(Get-NetIPAddress -InterfaceIndex ${way.index} -AddressFamily IPv4 | Select-Object -First 1).IPAddress"
        ).trim()
        val target = InetAddress.getAllByName("github.com").filterIsInstance<Inet4Address>().first()
        val engine = startEngine(way.alias)
        val hev = controller.startTunnel(hev(), plan(engine, listOf("203.0.113.77")))
        assertTrue(hev.isAlive, "hev ended at once")
        val counters = WindowsAdapterTraffic(WindowsTunController.HEV_ADAPTER)
        val before = counters.read()
        assertTrue(before != null, "the adapter has no counters")

        Socket().use { socket ->
            socket.soTimeout = 20_000
            socket.connect(InetSocketAddress(target, 80), 20_000)
            val out = socket.getOutputStream()
            // The head, without its last blank line: the connection stays open while it is looked at.
            out.write("GET / HTTP/1.1\r\nHost: github.com\r\nConnection: close\r\n".toByteArray())
            out.flush()
            val local = powershell(
                "Get-NetTCPConnection -OwningProcess ${engine.process.pid()} -RemoteAddress ${target.hostAddress.powershellString()} -RemotePort 80 " +
                    "-ErrorAction Stop | Select-Object -First 1 -ExpandProperty LocalAddress"
            ).trim()
            assertEquals(physicalAddress, local, "the engine's socket to $target left by the way out, not by the tunnel")
            out.write("\r\n".toByteArray())
            out.flush()
            val head = socket.getInputStream().bufferedReader().readLine().orEmpty()
            assertTrue(head.startsWith("HTTP/1."), "the answer through the tunnel and the engine: '$head'")
        }
        val after = counters.read()!!
        assertTrue(after.bytesIn > before!!.bytesIn && after.bytesOut > before.bytesOut, "the adapter carried it: $before then $after")
    }

    @Test fun theDefaultRouteIsSplitOntoTheAdapterAndTheServerStaysOut() = runBlocking {
        Assume.assumeTrue(on)
        val way = controller.physicalRoute()
        val engine = startEngine(way.alias)
        controller.startTunnel(hev(), plan(engine, listOf("203.0.113.77")))
        assertTrue(adapterThere(), "the adapter is up")
        val prefixes = powershell(
            "Get-NetRoute -AddressFamily IPv4 -InterfaceAlias ${WindowsTunController.HEV_ADAPTER.powershellString()} | ForEach-Object { \$_.DestinationPrefix }"
        ).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("0.0.0.0/1" in prefixes && "128.0.0.0/1" in prefixes, "both halves are on the adapter: $prefixes")
        fun leavesBy(address: String) =
            powershell("(Find-NetRoute -RemoteIPAddress '$address' | Where-Object { \$_.InterfaceAlias } | Select-Object -First 1).InterfaceAlias").trim()
        assertEquals(WindowsTunController.HEV_ADAPTER, leavesBy("203.0.113.10"), "an address in the world takes the tunnel")
        assertEquals(WindowsTunController.HEV_ADAPTER, leavesBy("8.8.8.8"))
        assertEquals(way.alias, leavesBy("203.0.113.77"), "the spared address keeps the physical way")

        controller.stopTunnel()
        assertFalse(adapterThere(), "the adapter is gone after the stop")
        assertEquals(0, routeCount("203.0.113.77/32"), "the spared route is gone")
        assertEquals(0, routeCount("0.0.0.0/1"), "the half routes are gone")
        assertEquals(0, routeCount("128.0.0.0/1"))
        assertEquals(null, journal.read(), "nothing is owed after the stop")
        assertEquals(way.alias, controller.physicalRoute().alias, "the machine leaves as it did")
    }

    @Test fun namesAreAnsweredInTheTunnelAndNoResolverOutsideItIsAskedFor() = runBlocking {
        Assume.assumeTrue(on)
        val way = controller.physicalRoute()
        val engine = startEngine(way.alias)
        controller.startTunnel(hev(), plan(engine, emptyList()))
        val (rules, nrpt) = guardPieces()
        assertTrue(rules >= 2, "the firewall rules of the guard stand: $rules")
        assertEquals(1, nrpt, "the catch-all name rule stands")
        val states = powershell(
            "Get-NetFirewallRule -Name '${WindowsDnsGuard.RULE_PREFIX}-*' | ForEach-Object { ('{0} {1} {2}' -f \$_.Enabled, \$_.Action, \$_.Direction) } | Sort-Object -Unique"
        ).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("True Block Outbound"), states, "every rule of the guard blocks outbound")
        powershell("Clear-DnsClientCache")
        // A name no resolver knows: only the tunnel's own answers it, with an address of its own range.
        val name = "leak-${System.nanoTime().toString(36)}.guard.test"
        val answer = InetAddress.getByName(name).hostAddress
        assertTrue(answer.startsWith("100.") && answer.split('.')[1].toInt() in 64..127, "'$name' was answered by the tunnel, not outside it: $answer")

        controller.stopTunnel()
        assertEquals(0 to 0, guardPieces(), "the guard is gone after the stop")
    }

    @Test fun afterAKillTheNextStartSweepsWhatWasLeft() = runBlocking {
        Assume.assumeTrue(on)
        val way = controller.physicalRoute()
        val engine = startEngine(way.alias)

        // The tunnel's program is killed and the app never stops it: the routes and the guard stay.
        val first = controller.startTunnel(hev(), plan(engine, listOf("203.0.113.77")))
        val pid = first.pid()
        val killed = ProcessBuilder("taskkill", "/F", "/PID", pid.toString()).redirectErrorStream(true).start()
        killed.inputStream.readBytes()
        assertTrue(first.waitFor(15, TimeUnit.SECONDS), "hev is dead")
        assertEquals(1, routeCount("203.0.113.77/32"), "the spared route stays behind a kill")
        assertNotEquals(0 to 0, guardPieces(), "the guard stays behind a kill")
        val fresh = WindowsTunController(addLog = { println("tun: $it") }, journal = journal)
        val removed = fresh.sweep()
        assertTrue(removed.isNotEmpty(), "the sweep says what it removed")
        assertEquals(0, routeCount("203.0.113.77/32"))
        assertEquals(0 to 0, guardPieces())
        assertFalse(adapterThere())
        assertEquals(null, journal.read())

        // The app is killed and hev lives on: the sweep ends it.
        val second = controller.startTunnel(hev(), plan(engine, listOf("203.0.113.77")))
        val secondPid = second.pid()
        val later = WindowsTunController(addLog = { println("tun: $it") }, journal = journal)
        later.sweep()
        assertFalse(aliveHev(secondPid), "the program of the killed run is ended")
        assertFalse(adapterThere())
        assertEquals(0, routeCount("203.0.113.77/32"))
        assertEquals(0 to 0, guardPieces())

        // Nothing owed, nothing done.
        assertEquals(emptyList(), WindowsTunController(addLog = {}, journal = journal).sweep())
    }
}
