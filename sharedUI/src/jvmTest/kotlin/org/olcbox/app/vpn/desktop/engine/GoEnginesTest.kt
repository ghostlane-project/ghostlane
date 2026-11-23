package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.OlcrtcDtls
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine the desktop has always had, asked through the seam: a room is
 * launched with the file and the command line the manager used to write
 * itself.
 */
class GoEnginesTest {
    private val engine = DesktopEngines.create(
        DesktopEngineHost(
            log = {},
            windowsTunOutput = {},
            session = object : SessionHooks {
                override fun lineDied(what: String) = Unit
                override fun wayOutChanged(interfaceName: String?) = Unit
            }
        )
    )

    private val location = LocationConfig(
        id = "https://meet.example/room",
        key = "ab".repeat(32),
        bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
        transport = LocationConfig.TRANSPORT_DATACHANNEL
    ).normalized()

    @Test
    fun aRoomIsLaunchedWithTheCommandItAlwaysHad() {
        val binary = Path.of("/tmp/olcrtc")
        val rules = Path.of("/tmp/direct.txt")
        val launch = engine.room.launch(
            binary,
            RoomRequest(
                location = location,
                socksHost = "127.0.0.1",
                socksPort = 10808,
                socksUser = "user",
                socksPass = "it's",
                dnsServer = "1.1.1.1:53",
                directRulesFile = rules,
                dtlsProfile = OlcrtcDtls.OFF
            )
        )
        val asItWas = OlcRtcCommand(
            binary = binary,
            location = location,
            socksHost = "127.0.0.1",
            socksPort = 10808,
            socksUser = "user",
            socksPass = "it's",
            dnsServer = "1.1.1.1:53",
            directRulesFile = rules,
            dtlsProfile = OlcrtcDtls.OFF
        )
        val config = Path.of("/tmp/olcrtc-client-1.yaml")
        assertEquals(asItWas.yaml(), launch.config())
        assertEquals(asItWas.args(config), launch.args(config))
        assertEquals(listOf(config.toString()), launch.args(config).drop(1), "what the Mac's helper is handed")
        assertEquals("wbstream", launch.provider)
    }

    @Test
    fun theRoomsProgramIsReadAsItWas() {
        val launch = engine.room.launch(
            Path.of("/tmp/olcrtc"),
            RoomRequest(location, "127.0.0.1", 10808, "", "", "1.1.1.1:53", null, OlcrtcDtls.OFF)
        )
        assertTrue(launch.saysReady("INFO socks5 server listening on 127.0.0.1:10808"))
        assertFalse(launch.saysReady("joining room"))
        assertTrue(launch.saysFailed("Join room failed: 403"))
        assertTrue(launch.saysFailed("transport connect to peer failed"))
        assertFalse(launch.saysFailed("transport connect ok"))
        assertTrue(engine.room.rootBesideLinuxTun, "the olcRTC engine leaves the Linux tunnel as root")
    }

    /** This engine reports nothing by itself; the manager asks it. A session's start and stop are nothing to it. */
    @Test
    fun aSessionsStartAndStopAreNothingToIt() {
        engine.sessionUp(org.olcbox.app.vpn.DesktopMode.SystemProxy)
        engine.sessionDown()
        engine.sessionDown()
        assertFalse(engine.line.anyRunning())
        assertFalse(engine.windowsTun.isRunning())
    }
}
