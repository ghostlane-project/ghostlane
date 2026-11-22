package org.olcbox.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.LocationKind
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.RustCore

/**
 * Phase 2 end to end: the app's own OlcboxVpnService, switched onto the Rust core
 * (intent extra), establishes the tun, runs ghostlane-core behind hev, and carries a
 * connection; then stops and lets go of the tun.
 *
 * No server of ours is needed: the test installs a core.json (RustCore.testConfig,
 * which only tests set) whose default outbound is `freedom` and whose one rule sends
 * 192.0.2.1 (TEST-NET-1, reachable nowhere) through a `freedom` with `redirect` to a
 * server this test runs on loopback. The fetch of 192.0.2.1:80 is made by the shell
 * (uid 2000), which the service's split-tunnel list sends through the tun: the answer
 * can only have come tun -> hev -> SOCKS (login) -> core -> redirect -> this server.
 *
 * No carrier room: olcRTC is covered as far as phase 1 went (runtime object, stats).
 */
@RunWith(AndroidJUnit4::class)
class RustCoreVpnE2ETest {
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

    /** rx + tx bytes of tun0, or null when there is no such interface. */
    private fun tunInterfaceBytes(): Long? =
        shell("cat /proc/net/dev").lines().firstOrNull { it.trim().startsWith("tun0:") }?.let { line ->
            val f = line.substringAfter(':').trim().split(Regex("\\s+"))
            f[0].toLong() + f[8].toLong()
        }

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

