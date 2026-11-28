package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three Windows pieces against a real Windows: the adapter counters, the
 * registry values of system-proxy mode, and the firewall rule with the LAN
 * listener behind it. Run by scripts/desktop-checks/windows-sys.ps1
 * (WINDOWS_SYS_CHECK=1, an administrator), which fails a run in which one of
 * them was skipped or did not run. Anywhere else each test is skipped.
 */
class WindowsSystemRealTest {
    private val on = System.getenv("WINDOWS_SYS_CHECK") == "1" &&
        System.getProperty("os.name").startsWith("Windows")
    private val cleanup = mutableListOf<() -> Unit>()

    @AfterTest fun undo() {
        cleanup.reversed().forEach { runCatching { it() } }
    }

    private fun powershell(script: String) = windowsPowerShellBlocking(script, timeoutSeconds = 60)

    @Test fun anAdaptersCountersAreReadAndGrowWithTraffic() = runBlocking {
        Assume.assumeTrue(on)
        // The adapters Windows keeps counters for (a virtual function of a NIC may have none), and which of
        // them carries a fetch is the host's business: one of them must move.
        val names = powershell("Get-NetAdapterStatistics | ForEach-Object { \$_.Name }")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(names.isNotEmpty(), "Windows keeps counters for no adapter")
        val readers = names.associateWith { WindowsAdapterTraffic(it) }
        val first = readers.mapValues { (name, reader) -> assertNotNull(reader.read(), "no counters for '$name'") }
        val fetched = runCatching {
            URI("https://github.com/").toURL().openStream().use { it.readNBytes(64 * 1024).size }
        }.getOrDefault(0)
        var moved = false
        val deadline = System.nanoTime() + 20_000_000_000L
        do {
            Thread.sleep(1_000)
            for ((name, reader) in readers) {
                val now = assertNotNull(reader.read(), "no counters for '$name'")
                val was = first.getValue(name)
                assertTrue(now.bytesIn >= was.bytesIn && now.bytesOut >= was.bytesOut, "$name: $was then $now")
                if (now.bytesIn > was.bytesIn) moved = true
            }
        } while (fetched > 0 && !moved && System.nanoTime() < deadline)
        if (fetched > 0) assertTrue(moved, "$fetched bytes fetched and no adapter's received count moved: $first")
        assertEquals(null, WindowsAdapterTraffic("No such adapter ${System.nanoTime()}").read())
    }

    @Test fun theRegistryValuesAreSetAndPutBackAsTheyWere() = runBlocking {
        Assume.assumeTrue(on)
        val inspector = WindowsSystemProxy()
        val original = inspector.snapshot()
        cleanup += { runBlocking { WindowsSystemProxy.restoreCommands(original).forEach { runCatching { runWindowsCommand(it) } } } }

        for (scenario in listOf("none set", "user's own")) {
            if (scenario == "user's own") {
                runWindowsCommand(listOf("reg", "add", WindowsSystemProxy.KEY, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f"))
                runWindowsCommand(listOf("reg", "add", WindowsSystemProxy.KEY, "/v", "AutoConfigURL", "/t", "REG_SZ", "/d", "http://corp.example/wpad.pac", "/f"))
            } else {
                WindowsSystemProxy.restoreCommands(mapOf("AutoConfigURL" to null)).forEach { runCatching { runWindowsCommand(it) } }
            }
            val before = inspector.snapshot()
            val proxy = WindowsSystemProxy()
            proxy.enable("http://127.0.0.1:10809/proxy.pac")
            val during = inspector.snapshot()
            assertEquals(RegistryValue("REG_DWORD", "0x0"), during["ProxyEnable"], scenario)
            assertEquals(RegistryValue("REG_SZ", "http://127.0.0.1:10809/proxy.pac"), during["AutoConfigURL"], scenario)
            assertEquals(before["ProxyServer"], during["ProxyServer"], scenario)
            proxy.restore()
            assertEquals(before, inspector.snapshot(), scenario)
        }
    }

    @Test fun theRuleNamesTheEngineAndGoesAtStopAndAfterAKill() {
        Assume.assumeTrue(on)
        val engine = engineProgram()
        val host = lanAddress()
        val firewall = WindowsLanFirewall()
        cleanup += { WindowsLanFirewall().sweep() }
        WindowsLanFirewall().sweep()

        val rule = firewall.add(host, 40123, engine)
        assertTrue(firewall.exists(rule))
        assertEquals(engine.toAbsolutePath().normalize().toString().lowercase(), firewall.programOf(rule).lowercase())
        assertTrue(firewall.remove())
        assertFalse(firewall.exists(rule), "the rule is still there after stop")

        // A kill: the object that added the rule is gone, the rule is not.
        val orphan = WindowsLanFirewall().add(host, 40124, engine)
        assertTrue(WindowsLanFirewall().exists(orphan))
        WindowsLanFirewall().sweep()
        assertFalse(WindowsLanFirewall().exists(orphan), "the sweep left the rule of a killed run")
    }

    @Test fun theLanListenerAnswersOnItsPortAndAgainAfter15Seconds() {
        Assume.assumeTrue(on)
        val host = lanAddress()
        val firewall = WindowsLanFirewall()
        cleanup += { WindowsLanFirewall().sweep() }
        val server = ServerSocket()
        server.reuseAddress = false
        server.bind(InetSocketAddress(InetAddress.getByName(host), 0))
        cleanup += { server.close() }
        val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept().use { it.getOutputStream().write(byteArrayOf(5, 0)) } }
            }
        }
        cleanup += { acceptor.interrupt() }
        firewall.add(host, server.localPort, engineProgram())

        fun proven() = Socket().use { socket ->
            socket.connect(InetSocketAddress(host, server.localPort), 2_000)
            socket.soTimeout = 2_000
            val answer = ByteArray(2)
            assertEquals(2, socket.getInputStream().readNBytes(answer, 0, 2))
            assertEquals(listOf<Byte>(5, 0), answer.toList())
        }
        proven()
        Thread.sleep(15_000)
        proven()
        assertTrue(firewall.exists(firewall.rule!!), "the rule went while the listener stood")
        assertTrue(firewall.remove())
    }

    /** The engine the rule names: the Core build's program when there is one, else the JVM, which is a real program. */
    private fun engineProgram(): Path =
        System.getenv("GHOSTLANE_ENGINE")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }
            ?: Path.of(System.getProperty("java.home"), "bin", "java.exe")

    /** A private IPv4 address of this machine, else the loopback: the listener is proven either way. */
    private fun lanAddress(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it.address.size == 4 && it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"
}
