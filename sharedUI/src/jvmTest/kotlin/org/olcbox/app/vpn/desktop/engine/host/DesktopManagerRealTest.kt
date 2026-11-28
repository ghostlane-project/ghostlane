package org.olcbox.app.vpn.desktop.engine.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.olcbox.app.vpn.DesktopVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.desktop.LinuxTunController
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E: a session started through the app's own manager. The other desktop
 * checks run HostEngine, HostProcess or LinuxTunController by hand; this one
 * builds a [DesktopVpnManager] over a store that holds one VLESS REALITY
 * Vision location, with the default engine factory, and presses Connect and
 * Disconnect as the screen does.
 *
 * Run by scripts/desktop-checks/manager.sh (rust-desktop.yml's Linux leg),
 * which makes the namespaces, builds hev, starts the far side (DNS, an HTTP
 * origin, the exit check at 1.1.1.1, Xray 26.4.25) and writes what this test
 * reads into the file [CHECK_ENV] names. Anywhere else the test is skipped,
 * and the script fails a run in which it was skipped or did not run.
 */
class DesktopManagerRealTest {
    private class Check(
        val engine: String,
        val hev: String,
        val link: String,
        val appData: String,
        val logs: File,
        val xrayAccess: File,
        val outcomes: File,
    ) {
        companion object {
            fun read(file: File): Check {
                val o = Json.parseToJsonElement(file.readText()).jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                return Check(
                    engine = s("engine"),
                    hev = s("hev"),
                    link = s("link"),
                    appData = s("app_data"),
                    logs = File(s("logs")),
                    xrayAccess = File(s("xray_access")),
                    outcomes = File(s("outcomes")),
                )
            }
        }
    }

    @Test
    fun theManagerConnectsFetchesInBothModesAndLeavesNothing(): Unit = runBlocking {
        val file = System.getenv(CHECK_ENV)
        Assume.assumeTrue("set by scripts/desktop-checks/manager.sh", !file.isNullOrBlank())
        val check = Check.read(File(requireNotNull(file)))
        System.setProperty("olcbox.appDataDir", check.appData)
        // The exit check's certificate is this run's own: the script gives this JVM a trust store that holds it.
        assertTrue(
            System.getProperty("javax.net.ssl.trustStore").orEmpty().endsWith("trust.p12"),
            "the JVM trusts the exit check's certificate: ${System.getProperty("javax.net.ssl.trustStore")}"
        )

        val before = rpFilter()
        val left = leftovers()
        assertTrue(left.isEmpty(), "the check began over what another run left: $left")
        assertTrue(ours(check).isEmpty(), "no engine or hev stands before the session: ${ours(check)}")

        val store = object : LocationsDataSource {
            private var bundle = LocationBundleV4()
            override suspend fun loadLocationBundle(): LocationBundleV4 = bundle
            override suspend fun saveLocationBundle(bundle: LocationBundleV4) { this.bundle = bundle }
            override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
            override suspend fun loadLegacyActiveLocationId(): String? = null
        }
        val repository = LocationsRepositoryImpl(store)
        repository.saveLocation(
            "e2e-manager",
            LocationConfig(name = "e2e line", kind = LocationKind.Vless, rawLink = check.link),
        )

        // The default engine factory: nothing here names an engine.
        val manager = DesktopVpnManager(repository)
        try {
            manager.startVpn()
            val reached = waitUntil(CONNECT_MS) {
                manager.status.value is VpnStatus.Connected || manager.status.value is VpnStatus.Error
            }
            val status = manager.status.value
            assertTrue(reached && status is VpnStatus.Connected, "the manager connected: $status\n${logs(manager)}")
            assertTrue(manager.isConnected.value, "isConnected says so")

            // The engine the manager started is the program of this run's pin; the tun's hev runs as root.
            val running = ours(check)
            assertTrue(
                running.any { it.contains(check.engine) } && running.any { it.contains(check.hev) },
                "the session runs ghostlane-engine and hev: $running\n${logs(manager)}"
            )
            assertTrue(
                logs(manager).contains("connected"),
                "the manager says it connected: ${logs(manager)}"
            )

            // The proxy mode: the session's own SOCKS listener, the one the app fetches through.
            val listener = assertNotNull(manager.subscriptionFetchProxy(), "a connected session has its listener")
            val viaProxy = fetchThroughSocks(listener.host, listener.port, ORIGIN, "/mgr/proxy")
            assertTrue(page(viaProxy), "a page through the session's SOCKS listener: $viaProxy\n${logs(manager)}")
            val proxySeen = requests(check, "/mgr/proxy")
            assertTrue(
                proxySeen.isNotEmpty() && OUT4 !in proxySeen,
                "the origin saw the proxy fetch from the far side alone: $proxySeen"
            )

            // The tun mode: a program that knows of no proxy, its traffic taken by the tun.
            val viaTun = fetchPage(ORIGIN, "/mgr/tun")
            assertTrue(page(viaTun), "a page through the tun: $viaTun\n${logs(manager)}")
            val tunSeen = requests(check, "/mgr/tun")
            assertTrue(
                tunSeen.isNotEmpty() && OUT4 !in tunSeen,
                "the origin saw the tun fetch from the far side alone: $tunSeen"
            )
            assertTrue(
                waitUntil(3_000) { ORIGIN in lineCarried(check) },
                "the line carried both: ${check.xrayAccess.readText().takeLast(1500)}"
            )
            val rules = leftovers()
            assertTrue(rules.any { "lookup ${LinuxTunController.ROUTE_TABLE}" in it }, "the tunnel's rules stand while it is up: $rules")

            manager.stopVpn()
            val stopped = waitUntil(STOP_MS) { manager.status.value is VpnStatus.Disconnected }
            assertTrue(stopped, "the manager disconnected: ${manager.status.value}\n${logs(manager)}")
            assertTrue(!manager.isConnected.value, "isConnected says no")

            assertTrue(waitUntil(10_000) { ours(check).isEmpty() }, "no engine or hev is left: ${ours(check)}\n${logs(manager)}")
            val standing = leftovers()
            assertTrue(standing.isEmpty(), "the stop left $standing\n${logs(manager)}")
            val after = rpFilter()
            val changed = before.filter { (name, value) -> after[name] != null && after[name] != value }
            assertTrue(changed.isEmpty(), "rp_filter is not back: ${changed.map { (name, value) -> "$name $value -> ${after[name]}" }}")
            val back = fetchPage(ORIGIN, "/mgr/after")
            assertTrue(page(back), "the machine's own way is back: $back")
            assertEquals(listOf(OUT4), requests(check, "/mgr/after").distinct(), "and it leaves by the way out")
            check.outcomes.appendText(
                "the manager connected a VLESS line (default engine, ghostlane-engine and hev running); a page by its SOCKS " +
                    "listener and one through the tun, both from the far side's address; its stop left no rule, route, " +
                    "device, process or rp_filter change\n"
            )
        } finally {
            runCatching { manager.close() }
            println(logs(manager))
        }
    }

    // ---- what the namespace says ------------------------------------------------

    private fun logs(manager: DesktopVpnManager): String = manager.logs.value.takeLast(60).joinToString("\n")

    private fun cmd(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    private fun routes(family: String): List<String> =
        cmd("ip", family, "route", "show", "table", LinuxTunController.ROUTE_TABLE).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Error") && !it.startsWith("Dump terminated") }

    /** What of a tunnel is in the namespace: rules, routes of its table, its devices. Empty when nothing is. */
    private fun leftovers(): List<String> = buildList {
        for (family in listOf("-4", "-6")) {
            cmd("ip", family, "rule", "show").lines()
                .filter { "lookup ${LinuxTunController.ROUTE_TABLE}" in it || "uidrange 0-0" in it }
                .forEach { add("ip $family rule: ${it.trim()}") }
            routes(family).forEach { add("ip $family route in ${LinuxTunController.ROUTE_TABLE}: $it") }
        }
        for (name in listOf(LinuxTunController.TUN_NAME, LinuxTunController.KILL_SWITCH_DEVICE)) {
            if (File("/sys/class/net/$name").exists()) add("device $name")
        }
    }

    /** Every interface's rp_filter but the tunnel's own devices'. */
    private fun rpFilter(): Map<String, String> =
        File("/proc/sys/net/ipv4/conf").listFiles().orEmpty()
            .filter { it.name != LinuxTunController.TUN_NAME && it.name != LinuxTunController.KILL_SWITCH_DEVICE }
            .associate { it.name to File(it, "rp_filter").readText().trim() }

    /** The command lines of the processes of the engine's program and of hev (under the sudo that runs it). */
    private fun ours(check: Check): List<String> =
        File("/proc").listFiles().orEmpty()
            .filter { it.name.all(Char::isDigit) }
            .mapNotNull { runCatching { File(it, "cmdline").readBytes().decodeToString().split('\u0000') }.getOrNull() }
            // One argument that IS the program: the test's own `sudo env HEV_..._BINARY=<path> gradle`
            // names the paths only inside `KEY=value` arguments and must not count.
            .filter { args -> args.any { it == check.engine || it == check.hev } }
            .map { it.joinToString(" ") }

    private fun world(service: String, check: Check): List<List<String>> =
        File(check.logs, "world-$service.log").takeIf { it.exists() }?.readLines().orEmpty()
            .filter { it.isNotBlank() && it != "ready" }
            .map { it.split(' ') }

    private fun requests(check: Check, path: String): List<String> =
        world("origin", check).filter { it.size >= 3 && it[0] == "request" && it[2] == path }.map { it[1] }

    /** Where the line's server was asked to go by connections that came to it from [OUT4]. */
    private fun lineCarried(check: Check): List<String> {
        val accepted = Regex("from (?:tcp:|udp:)?${Regex.escape(OUT4)}:\\d+ accepted (?:tcp:|udp:)?(\\S+):\\d+")
        return check.xrayAccess.readLines().mapNotNull { accepted.find(it)?.groupValues?.get(1) }
    }

    // ---- what the user's programs do --------------------------------------------

    private fun request(socket: Socket, address: String, path: String): String {
        val request = "GET $path HTTP/1.1\r\nHost: $address\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    private fun fetch(address: String, path: String, timeoutMs: Int = 8_000): String = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 80), timeoutMs)
            socket.soTimeout = timeoutMs
            request(socket, address, path)
        }
    }.getOrElse { "${it::class.simpleName}: ${it.message}" }

    private fun fetchThroughSocks(host: String, port: Int, address: String, path: String): String = runCatching {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))).use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(address, 80), 15_000)
            request(socket, address, path)
        }
    }.getOrElse { "${it::class.simpleName}: ${it.message}" }

    private suspend fun fetchPage(address: String, path: String, withinMs: Long = 15_000): String {
        val deadline = System.currentTimeMillis() + withinMs
        while (true) {
            val got = fetch(address, path)
            if (page(got) || System.currentTimeMillis() > deadline) return got
            delay(500)
        }
    }

    private fun page(response: String): Boolean = response.startsWith("HTTP/1.1 200") && response.endsWith(BODY)

    private suspend fun waitUntil(withinMs: Long, what: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        while (System.currentTimeMillis() < deadline) {
            if (what()) return true
            delay(100)
        }
        return what()
    }

    private companion object {
        const val CHECK_ENV = "MANAGER_CHECK"

        /** The client namespace's address (manager.sh makes it) and the far side's origin. */
        const val OUT4 = "10.231.0.2"
        const val ORIGIN = "10.232.0.30"
        const val BODY = "the world says hello\n"

        /** A connect verifies the exit through the line; a stop takes the tunnel down and waits for the processes. */
        const val CONNECT_MS = 120_000L
        const val STOP_MS = 60_000L
    }
}
