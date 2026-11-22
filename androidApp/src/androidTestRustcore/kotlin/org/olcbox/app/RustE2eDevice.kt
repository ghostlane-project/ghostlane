package org.olcbox.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

/**
 * What the Rust core's end-to-end classes do on the device, written for every
 * API level the app installs on (minSdk 23), as the emulator matrix of
 * rust-core-android.yml runs them: API 23, 24, 29, 34 and 35.
 *
 *  - The VPN service is started the way the app starts it: a foreground start
 *    from Android 8 (API 26), a plain one before, where startForegroundService
 *    does not exist.
 *  - Traffic through the tun is the shell's (uid 2000, the package
 *    com.android.shell), sent by [ShellNet] run with app_process: toybox has no
 *    `nc` on Android 6 and 7, and the instrumentation's two-way shell
 *    (executeShellCommandRw) needs Android 12.
 *
 * Nothing here is a test of its own; the classes that use it are.
 */
object RustE2eDevice {
    const val TAG = "RustCoreE2E"
    const val SHELL_PACKAGE = "com.android.shell"

    /** TEST-NET-1, reachable nowhere: an answer from it can only have come through the core. */
    const val UNREACHABLE = "192.0.2.1"

    /** A profile's line that is never dialled: the test's core.json ([RedirectServer.config]) stands in for it. */
    const val PLACEHOLDER_LINK = "vless://00000000-0000-4000-8000-000000000000@192.0.2.9:443?type=tcp&security=none#e2e"

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val pkg: String get() = context.packageName

    /** The test APK's own package: installed, never running, so a list may name it without moving anyone's traffic. */
    val testPackage: String get() = instrumentation.context.packageName

    fun say(line: String) {
        Log.i(TAG, line)
    }

