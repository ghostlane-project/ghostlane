package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The interface watch on a real Windows as an administrator, run by
 * scripts/desktop-checks/windows-watch.ps1 (WINDOWS_WATCH_CHECK=1, HEV_OUT),
 * which fails a run in which one of these was skipped. Anywhere else each is
 * skipped.
 *
 * The machine is moved for real: hev makes a second Wintun adapter under a
 * name of its own (not the tunnel's, so the reader does not leave it out),
 * and a default route of the lowest metric is put on it, then taken away.
 * The route points nowhere useful, so it is held for seconds, and the
 * adapter and the route go in [undo] whatever happens.
 */
class WindowsWatchRealTest {
    private val on = System.getenv("WINDOWS_WATCH_CHECK") == "1" &&
        System.getProperty("os.name").startsWith("Windows")
    private val work: Path = Files.createTempDirectory("windows-watch-")
    private val journal = WindowsTunJournal(work.resolve("windows-tun.json"))
    private val controller = WindowsTunController(addLog = { println("watch: $it") }, journal = journal)
    private var hev: Process? = null
    private var engine: Process? = null

    @AfterTest fun undo() {
        if (!on) return
        runCatching { powershell("Remove-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceAlias '$ALT' -Confirm:\$false -ErrorAction SilentlyContinue") }
        runCatching { runBlocking { controller.stopTunnel() } }
        engine?.let { runCatching { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) } }
        hev?.let { runCatching { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) } }
        runCatching { work.toFile().deleteRecursively() }
    }

    private fun powershell(script: String) = windowsPowerShellBlocking(script, timeoutSeconds = 90)

    private fun hevExe(): Path {
        val out = System.getenv("HEV_OUT") ?: error("HEV_OUT is not set")
        return Path.of(out, "hev.exe").also { check(Files.isRegularFile(it)) { "no hev.exe in $out" } }
    }

    /** A second Wintun adapter, [ALT], with no default route yet. */
    private fun makeAlternativeAdapter() {
        val plan = WindowsTunPlan(socksPort = 9, adapter = ALT, dnsGuard = false, address = "198.19.0.1")
        val config = work.resolve("alt.yml")
        Files.writeString(config, WindowsTunController.hevConfig(plan))
        hev = ProcessBuilder(hevExe().toString(), config.toString())
            .directory(hevExe().parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(work.resolve("alt.log").toFile())
            .start()
        powershell(WindowsTunController.adapterUpScript(plan))
    }

    private fun putDefaultRouteOnAlternative() = powershell(
        "Set-NetIPInterface -InterfaceAlias '$ALT' -AddressFamily IPv4 -AutomaticMetric Disabled -InterfaceMetric 1; " +
            "New-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceAlias '$ALT' -NextHop '0.0.0.0' -RouteMetric 1 -PolicyStore ActiveStore | Out-Null"
    )

    private fun removeDefaultRouteOfAlternative() =
        powershell("Remove-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceAlias '$ALT' -Confirm:\$false")

    @Test fun theReaderNamesTheWayOutManagerReadsAndNeverTheTunnel() = runBlocking {
        Assume.assumeTrue(on)
        val way = controller.physicalRoute()
        assertEquals(way.alias, WindowsTunController.physicalAliasNow(), "the watch reads what the manager reads")
        assertTrue(!WindowsTunController.isTunAdapter(way.alias), "the way out is no tunnel adapter: ${way.alias}")
        // The resolvers of the machine's own adapters: none is the tunnel's, and the read is the real one.
        val dns = WindowsTunController.physicalDnsServersNow()
        assertTrue(dns != null, "the resolvers of the adapters could be read")
        println("watch: way out ${way.alias}, resolvers $dns")
    }

    @Test fun aDefaultRouteOfAnotherAdapterMovesTheWatchAndBackAgain() = runBlocking {
        Assume.assumeTrue(on)
        val first = controller.physicalRoute().alias
        makeAlternativeAdapter()
        val moves = LinkedBlockingQueue<String>()
        val watch = PhysicalInterfaceWatch(
            onMove = { moves.put(it) },
            read = WindowsTunController::physicalAliasNow,
            indexOf = { null },
            intervalMs = 500,
        )
        try {
            watch.bound(first, askedForWayOut = true)
            watch.start()
            assertEquals(null, moves.poll(3, TimeUnit.SECONDS), "nothing moved yet")
            putDefaultRouteOnAlternative()
            assertEquals(ALT, WindowsTunController.physicalAliasNow(), "the reader names the adapter with the lowest metric")
            val moved = moves.poll(40, TimeUnit.SECONDS)
            assertEquals(ALT, moved, "the watch told the move to $ALT")
            // What the session does: the line starts again, bound to the new alias, and the watch is told so.
            watch.bound(ALT, askedForWayOut = true)
            moves.clear()
            removeDefaultRouteOfAlternative()
            val back = moves.poll(40, TimeUnit.SECONDS)
            assertNotEquals(null, back, "the watch told the move back")
            assertEquals(first, back, "back to the first adapter")
        } finally {
            watch.stop()
        }
    }

    /** ghostlane-engine as the app runs it, bound to [wayOut]: a real line behind the tun, so the held state carries traffic. */
    private fun startEngine(wayOut: String): Int {
        val exe = System.getenv("GHOSTLANE_ENGINE") ?: error("GHOSTLANE_ENGINE is not set")
        val session = work.resolve("session.json")
        Files.writeString(
            session,
            """{"version":1,"core":{"log":{"loglevel":"warning"},"inbounds":[{"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":0}],"outbounds":[{"tag":"direct","protocol":"freedom"}]},"bind":{"interface":"$wayOut"}}"""
        )
        val process = ProcessBuilder(exe, "run", "--session", session.toString())
            .redirectError(work.resolve("engine.err").toFile())
            .start()
        engine = process
        val lines = LinkedBlockingQueue<String>()
        kotlin.concurrent.thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val line = lines.poll(500, TimeUnit.MILLISECONDS)
            if (line != null) Regex(""""address"\s*:\s*"127\.0\.0\.1:(\d+)"""").find(line)?.let { return it.groupValues[1].toInt() }
            check(process.isAlive) { "the engine ended" }
        }
        error("the engine said no ready line in 30 s")
    }

    /** `index|nexthop` for each route to [prefix], whatever interface it is on. */
    private fun routesTo(prefix: String): List<String> = powershell(
        "Get-NetRoute -DestinationPrefix '$prefix' -ErrorAction SilentlyContinue | ForEach-Object { \"\$(\$_.InterfaceIndex)|\$(\$_.NextHop)\" }; exit 0"
    ).lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test fun aSparedRouteOfTheTunFollowsTheMoveAndBackAndNothingIsLeftAtTheStop() = runBlocking {
        Assume.assumeTrue(on)
        val first = controller.physicalRoute()
        makeAlternativeAdapter()
        val port = startEngine(first.alias)
        controller.startTunnel(hevExe(), WindowsTunPlan(socksPort = port, spared = listOf(SPARED), dnsGuard = false))
        val prefix = "$SPARED/32"
        assertEquals(listOf("${first.index}|${first.gateway}"), routesTo(prefix), "laid on the first way out")
        // Moved for as long as it takes to read: the tun's own halves keep the traffic going through the engine.
        putDefaultRouteOnAlternative()
        val alt: PhysicalRoute
        try {
            alt = controller.physicalRoute()
            assertEquals(ALT, alt.alias)
            val move = controller.followWayOut()
            assertEquals(1, move?.add?.size)
            assertEquals(listOf("${alt.index}|${alt.gateway}"), routesTo(prefix), "the spared route is the new way out's, none left on the old interface")
        } finally {
            removeDefaultRouteOfAlternative()
        }
        assertEquals(1, controller.followWayOut()?.add?.size)
        assertEquals(listOf("${first.index}|${first.gateway}"), routesTo(prefix), "back on the first way out")
        controller.stopTunnel()
        assertEquals(emptyList(), routesTo(prefix), "nothing left after the stop")
        assertEquals(null, journal.read(), "the journal is cleared")
    }

    private companion object {
        const val SPARED = "203.0.113.77"
        const val ALT = "WatchAlt"
    }
}
