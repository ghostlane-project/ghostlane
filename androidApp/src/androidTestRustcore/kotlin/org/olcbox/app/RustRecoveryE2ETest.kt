package org.olcbox.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.net.LocationKind
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore

/**
 * The service's watchdog and its log on the Rust core (RustLiveness).
 *
 * The core runs in this process behind a handle the engine keeps, so a core
 * that stops serving (its runtime ended, or wedged) can leave the handle held.
 * The first test makes that happen on purpose: xray-ffi's own stop on the
 * engine's handle, which the engine keeps. The listener closes, nothing is
 * carried, and the watchdog must see it at its next look and bring the line
 * back behind the same tun. The second asks what the log says about the core
 * when a tunnel came up and carried nothing. The third puts a port that is full,
 * not dead, in front of the core (it accepts and closes, as the room client's
 * accept loop does at its connection cap): the watchdog must keep that line.
 *
 * No server of ours is needed, as in RustCoreVpnE2ETest: the core.json is the
 * test's (RustCore.testConfig), the fetch is the shell's through the tun, and a
 * TEST-NET address is redirected to a server this test runs on loopback.
 */
@RunWith(AndroidJUnit4::class)
class RustRecoveryE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val pkg = context.packageName

    private fun say(line: String) {
        Log.i("RustCoreE2E", line)
    }

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Shell's `nc` with a request on stdin; what the server sent back. */
    private fun shellFetch(host: String, port: Int, request: String): String {
        val (out, input) = instrumentation.uiAutomation.executeShellCommandRw("toybox nc -w 8 $host $port")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(request.toByteArray()); it.flush() }
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun tunFds(): List<String> =
        File("/proc/self/fd").listFiles().orEmpty()
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .filter { it.contains("/dev/tun") || it.contains("/dev/net/tun") }

    private fun serviceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(100).any { it.service.className == OlcboxVpnActions.SERVICE_CLASS_NAME }
    }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(200)
        }
        val tail = OlcboxVpnState.logs.value.takeLast(40).joinToString("\n")
        throw AssertionError("timed out waiting for $what; status=${OlcboxVpnState.status.value}; log tail:\n$tail")
    }

    private fun consent() {
        shell("appops set $pkg ACTIVATE_VPN allow")
        android.net.VpnService.prepare(context)?.let { c ->
            context.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the VPN consent to settle", 15_000) { android.net.VpnService.prepare(context) == null }
        }
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }

    /** The profile the service reads: a VLESS line, never parsed here (testConfig stands in). */
    private fun saveProfile() = runBlocking {
        val location = LocationConfig(
            name = "e2e",
            kind = LocationKind.Vless,
            rawLink = "vless://00000000-0000-4000-8000-000000000000@192.0.2.9:443?type=tcp&security=none#e2e"
        )
        LocationsDataSourceImpl(context).saveLocationBundle(
            LocationBundleV4(activeLocationId = "e2e", locations = listOf(LocationEntry.from("e2e", location)))
        )
    }

    private fun startIntent(user: String, pass: String) =
        Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME)
            .setAction(OlcboxVpnActions.ACTION_START_VPN)
            .putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, true)
            .putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, "tun")
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, user)
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, pass)
            // Only the shell's traffic goes through the tun (this app is always left out of it).
            .putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, "proxy_selected")
            .putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS, arrayListOf("com.android.shell"))

    private fun stopAndWait() {
        context.startService(
            Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME).setAction(OlcboxVpnActions.ACTION_STOP_VPN)
        )
        waitFor("the status Disconnected", 30_000) { OlcboxVpnState.status.value is VpnStatus.Disconnected }
        waitFor("the Rust core to stop", 10_000) { !RustCoreEngine.isCoreRunning() }
        waitFor("the service to end", 20_000) { !serviceRunning() }
        waitFor("the tun fd to close", 10_000) { tunFds().isEmpty() }
    }

    private fun logCount(needle: String) = OlcboxVpnState.logs.value.count { it.contains(needle) }

    /** The service's own interval between two looks of its watchdog (a private constant there). */
    private fun watchdogIntervalMs(): Long =
        runCatching {
            OlcboxVpnService::class.java.getDeclaredField("WATCHDOG_INTERVAL_MS").apply { isAccessible = true }.getLong(null)
        }.getOrElse { throw AssertionError("OlcboxVpnService.WATCHDOG_INTERVAL_MS is not readable: $it") }

    /** A loopback HTTP server that answers every request with [nonce]; [hits] counts them. */
    private class NonceServer(val nonce: String) : AutoCloseable {
        val hits = AtomicInteger()
        private val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        init {
            Thread {
                while (!socket.isClosed) {
                    runCatching {
                        socket.accept().use { c ->
                            val r = c.getInputStream().bufferedReader()
                            while (r.readLine().let { it != null && it.isNotEmpty() }) Unit
                            hits.incrementAndGet()
                            c.getOutputStream().write(
                                "HTTP/1.0 200 OK\r\nContent-Length: ${nonce.length}\r\nConnection: close\r\n\r\n$nonce".toByteArray()
                            )
                        }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        override fun close() = socket.close()
    }

    /**
     * A listener on the session's port in front of the core, which listens on [corePort]. While
     * [full] it closes every connection it accepts at once, with no reply, as the room client's
     * accept loop does once it holds its cap of SOCKS connections, and [dropped] counts them.
     * Otherwise it carries the bytes to the core, each direction closed on its own.
     */
    private class CapFront(val port: Int, private val corePort: Int) : AutoCloseable {
        @Volatile var full = false
        val dropped = AtomicInteger()
        private val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        }

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: continue
                    if (full) {
                        dropped.incrementAndGet()
                        runCatching { client.close() }
                    } else {
                        Thread { carry(client) }.also { it.isDaemon = true }.start()
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        /** The core may still be binding its port when the first connection comes. */
        private fun dialCore(): Socket? {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (true) {
                try {
                    return Socket(InetAddress.getByName("127.0.0.1"), corePort)
                } catch (_: IOException) {
                    if (SystemClock.elapsedRealtime() > deadline) return null
                    Thread.sleep(100)
                }
            }
        }

        private fun carry(client: Socket) {
            val core = dialCore() ?: run {
                runCatching { client.close() }
                return
            }
            val ends = AtomicInteger()
            fun pump(from: Socket, to: Socket) = Thread {
                runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
                runCatching { to.shutdownOutput() }
                if (ends.incrementAndGet() == 2) {
                    runCatching { from.close() }
                    runCatching { to.close() }
                }
            }.also { it.isDaemon = true }.start()
            pump(client, core)
            pump(core, client)
        }

        override fun close() = socket.close()
    }

    /**
     * Stops the running core the way one that died behind its handle is left: xray-ffi's own
     * xray_core_stop on the handle RustCoreEngine holds, under the engine's lock. The core's
     * listeners close; the engine still holds the handle (isCoreRunning stays true) and stops
     * and frees it at the next start or stop, as it does any core (a second stop is a no-op).
     */
    private fun stopTheCoreBehindItsHandle() {
        val engine = RustCoreEngine::class.java
        fun field(name: String) = runCatching { engine.getDeclaredField(name).apply { isAccessible = true } }
            .getOrElse { throw AssertionError("RustCoreEngine has no field '$name' this test can stop the core through: $it") }
        val lock = checkNotNull(field("lock").get(RustCoreEngine)) { "RustCoreEngine.lock is null" }
        val handle = field("handle")
        val ffi = Native.load("ghostlane_core", RustCoreEngine.XrayFfi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        synchronized(lock) {
            val core = handle.get(RustCoreEngine) as Pointer? ?: throw AssertionError("no core handle is held")
            val error = PointerByReference()
            val status = ffi.xray_core_stop(core, error)
            val why = error.value?.let { e -> ffi.xray_error_message(e).also { ffi.xray_error_free(e) } }
            assertEquals("xray_core_stop on the engine's handle: $why", 0, status)
        }
    }

    @Test
    fun aCoreStoppedBehindTheServiceIsBackWithinTheWatchdogInterval() {
        consent()
        val server = NonceServer("E2E-${System.nanoTime()}")
        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        RustCore.testConfig = { port, _ ->
            """{"log":{"loglevel":"warning","access":"none"},
              "inbounds":[{"tag":"in","protocol":"socks","listen":"127.0.0.1","port":$port,
                "settings":{"auth":"password","accounts":[{"user":"$user","pass":"$pass"}],"udp":false}}],
              "outbounds":[{"tag":"direct","protocol":"freedom"},
                {"tag":"loop","protocol":"freedom","settings":{"redirect":"127.0.0.1:${server.port}"}}],
              "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["192.0.2.1"],"outboundTag":"loop"}]}}"""
        }
        saveProfile()
        val interval = watchdogIntervalMs()
        try {
            context.startForegroundService(startIntent(user, pass))
            waitFor("the tunnel to connect", 90_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
            val first = shellFetch("192.0.2.1", 80, "GET /before HTTP/1.0\r\nHost: 192.0.2.1\r\n\r\n")
            assertTrue("the line carries before the stop: '$first'", first.contains(server.nonce))

            val readyBefore = logCount("Rust core ready on")
            val seenBefore = logCount("Watchdog: core transport stopped")
            val silentBefore = logCount("Rust core: its SOCKS port did not answer")
            stopTheCoreBehindItsHandle()
            val stoppedAt = SystemClock.elapsedRealtime()
            assertTrue("the engine still holds its core: only the core itself stopped", RustCoreEngine.isCoreRunning())
            assertTrue("still connected as far as the service knows", OlcboxVpnState.status.value is VpnStatus.Connected)

            // The watchdog looks once an interval; the stop can fall just after a look.
            waitFor("the watchdog to see the stopped core within one interval ($interval ms)", interval + 5_000) {
                logCount("Watchdog: core transport stopped") > seenBefore
            }
            val seenMs = SystemClock.elapsedRealtime() - stoppedAt
            assertTrue("the liveness read said why", logCount("Rust core: its SOCKS port did not answer") > silentBefore)
            waitFor("a new core behind the same tun", 30_000) { logCount("Rust core ready on") > readyBefore }
            waitFor("the line to be verified again", 30_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
            val backMs = SystemClock.elapsedRealtime() - stoppedAt
            assertTrue("a tun fd is still held", tunFds().isNotEmpty())

            val hitsBefore = server.hits.get()
            val again = shellFetch("192.0.2.1", 80, "GET /after HTTP/1.0\r\nHost: 192.0.2.1\r\n\r\n")
            assertTrue("the line carries after the recovery: '$again'", again.contains(server.nonce))
            assertEquals("the server saw the request after the recovery", hitsBefore + 1, server.hits.get())
            say("RESULT a core stopped behind its handle: seen by the watchdog after $seenMs ms (interval $interval ms), carrying again after $backMs ms")
        } finally {
            RustCore.testConfig = null
            server.close()
            stopAndWait()
        }
    }

    @Test
    fun aTunnelThatCarriesNothingLogsTheRustCoresOwnAccount() {
        consent()
        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        // Every connection the core is handed is dropped: the tunnel check through it fails.
        RustCore.testConfig = { port, _ ->
            """{"log":{"loglevel":"warning","access":"none"},
              "inbounds":[{"tag":"in","protocol":"socks","listen":"127.0.0.1","port":$port,
                "settings":{"auth":"password","accounts":[{"user":"$user","pass":"$pass"}],"udp":false}}],
              "outbounds":[{"tag":"drop","protocol":"blackhole"}],
              "routing":{"domainStrategy":"AsIs","rules":[]}}"""
        }
        saveProfile()
        val unverified = "is up but no traffic reached the internet through it"
        val unverifiedBefore = logCount(unverified)
        try {
            context.startForegroundService(startIntent(user, pass))
            waitFor("the tunnel check to fail", 90_000) {
                (OlcboxVpnState.status.value as? VpnStatus.Error)?.message?.contains("No traffic reached") == true
            }
            val logs = OlcboxVpnState.logs.value
            assertTrue("the service said the tunnel carried nothing", logCount(unverified) > unverifiedBefore)
            val at = logs.indexOfLast { it.contains(unverified) }
            // The account follows that line (a line of the core's own may come between).
            val account = logs.drop(at + 1).take(6).firstOrNull {
                it.startsWith("Rust core:") || it.contains("no core process is running")
            }
            say("RESULT the core's account of an unverified tunnel: ${account?.lineSequence()?.firstOrNull()}")
            assertNotNull("the core's account follows the unverified tunnel: ${logs.drop(at).take(8)}", account)
            assertFalse("the account is the Rust core's, not the Go cores': $account", account!!.contains("no core process is running"))
            assertTrue("the account names the core's state: $account", account.contains("the core is running"))
        } finally {
            RustCore.testConfig = null
            stopAndWait()
        }
    }

    @Test
    fun aPortAtItsConnectionCapIsNotTakenForAStoppedEngine() {
        consent()
        val server = NonceServer("E2E-${System.nanoTime()}")
        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        val corePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val front = AtomicReference<CapFront?>()
        // The core listens on a port of the test's; the session's port is the front's. A restart is
        // handed the session's same port, and the front stays.
        RustCore.testConfig = { port, _ ->
            if (front.get()?.port != port) {
                front.getAndSet(CapFront(port, corePort))?.close()
            }
            """{"log":{"loglevel":"warning","access":"none"},
              "inbounds":[{"tag":"in","protocol":"socks","listen":"127.0.0.1","port":$corePort,
                "settings":{"auth":"password","accounts":[{"user":"$user","pass":"$pass"}],"udp":false}}],
              "outbounds":[{"tag":"direct","protocol":"freedom"},
                {"tag":"loop","protocol":"freedom","settings":{"redirect":"127.0.0.1:${server.port}"}}],
              "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["192.0.2.1"],"outboundTag":"loop"}]}}"""
        }
        saveProfile()
        val interval = watchdogIntervalMs()
        val held = "closed it without a reply"
        try {
            context.startForegroundService(startIntent(user, pass))
            waitFor("the tunnel to connect", 90_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
            val cap = checkNotNull(front.get()) { "the session never asked for its core config" }
            val first = shellFetch("192.0.2.1", 80, "GET /before HTTP/1.0\r\nHost: 192.0.2.1\r\n\r\n")
            assertTrue("the line carries through the front before it is full: '$first'", first.contains(server.nonce))

            val readyBefore = logCount("Rust core ready on")
            val seenBefore = logCount("Watchdog: core transport stopped")
            val silentBefore = logCount("Rust core: its SOCKS port did not answer")
            val heldBefore = logCount(held)
            cap.full = true
            // Each look of the watchdog greets the port at least once: two dropped greetings are two
            // looks now, one look of two tries before the fix.
            waitFor("the watchdog's greetings to meet the full port twice", 2 * interval + 5_000) { cap.dropped.get() >= 2 }
            // What the look that met it decided is logged at once.
            Thread.sleep(1_000)
            val dropped = cap.dropped.get()
            val seen = logCount("Watchdog: core transport stopped") - seenBefore
            val silent = logCount("Rust core: its SOCKS port did not answer") - silentBefore
            val restarted = logCount("Rust core ready on") - readyBefore
            val said = logCount(held) - heldBefore
            val connected = OlcboxVpnState.status.value is VpnStatus.Connected
            val tail = OlcboxVpnState.logs.value.takeLast(20).joinToString("\n")
            cap.full = false

            assertEquals("the watchdog took a full port for a stopped engine ($dropped greeting(s) dropped); log tail:\n$tail", 0, seen)
            assertEquals("a port that accepts and closes was said not to answer; log tail:\n$tail", 0, silent)
            assertEquals("a new core was started; log tail:\n$tail", 0, restarted)
            assertTrue("still connected; log tail:\n$tail", connected)
            assertEquals("the log said once that the port closed a greeting unanswered; log tail:\n$tail", 1, said)

            val hitsBefore = server.hits.get()
            val again = shellFetch("192.0.2.1", 80, "GET /after HTTP/1.0\r\nHost: 192.0.2.1\r\n\r\n")
            assertTrue("the same line carries once the port has room again: '$again'", again.contains(server.nonce))
            assertEquals("the server saw the request", hitsBefore + 1, server.hits.get())
            say("RESULT a port at its connection cap: $dropped greeting(s) dropped, the line kept (interval $interval ms)")
        } finally {
            RustCore.testConfig = null
            try {
                stopAndWait()
            } finally {
                front.get()?.close()
                server.close()
            }
        }
    }
}
