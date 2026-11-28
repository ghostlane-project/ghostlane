package org.olcbox.app.vpn.desktop

import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A session's tun, or its system proxy setting, points at one local port and
// offers at most one login, for as long as the session lasts. Another location
// chosen inside the session is started behind it, so what an engine is handed
// has to be exactly that: these pin what [LineConfigs] hands it.
class LineConfigsTest {
    // A session that started in a room: the engine's port and the login from the settings.
    private val withLogin = SessionEndpoint(port = 10808, login = SocksLogin("ghost", "pw"))

    // A session that started on a core: its port, and no login.
    private val withoutLogin = SessionEndpoint(port = 10810, login = null)

    private fun link(type: String) = LinkParser.parse(
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:443" +
            "?type=$type&security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ff" +
            "&fp=chrome&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#x"
    ) ?: error("the test's own link does not parse")

    @Test
    fun xhttpIsTheOneTransportTheLineIsToldApart() {
        assertTrue(LineConfigs.xhttpOf(link("xhttp")) is OutboundSpec.Vless)
        assertNull(LineConfigs.xhttpOf(link("tcp")))
    }

    // The engine takes its port and its login from the settings object it is
    // started with. These are the settings it is handed.
    @Test
    fun anEngineThatComesLaterIsStartedAsTheEndpoint() {
        val settings = DesktopSocksProxySettings(
            port = 10900, username = "set", password = "setpw", shareOnLan = true, lanPort = 10999
        )

        // Into a room, in a session that started on a core: the core's port and
        // no login, although the settings have both.
        val entered = LineConfigs.engineSettings(settings, withoutLogin)
        assertEquals(10810, entered.port)
        assertEquals("", entered.username)
        assertEquals("", entered.password)
        // Nothing else of the settings is the endpoint's to change.
        assertEquals(settings.copy(port = 10810, username = "", password = ""), entered)

        // Between rooms, in a session that started in one: the port and login it started with.
        val moved = LineConfigs.engineSettings(settings, withLogin)
        assertEquals(10808, moved.port)
        assertEquals("ghost", moved.username)
        assertEquals("pw", moved.password)
    }

    // A process rule that names a path the system does not know the process
    // by lets nothing out and says nothing, so each binary is named both as it
    // is started and with its links resolved.
    @Test
    fun theProcessRuleNamesEachBinaryAsStartedAndAsResolved() {
        val dir = Files.createTempDirectory("line-binaries").toRealPath()
        val real = Files.createDirectory(dir.resolve("real"))
        val binary = Files.createFile(real.resolve("sing-box"))
        // Not every system lets a test make a link. Without one the two
        // spellings are the same, and the list has to say it once.
        val link = runCatching { Files.createSymbolicLink(dir.resolve("linked"), real) }.getOrNull()
        try {
            val started = (link ?: real).resolve("sing-box")
            val paths = LineConfigs.processPaths(
                running = listOf("/proc/reported/olcrtc"),
                binaries = listOf(started, started)
            )
            assertEquals("/proc/reported/olcrtc", paths.first())
            assertTrue(started.toString() in paths, paths.toString())
            assertTrue(binary.toString() in paths, paths.toString())
            assertEquals(paths.distinct(), paths)
            // One that is not on this machine is still named as it would be started.
            assertEquals(
                listOf(dir.resolve("absent").toString()),
                LineConfigs.processPaths(running = emptyList(), binaries = listOf(dir.resolve("absent")))
            )
        } finally {
            link?.let { Files.deleteIfExists(it) }
            Files.deleteIfExists(binary)
            Files.deleteIfExists(real)
            Files.deleteIfExists(dir)
        }
    }
}
