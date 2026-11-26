package org.olcbox.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore

/**
 * Probes on the Rust core: a candidate line's own core on a second handle of
 * ghostlane-core in the app's process, beside the session's.
 *
 *  - Smart connect's probe (AndroidVpnManager.probeTransport) while a session
 *    runs on the Rust core: the probe's core dials the line's server, two
 *    handles run at once, the probe ends with its handle freed, and the
 *    session's core was never restarted and still carries the tun.
 *  - A session failover from a dead room: the room's start fails, and once the
 *    outage is long enough the service probes another line of the same country
 *    on a second handle and moves the session to it.
 *  - A probe cancelled while its handle is being made: the native start ends
 *    all the same, and the handle it made is freed, not left running.
 *
 * No server of ours and no carrier: the session's core.json is a test's
 * (RustCore.testConfig, a redirect to a loopback server, as RustCoreVpnE2ETest),
 * the dead room is on the engine's `none` carrier at a loopback port nothing
 * listens on (RustCoreEngine.testCarrier), and a probe that has to pass leaves
 * through the core's own `freedom` outbound (RustCoreEngine.testProbeConfig),
 * as the session's verification does.
 */
@RunWith(AndroidJUnit4::class)
class RustProbeE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val pkg = context.packageName
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun say(line: String) {
        Log.i("RustCoreE2E", line)
    }

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The shell's GET through the tun (ShellNet, uid 2000), true when the answer held [needle]. */
    private fun shellFetch(path: String, needle: String): Boolean =
        RustE2eDevice.shellFetch(RustE2eDevice.UNREACHABLE, 80, path, needle)

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
        val tail = OlcboxVpnState.logs.value.takeLast(60).joinToString("\n")
        throw AssertionError("timed out waiting for $what; status=${OlcboxVpnState.status.value}; log tail:\n$tail")
    }

    private fun logCount(needle: String) = OlcboxVpnState.logs.value.count { it.contains(needle) }

    private fun connected() = OlcboxVpnState.status.value is VpnStatus.Connected

    private fun consent() {
        shell("appops set $pkg ACTIVATE_VPN allow")
        android.net.VpnService.prepare(context)?.let { c ->
            context.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the VPN consent to settle", 15_000) { android.net.VpnService.prepare(context) == null }
        }
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }

    private fun startIntent(user: String, pass: String) =
        Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME)
            .setAction(OlcboxVpnActions.ACTION_START_VPN)
            .putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, true)
            .putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, "tun")
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, user)
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, pass)
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

    private fun save(bundle: LocationBundleV4) = runBlocking { LocationsDataSourceImpl(context).saveLocationBundle(bundle) }

    private fun storedActiveId(): String? = runBlocking { LocationsDataSourceImpl(context).loadLocationBundle()?.activeLocationId }

    /** The hidden developer switch, as the app's own probe reads it (null: unset, the build's default). */
    private fun devSwitch(on: Boolean?) = runBlocking {
        context.vpnPrefDataStore.edit { prefs ->
            if (on == null) prefs.remove(RustCore.KEY_DEV_RUST_CORE) else prefs[RustCore.KEY_DEV_RUST_CORE] = on
        }
    }

    /** A Reality line to [host]:[port]: one the Rust core takes, so the probe is the builder's own core.json. */
    private fun realityLine(name: String, host: String, port: Int) = LocationConfig(
        name = name,
        kind = LocationKind.Vless,
        rawLink = "vless://11111111-1111-1111-1111-111111111111@$host:$port" +
            "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#p"
    )

    /** The session's core.json: `freedom`, and 192.0.2.1 (TEST-NET-1) redirected to [web]. */
    private fun sessionConfig(user: String, pass: String, web: LoopbackWeb) = { port: Int, _: SocksLogin? ->
        """{"log":{"loglevel":"warning","access":"none"},
          "inbounds":[{"tag":"in","protocol":"socks","listen":"127.0.0.1","port":$port,
            "settings":{"auth":"password","accounts":[{"user":"$user","pass":"$pass"}],"udp":false}}],
          "outbounds":[{"tag":"direct","protocol":"freedom"},
            {"tag":"loop","protocol":"freedom","settings":{"redirect":"127.0.0.1:${web.port}"}}],
          "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["192.0.2.1"],"outboundTag":"loop"}]}}"""
    }

    /**
     * A probe's core.json with its line replaced by the core's own way out: the builder's
     * inbound (the probe's loopback port and made-up login) and one `freedom` outbound.
     */
    private fun freedomProbe(builder: String): String =
        JSONObject()
            .put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            .put("inbounds", JSONObject(builder).getJSONArray("inbounds"))
            .put("outbounds", JSONArray().put(JSONObject().put("tag", "out").put("protocol", "freedom")))
            .toString()

    /** An HTTP server on loopback that answers every request with [nonce]. */
    private class LoopbackWeb(address: InetAddress) : AutoCloseable {
        val nonce = "PROBE-E2E-${System.nanoTime()}"
        private val server = ServerSocket(0, 8, address)
        val port: Int = server.localPort

        init {
            thread(isDaemon = true, name = "probe-e2e-web") {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { c ->
                            val r = c.getInputStream().bufferedReader()
                            while (r.readLine().let { it != null && it.isNotEmpty() }) Unit
                            c.getOutputStream().write(
                                "HTTP/1.0 200 OK\r\nContent-Length: ${nonce.length}\r\nConnection: close\r\n\r\n$nonce".toByteArray()
                            )
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    /** A server that takes every connection and never says a word: a line whose far end is gone silent. */
    private class BlackHole(address: InetAddress) : AutoCloseable {
        private val server = ServerSocket(0, 8, address)
        private val held = CopyOnWriteArrayList<Socket>()
        val port: Int = server.localPort
        val accepted = AtomicInteger()

        init {
            thread(isDaemon = true, name = "probe-e2e-black-hole") {
                while (!server.isClosed) {
                    val c = runCatching { server.accept() }.getOrNull() ?: break
                    held += c
                    accepted.incrementAndGet()
                }
            }
        }

        override fun close() {
            server.close()
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun aProbeBesideASessionRunsOnASecondHandleAndLeavesTheSessionUp() {
        consent()
        val web = LoopbackWeb(loopback)
        val hole = BlackHole(loopback)
        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        RustCore.testConfig = sessionConfig(user, pass, web)
        save(
            LocationBundleV4(
                activeLocationId = "line",
                locations = listOf(LocationEntry.from("line", realityLine("e2e", "192.0.2.9", 443)))
            )
        )
        // The app's own probe follows the switch the app's next start would: on here, as it is
        // for a build whose default is the Rust core.
        devSwitch(true)
        try {
            RustE2eDevice.startVpn(startIntent(user, pass))
            waitFor("the session to connect", 90_000) { connected() }
            assertTrue("the session carries the tun", shellFetch("before", web.nonce))
            val sessionStarts = logCount("Rust core ready on")
            val probesBefore = logCount(PROBE_MARK)
            val manager = AndroidVpnManager(context)

            // A probe of a line whose server takes the connection and then says nothing.
            val verdict = AtomicReference<Boolean?>()
            val probing = thread(name = "probe-e2e-probe") {
                verdict.set(runBlocking { manager.probeTransport(realityLine("probe", "127.0.0.1", hole.port)) })
            }
            waitFor("the probe's core to dial the line's server", 30_000) { hole.accepted.get() > 0 || !probing.isAlive }
            val handles = RustCoreEngine.probeHandles()
            val sessionRuns = RustCoreEngine.isCoreRunning()
            say("RESULT while probing: the line's server saw ${hole.accepted.get()} connection(s), probe handles $handles, the session's core runs $sessionRuns")
            assertTrue("the probe's core dialled the line's server", hole.accepted.get() > 0)
            assertEquals("the probe runs on a core handle of its own", 1, handles)
            assertTrue("the session's core runs beside it", sessionRuns)
            val during = shellFetch("during", web.nonce)
            say("RESULT a fetch through the tun while the probe held its handle: $during; still held after it: ${RustCoreEngine.probeHandles() == 1}")
            assertTrue("the session carries the tun while the probe runs", during)
            probing.join(60_000)
            assertFalse("the probe ended", probing.isAlive)
            say("RESULT the probe of a line that never answers: ${verdict.get()}")
            assertEquals("a line whose server never answers does not pass", false, verdict.get())
            assertEquals("the probe's handle is freed", 0, RustCoreEngine.probeHandles())
            assertTrue("the probe said it ran on the Rust core", logCount(PROBE_MARK) > probesBefore)

            // A probe that passes, beside the session: the line leaves by the core's own way out.
            RustCoreEngine.testProbeConfig = ::freedomProbe
            val passed = runBlocking { manager.probeTransport(realityLine("probe-2", "192.0.2.9", 443)) }
            say("RESULT a probe on the second handle, beside the session, through the core's own way out: $passed")
            assertEquals("the probe passes", true, passed)
            assertEquals("its handle is freed", 0, RustCoreEngine.probeHandles())

            // The session's core was never started again, and carries as before.
            assertEquals("the session's core was not restarted", sessionStarts, logCount("Rust core ready on"))
            assertTrue("the session's core runs", RustCoreEngine.isCoreRunning())
            assertTrue("still connected", connected())
            val after = shellFetch("after", web.nonce)
            assertTrue("the session carries the tun after the probes", after)
            say("RESULT the session stayed up: connected, its core not restarted, the tun carries")
        } finally {
            RustCoreEngine.testProbeConfig = null
            devSwitch(null)
            stopAndWait()
            RustCore.testConfig = null
            web.close()
            hole.close()
        }
    }

    @Test
    fun aDeadRoomFailsOverToALineProbedOnASecondHandle() {
        consent()
        val web = LoopbackWeb(loopback)
        // A loopback port nothing listens on: the room's engine is gone.
        val deadPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        RustCore.testConfig = sessionConfig(user, pass, web)
        RustCoreEngine.testCarrier = "none"
        RustCoreEngine.testProbeConfig = ::freedomProbe
        // One list, one country: its core line and its room. Never fetched (.invalid).
        val list = "https://sub.example.invalid/list"
        val line = LocationEntry.from("line", realityLine("DE Direct", "192.0.2.9", 443), subscriptionUrl = list)
        val room = LocationEntry.from(
            "room",
            LocationConfig(
                name = "DE · olcRTC",
                kind = LocationKind.Olcrtc,
                id = "tcp://127.0.0.1:$deadPort/dead",
                key = "ab".repeat(32),
                bypassProvider = LocationConfig.PROVIDER_SALUTEJAZZ,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            subscriptionUrl = list
        )
        save(LocationBundleV4(activeLocationId = "line", locations = listOf(line, room)))
        try {
            RustE2eDevice.startVpn(startIntent(user, pass))
            waitFor("the session to connect on the line", 90_000) { connected() }
            assertTrue("the line carries the tun", shellFetch("line", web.nonce))

            // The user picks the room; the app restarts the session for it, behind the tun.
            val failedBefore = logCount("Rust core start failed")
            val movedBefore = logCount(MOVED_MARK)
            val probesBefore = logCount(PROBE_MARK)
            save(LocationBundleV4(activeLocationId = "room", locations = listOf(line, room)))
            RustE2eDevice.startVpn(startIntent(user, pass))
            waitFor("the room's start to fail", 90_000) { logCount("Rust core start failed") > failedBefore }
            say("RESULT the dead room's start failed: ${OlcboxVpnState.logs.value.lastOrNull { it.contains("Rust core start failed") }}")
            waitFor("the session to move to another line of the same country", 240_000) { logCount(MOVED_MARK) > movedBefore }
            waitFor("the moved session to be connected", 30_000) { connected() }
            say("RESULT moved: ${OlcboxVpnState.logs.value.lastOrNull { it.contains(MOVED_MARK) }}")

            assertTrue("the line was probed on the Rust core", logCount(PROBE_MARK) > probesBefore)
            assertEquals("no probe handle is left", 0, RustCoreEngine.probeHandles())
            assertEquals("the store names the line the session moved to", "line", storedActiveId())
            assertTrue("the session's core runs", RustCoreEngine.isCoreRunning())
            val after = shellFetch("moved", web.nonce)
            assertTrue("the line carries the tun after the move", after)
            say("RESULT a dead room failed over to a line of its country, probed on a second handle")
        } finally {
            RustCoreEngine.testCarrier = null
            RustCoreEngine.testProbeConfig = null
            stopAndWait()
            RustCore.testConfig = null
            web.close()
        }
    }

    @Test
    fun aProbeCancelledWhileItsHandleIsMadeFreesIt() {
        // The probe's handle is held in the making (inside startProbeCore, counted already)
        // until the probe has been cancelled: the native start then finishes all the same.
        val making = CountDownLatch(1)
        val release = CountDownLatch(1)
        RustCoreEngine.testProbeConfig = { config ->
            making.countDown()
            release.await(30, TimeUnit.SECONDS)
            config
        }
        // Smart connect's probe before any session, on the Rust core by the switch.
        devSwitch(true)
        try {
            val manager = AndroidVpnManager(context)
            runBlocking {
                val probing = launch(Dispatchers.IO) { manager.probeTransport(realityLine("cancelled", "192.0.2.9", 443)) }
                val held = withContext(Dispatchers.IO) { making.await(30, TimeUnit.SECONDS) }
                assertTrue("the probe's handle is being made", held)
                assertEquals("the handle in the making is counted", 1, RustCoreEngine.probeHandles())
                probing.cancel()
                release.countDown()
                probing.join()
            }
            val left = RustCoreEngine.probeHandles()
            say("RESULT a probe cancelled while its handle was made: probe handles left $left")
            assertEquals("a probe cancelled while its handle was made leaves no handle running", 0, left)
        } finally {
            release.countDown()
            RustCoreEngine.testProbeConfig = null
            devSwitch(null)
        }
    }

    private companion object {
        /** What the Rust probe starter logs for each probe it starts. */
        const val PROBE_MARK = "Probe on the Rust core"
        const val MOVED_MARK = "Moved to another line of the same country"
    }
}