    /** A command as the shell user, its standard output. No shell reads it: words are split on blanks. */
    fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Whether this instrumentation's shell is root (an image whose adbd runs as root, API 23's). */
    private val shellIsRoot: Boolean by lazy { shell("id").startsWith("uid=0(") }

    /**
     * [ShellNet] as a process of the shell's (uid 2000, the package the app lists name): the
     * test APK for the class, the app's APK for the Kotlin library. Under a root shell it is
     * started by `su 2000`, root being in no app list.
     */
    fun shellNet(vararg args: String): String {
        val classPath = instrumentation.context.packageCodePath + ":" + context.packageCodePath
        val command = "app_process -Djava.class.path=$classPath /system/bin ${ShellNet::class.java.name} ${args.joinToString(" ")}"
        return shell(if (shellIsRoot) "su 2000 $command" else command)
    }

    /** A GET made by the shell's uid; true when an answer held [needle]. */
    fun shellFetch(host: String, port: Int, path: String, needle: String, tries: Int = 1, timeoutMs: Int = 8_000): Boolean {
        val out = shellNet("fetch", host, "$port", path, "$timeoutMs", "$tries", needle)
        say("RESULT shell fetch $host:$port$path: ${out.lines().filter { it.isNotBlank() }.joinToString(" | ")}")
        return out.lines().any { it.trim() == "RESULT found" }
    }

    /** One datagram from the shell's uid; what came back, or the failure. */
    fun shellUdp(host: String, port: Int, payload: String, timeoutMs: Int = 5_000): String =
        shellNet("udp", host, "$port", payload, "$timeoutMs").trim()

    fun consent() {
        shell("appops set $pkg ACTIVATE_VPN allow")
        // The appop is the consent itself (the dialog's "always allow" sets it): prepare()
        // then makes this package the prepared VPN, through the dialog only where it still asks.
        android.net.VpnService.prepare(context)?.let { c ->
            context.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the VPN consent to settle", 15_000) { android.net.VpnService.prepare(context) == null }
        }
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }

    /** Starts the VPN service as the app does: a foreground start from Android 8, a plain one before it. */
    fun startVpn(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun serviceIntent(action: String): Intent =
        Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME).setAction(action)

    /**
     * A start as the app's Connect sends it on the Rust core: [split] is the split-tunnel mode
     * (all_apps, proxy_selected, bypass_selected) with its lists.
     */
    fun startIntent(
        login: SocksLogin,
        split: String,
        proxyApps: List<String> = emptyList(),
        bypassApps: List<String> = emptyList(),
        mode: String = "tun",
    ): Intent = serviceIntent(OlcboxVpnActions.ACTION_START_VPN)
        .putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, true)
        .putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, mode)
        .putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, login.username)
        .putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, login.password)
        .putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, split)
        .putStringArrayListExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS, ArrayList(proxyApps))
        .putStringArrayListExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS, ArrayList(bypassApps))

    fun newLogin(): SocksLogin = SocksLogin("u${System.nanoTime() % 100000}", "p${System.nanoTime() % 100000}x")

    fun saveProfile(link: String, routing: RoutingSettings = RoutingSettings()) = runBlocking {
        val location = LocationConfig(name = "e2e", kind = LocationKind.Vless, rawLink = link)
        LocationsDataSourceImpl(context).saveLocationBundle(
            LocationBundleV4(
                activeLocationId = "e2e",
                locations = listOf(LocationEntry.from("e2e", location)),
                routing = routing
            )
        )
    }

    fun connected() = OlcboxVpnState.status.value is VpnStatus.Connected

    fun waitForConnected(timeoutMs: Long = 90_000) = waitFor("the tunnel to connect", timeoutMs) { connected() }

    fun stopVpnAndWait() {
        context.startService(serviceIntent(OlcboxVpnActions.ACTION_STOP_VPN))
        waitFor("the status Disconnected", 30_000) { OlcboxVpnState.status.value is VpnStatus.Disconnected }
        waitFor("the Rust core to stop", 10_000) { !RustCoreEngine.isCoreRunning() }
        waitFor("the service to end", 20_000) { !serviceRunning() }
        waitFor("the tun fd to close", 10_000) { tunFds().isEmpty() }
    }

    fun tunFds(): List<String> =
        File("/proc/self/fd").listFiles().orEmpty()
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .filter { it.contains("/dev/tun") || it.contains("/dev/net/tun") }

    /** rx + tx bytes of tun0, or null when there is no such interface. */
    fun tunInterfaceBytes(): Long? =
        shell("cat /proc/net/dev").lines().firstOrNull { it.trim().startsWith("tun0:") }?.let { line ->
            val f = line.substringAfter(':').trim().split(Regex("\\s+"))
            f[0].toLong() + f[8].toLong()
        }

    fun serviceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(100).any { it.service.className == OlcboxVpnActions.SERVICE_CLASS_NAME }
    }

    fun logCount(needle: String) = OlcboxVpnState.logs.value.count { it.contains(needle) }

    fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(200)
        }
        val tail = OlcboxVpnState.logs.value.takeLast(40).joinToString("\n")
        throw AssertionError("timed out waiting for $what; status=${OlcboxVpnState.status.value}; log tail:\n$tail")
    }

    /**
     * A loopback HTTP server in this process that answers every request with its [nonce]
     * and counts them, and the core.json that leads [UNREACHABLE] to it: the core's own
     * `freedom` with a redirect, everything else straight out (the tunnel's check reaches
     * the internet). Only the core can carry a connection to [UNREACHABLE] here.
     */
    class RedirectServer : AutoCloseable {
        val nonce = "E2E-${System.nanoTime()}"
        val hits = AtomicInteger()
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
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
        }

        /** The test's core.json (RustCore.testConfig) for the core's SOCKS [corePort] and the tun's [login]. */
        fun config(corePort: Int, login: SocksLogin): String =
            """{"log":{"loglevel":"warning","access":"none"},
              "inbounds":[{"tag":"in","protocol":"socks","listen":"127.0.0.1","port":$corePort,
                "settings":{"auth":"password","accounts":[{"user":"${login.username}","pass":"${login.password}"}],"udp":false}}],
              "outbounds":[{"tag":"direct","protocol":"freedom"},
                {"tag":"loop","protocol":"freedom","settings":{"redirect":"127.0.0.1:$port"}}],
              "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["${RustE2eDevice.UNREACHABLE}"],"outboundTag":"loop"}]}}"""

        override fun close() {
            server.close()
        }
    }
}