    @Test
    fun theSwitchIsOffUnlessAsked() {
        assertFalse("the build flag is off", RustCore.resolve(Intent(), context))
        assertTrue(RustCore.resolve(Intent().putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, true), context))
        assertFalse(RustCore.resolve(Intent().putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, false), context))
        assertNotNull("the engine is packaged", RustCore.backend)
    }

    @Test
    fun theOlcRuntimeLoadsAndTrims() {
        val engine = RustCoreEngine
        engine.load()
        val stats = JSONObject(engine.memoryStats())
        say("RESULT olc memoryStats keys: ${stats.keys().asSequence().toList()}")
        assertTrue(stats.length() > 0)
        assertFalse(engine.isOlcRunning())
        say("RESULT trim gave back ${engine.trim()} bytes")
        engine.setByteBudget(1_000_000L)
        engine.setByteBudget(0L)
    }

    @Test
    fun theServiceCarriesATunConnectionOnTheRustCoreAndLetsGo() {
        shell("appops set $pkg ACTIVATE_VPN allow")
        say("RESULT appops ACTIVATE_VPN: ${shell("appops get $pkg ACTIVATE_VPN").trim()}")
        // The appop only lets the consent dialog pass itself: the app still has to go
        // through prepare(), whose dialog (com.android.vpndialogs) sees the pre-consent
        // and makes this package the prepared VPN without a tap.
        android.net.VpnService.prepare(context)?.let { consent ->
            context.startActivity(consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the VPN consent to settle", 15_000) { android.net.VpnService.prepare(context) == null }
        }
        say("RESULT VPN prepared: ${android.net.VpnService.prepare(context) == null}")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        assertTrue("no tun fd before the start", tunFds().isEmpty())

        // The server the redirect leads to.
        val nonce = "E2E-${System.nanoTime()}"
        val hits = AtomicInteger()
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { c ->
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

        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        RustCore.testConfig = { port, login ->
            assertEquals("the tun's login reaches the core", user, login?.username)
            """{"log":{"loglevel":"warning","access":"none"},
              "inbounds":[{"tag":"in","protocol":"socks","listen":"127.0.0.1","port":$port,
                "settings":{"auth":"password","accounts":[{"user":"$user","pass":"$pass"}],"udp":false}}],
              "outbounds":[{"tag":"direct","protocol":"freedom"},
                {"tag":"loop","protocol":"freedom","settings":{"redirect":"127.0.0.1:${server.localPort}"}}],
              "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["192.0.2.1"],"outboundTag":"loop"}]}}"""
        }

        // The profile the service reads from the app's store: a VLESS line (never parsed here: testConfig stands in).
        val location = LocationConfig(
            name = "e2e",
            kind = LocationKind.Vless,
            rawLink = "vless://00000000-0000-4000-8000-000000000000@192.0.2.9:443?type=tcp&security=none#e2e"
        )
        runBlocking {
            LocationsDataSourceImpl(context).saveLocationBundle(
                LocationBundleV4(activeLocationId = "e2e", locations = listOf(LocationEntry.from("e2e", location)))
            )
        }

        val start = Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME)
            .setAction(OlcboxVpnActions.ACTION_START_VPN)
            .putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, true)
            .putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, "tun")
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, user)
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, pass)
            // Only the shell's traffic goes through the tun (this app is always left out of it).
            .putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, "proxy_selected")
            .putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS, arrayListOf("com.android.shell"))
        try {
            context.startForegroundService(start)
            waitFor("the tunnel to connect", 90_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
            say("RESULT status Connected; log: ${OlcboxVpnState.logs.value.filter { it.contains("Rust core") }.joinToString(" | ")}")
            assertTrue("a tun fd is held while connected: ${tunFds()}", tunFds().isNotEmpty())
            assertTrue("the service runs", serviceRunning())
            assertTrue("the Rust core runs", RustCoreEngine.isCoreRunning())
            waitFor("tun0", 10_000) { tunInterfaceBytes() != null }

            val before = tunInterfaceBytes() ?: 0L
            val hitsBefore = hits.get()
            val reply = shellFetch("192.0.2.1", 80, "GET /e2e HTTP/1.0\r\nHost: 192.0.2.1\r\n\r\n")
            say("RESULT fetch of 192.0.2.1:80 through the tun answered: ${reply.lineSequence().firstOrNull()} ... ${reply.takeLast(24)}")
            assertTrue("the answer is the test server's: '$reply'", reply.contains(nonce))
            assertEquals("the server saw one request", hitsBefore + 1, hits.get())
            val after = tunInterfaceBytes() ?: 0L
            say("RESULT tun0 bytes $before -> $after")
            assertTrue("tun0 carried the fetch", after > before)

            // What a network change does to a core line (resetRustCoreForNetwork): the core closes
            // what it holds and carries on behind the same tun; the next connection is dialled afresh.
            val closed = RustCoreEngine.resetNetwork()
            say("RESULT resetNetwork closed $closed connection(s)")
            assertTrue("resetNetwork answers a count", closed >= 0)
            assertTrue("the core still runs after the reset", RustCoreEngine.isCoreRunning())
            val again = shellFetch("192.0.2.1", 80, "GET /e2e-after-reset HTTP/1.0\r\nHost: 192.0.2.1\r\n\r\n")
            assertTrue("a fetch after the reset is answered: '$again'", again.contains(nonce))
            assertEquals("the server saw the second request", hitsBefore + 2, hits.get())
            assertTrue("still connected after the reset", OlcboxVpnState.status.value is VpnStatus.Connected)
        } finally {
            context.startService(
                Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME).setAction(OlcboxVpnActions.ACTION_STOP_VPN)
            )
        }
        waitFor("the status Disconnected", 30_000) { OlcboxVpnState.status.value is VpnStatus.Disconnected }
        waitFor("the Rust core to stop", 10_000) { !RustCoreEngine.isCoreRunning() }
        waitFor("the service to end", 20_000) { !serviceRunning() }
        waitFor("the tun fd to close", 10_000) { tunFds().isEmpty() }
        assertEquals("tun0 is gone", null, tunInterfaceBytes())
        say("RESULT stopped: status Disconnected, core stopped, service ended, tun fd closed, tun0 gone")
        RustCore.testConfig = null
        server.close()
    }

    // ---- the gaps closed after phase 2: UDP, a hostname with bypass routing, proxy mode ----

    private fun consent() {
        shell("appops set $pkg ACTIVATE_VPN allow")
        android.net.VpnService.prepare(context)?.let { c ->
            context.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the VPN consent to settle", 15_000) { android.net.VpnService.prepare(context) == null }
        }
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }

    private fun saveProfile(link: String, routing: RoutingSettings = RoutingSettings()) = runBlocking {
        val location = LocationConfig(name = "e2e", kind = LocationKind.Vless, rawLink = link)
        LocationsDataSourceImpl(context).saveLocationBundle(
            LocationBundleV4(
                activeLocationId = "e2e",
                locations = listOf(LocationEntry.from("e2e", location)),
                routing = routing
            )
        )
    }

    private fun startIntent(mode: String, user: String, pass: String) =
        Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME)
            .setAction(OlcboxVpnActions.ACTION_START_VPN)
            .putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, true)
            .putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, mode)
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

    private fun logCount(needle: String) = OlcboxVpnState.logs.value.count { it.contains(needle) }

    /** The shell sends one UDP datagram through the tun and prints what comes back. */
    private fun shellUdp(host: String, port: Int, payload: String): String {
        val (out, input) = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use {
            // toybox nc -u never ends on its own (run 4 sat 15 min here): timeout ends it.
            it.write("(echo $payload; sleep 3) | toybox timeout 8 toybox nc -u -w 5 $host $port; exit\n".toByteArray())
            it.flush()
        }
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun udpRoundTripsThroughTheTunOnTheRustCore() {
        consent()
        val nonce = "UDP${System.nanoTime()}"
        val echo = java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val datagrams = AtomicInteger()
        Thread {
            val buf = ByteArray(2048)
            while (!echo.isClosed) {
                runCatching {
                    val p = java.net.DatagramPacket(buf, buf.size)
                    echo.receive(p)
                    datagrams.incrementAndGet()
                    echo.send(java.net.DatagramPacket(p.data, p.length, p.address, p.port))
                }
            }
        }.also { it.isDaemon = true }.start()

        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        RustCore.testConfig = { port, _ ->
            """{"log":{"loglevel":"warning","access":"none"},
              "inbounds":[{"tag":"in","protocol":"socks","listen":"127.0.0.1","port":$port,
                "settings":{"auth":"password","accounts":[{"user":"$user","pass":"$pass"}],"udp":true}}],
              "outbounds":[{"tag":"direct","protocol":"freedom"},
                {"tag":"loop","protocol":"freedom","settings":{"redirect":"127.0.0.1:${echo.localPort}"}}],
              "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["192.0.2.1"],"outboundTag":"loop"}]}}"""
        }
        saveProfile("vless://00000000-0000-4000-8000-000000000000@192.0.2.9:443?type=tcp&security=none#e2e")
        try {
            context.startForegroundService(startIntent("tun", user, pass))
            waitFor("the tunnel to connect", 90_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
            val reply = shellUdp("192.0.2.1", 5353, nonce)
            say("RESULT UDP through the tun: '${reply.trim()}', datagrams at the echo: ${datagrams.get()}")
            assertTrue("the datagram came back: '$reply'", reply.contains(nonce))
            assertTrue("the echo saw it", datagrams.get() >= 1)
        } finally {
            RustCore.testConfig = null
            echo.close()
            stopAndWait()
        }
    }

    @Test
    fun aHostnameServerWithBypassRoutingStartsTheCore() {
        consent()
        // A Reality line named by a hostname (a resolver answers `localhost` on any network), under
        // Bypass Russia: the service resolves the name, writes the config with the lists inline,
        // and the core starts and opens its SOCKS port. Nothing answers on the other end: only
        // the start is asserted, not a connection.
        val link = "vless://11111111-1111-1111-1111-111111111111@localhost:44321" +
            "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#h"
        saveProfile(link, RoutingSettings(mode = RoutingMode.BypassRussia))
        val readyBefore = logCount("Rust core ready on")
        val resolvedBefore = logCount("resolved to an address")
        try {
            context.startForegroundService(startIntent("tun", "u${System.nanoTime() % 100000}", "p${System.nanoTime() % 100000}x"))
            waitFor("the core to start on a resolved, bypass-routed line", 60_000) {
                logCount("Rust core ready on") > readyBefore
            }
            assertTrue("the name was resolved", logCount("resolved to an address") > resolvedBefore)
            assertFalse("the core is running", !RustCoreEngine.isCoreRunning())
            say("RESULT hostname + Bypass Russia: the core started")
        } finally {
            stopAndWait()
        }
    }

    @Test
    fun theUsersOwnRulesUnderGlobalStartTheCore() {
        consent()
        // Global with the user's own direct rules (a name and a range) and a tunnel rule: the
        // config names the network's resolver and writes the rules inline; the core on the device
        // must take it. As above, only the start is asserted.
        val link = "vless://11111111-1111-1111-1111-111111111111@192.0.2.9:443" +
            "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#r"
        saveProfile(
            link,
            RoutingSettings(
                mode = RoutingMode.Global,
                directRules = listOf("example.com", "10.8.0.0/16"),
                tunnelRules = listOf("example.org")
            )
        )
        val readyBefore = logCount("Rust core ready on")
        try {
            context.startForegroundService(startIntent("tun", "u${System.nanoTime() % 100000}", "p${System.nanoTime() % 100000}x"))
            waitFor("the core to start with the user's rules", 60_000) { logCount("Rust core ready on") > readyBefore }
            assertTrue("the core is running", RustCoreEngine.isCoreRunning())
            say("RESULT Global + the user's direct/tunnel rules: the core started")
        } finally {
            stopAndWait()
        }
    }

    /**
     * A VLESS server with no TLS and no flow, on loopback, standing for the line's far end: it reads
     * the request header the core writes, answers 192.0.2.1:80 itself (an address nothing reaches),
     * and relays every other destination, so the app's own check of the tunnel gets its answer.
     * [served] counts what it carried to 192.0.2.1:80.
     */
    private class PlainVlessServer(private val body: String) : AutoCloseable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val served = AtomicInteger()

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    Thread { runCatching { handle(client) } }.also { it.isDaemon = true }.start()
                }
            }.also { it.isDaemon = true }.start()
        }

        private fun handle(client: Socket) = client.use {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            input.readUnsignedByte() // version
            input.readFully(ByteArray(16)) // the user's id
            input.readFully(ByteArray(input.readUnsignedByte())) // addons
            val command = input.readUnsignedByte()
            val destPort = input.readUnsignedShort()
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!!
                2 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                3 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
                else -> return@use
            }
            if (command != 1) return@use // a stream only
            output.write(byteArrayOf(0, 0)) // the answer's version and no addons
            output.flush()
            if (host == "192.0.2.1" && destPort == 80) {
                val request = client.getInputStream().bufferedReader()
                while (request.readLine().let { it != null && it.isNotEmpty() }) Unit
                served.incrementAndGet()
                output.write(
                    "HTTP/1.0 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()
                )
                output.flush()
            } else {
                Socket().use { remote ->
                    remote.connect(InetSocketAddress(host, destPort), 8_000)
                    Thread { runCatching { remote.getInputStream().copyTo(output) }; runCatching { client.close() } }
                        .also { it.isDaemon = true }.start()
                    runCatching { client.getInputStream().copyTo(remote.getOutputStream()) }
                }
            }
        }

        override fun close() = socket.close()
    }

    /** A SOCKS5 CONNECT to [host]:[port] (an IPv4 literal) through 127.0.0.1:[proxyPort], with a login when given; the open socket, or an IOException saying which step the proxy refused. */
    private fun socksConnect(proxyPort: Int, user: String?, pass: String?, host: String, port: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
            s.soTimeout = 20_000
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, if (user == null) 0 else 2)); out.flush()
            check(inp.readUnsignedByte() == 5) { "not a SOCKS5 server" }
            val method = inp.readUnsignedByte()
            if (user == null) {
                if (method != 0) throw IOException("the proxy wants a login (method $method)")
            } else {
                if (method != 2) throw IOException("the proxy did not take the login (method $method)")
                val u = user.toByteArray(); val p = (pass ?: "").toByteArray()
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
                inp.readUnsignedByte()
                if (inp.readUnsignedByte() != 0) throw IOException("the proxy refused the login")
            }
            val ip = InetAddress.getByName(host).address
            out.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf((port shr 8).toByte(), port.toByte())); out.flush()
            inp.readUnsignedByte()
            val reply = inp.readUnsignedByte()
            if (reply != 0) throw IOException("CONNECT refused, reply $reply")
            inp.readUnsignedByte()
            when (inp.readUnsignedByte()) {
                1 -> inp.readFully(ByteArray(4 + 2))
                4 -> inp.readFully(ByteArray(16 + 2))
                3 -> inp.readFully(ByteArray(inp.readUnsignedByte() + 2))
            }
            return s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    private fun socksGet(proxyPort: Int, user: String?, pass: String?): String =
        socksConnect(proxyPort, user, pass, "192.0.2.1", 80).use { s ->
            s.getOutputStream().write("GET /proxy HTTP/1.0\r\nHost: 192.0.2.1\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun proxyIntent(port: Int, user: String, pass: String) =
        startIntent("proxy", user, pass).putExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT, port)

    private fun plainLine(serverPort: Int) =
        "vless://11111111-1111-4111-8111-111111111111@127.0.0.1:$serverPort?type=tcp&security=none&encryption=none#e2e-proxy"

    @Test
    fun proxyModeCarriesAFetchOnACoreLine() {
        val nonce = "PROXY-${System.nanoTime()}"
        PlainVlessServer(nonce).use { server ->
            saveProfile(plainLine(server.port))
            val port = freePort()
            val readyBefore = logCount("Rust core ready on")
            try {
                context.startForegroundService(proxyIntent(port, "", ""))
                waitFor("proxy mode to connect on the core", 90_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
                assertTrue("the Rust core runs", RustCoreEngine.isCoreRunning())
                assertTrue("the core was started", logCount("Rust core ready on") > readyBefore)
                assertTrue("no tun is held in proxy mode: ${tunFds()}", tunFds().isEmpty())
                val reply = socksGet(port, null, null)
                say("RESULT a fetch of 192.0.2.1:80 through the proxy port answered: ${reply.lineSequence().firstOrNull()} ... ${reply.takeLast(24)}")
                assertTrue("the answer is the far end's: '$reply'", reply.contains(nonce))
                assertEquals("the far end carried one fetch", 1, server.served.get())
            } finally {
                stopAndWait()
            }
        }
    }

    /** The app always writes a login into its start intent; a core line stays open on loopback as the Go core line did, so other apps that send none still connect. */
    @Test
    fun proxyModeOnACoreLineStaysOpenWhateverLoginTheAppSets() {
        val nonce = "PROXYOPEN-${System.nanoTime()}"
        val user = "u${System.nanoTime() % 100000}"
        val pass = "p${System.nanoTime() % 100000}x"
        PlainVlessServer(nonce).use { server ->
            saveProfile(plainLine(server.port))
            val port = freePort()
            try {
                context.startForegroundService(proxyIntent(port, user, pass))
                waitFor("proxy mode to connect on the core", 90_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
                val reply = socksGet(port, null, null)
                assertTrue("a client with no login is carried: '$reply'", reply.contains(nonce))
                say("RESULT proxy mode with the app's login set: a client with none is carried, as on the Go core line")
            } finally {
                stopAndWait()
            }
        }
    }

    /** "localhost" is a listen host the settings sheet takes; it means this phone, as it does on the Go cores. */
    @Test
    fun proxyModeAcceptsLocalhostAsTheListenHost() {
        val nonce = "PROXYLOCAL-${System.nanoTime()}"
        PlainVlessServer(nonce).use { server ->
            saveProfile(plainLine(server.port))
            val port = freePort()
            try {
                context.startForegroundService(proxyIntent(port, "", "").putExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST, "localhost"))
                waitFor("proxy mode to connect on the core", 90_000) { OlcboxVpnState.status.value is VpnStatus.Connected }
                val reply = socksGet(port, null, null)
                assertTrue("the answer is the far end's: '$reply'", reply.contains(nonce))
            } finally {
                stopAndWait()
            }
        }
    }
}
