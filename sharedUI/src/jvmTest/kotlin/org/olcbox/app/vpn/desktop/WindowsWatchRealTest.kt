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
    private val controller = WindowsTunController(addLog = { println("watch: $it") }, journal = WindowsTunJournal(work.resolve("windows-tun.json")))
    private var hev: Process? = null

    @AfterTest fun undo() {
        if (!on) return
        runCatching { powershell("Remove-NetRoute -DestinationPrefix '0.0.0.0/0' -InterfaceAlias '$ALT' -Confirm:\$false -ErrorAction SilentlyContinue") }
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
        val plan = WindowsTunPlan(socksPort = 9, adapter = ALT, dnsGuard = false)
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

    private companion object {
        const val ALT = "WatchAlt"
    }
}
