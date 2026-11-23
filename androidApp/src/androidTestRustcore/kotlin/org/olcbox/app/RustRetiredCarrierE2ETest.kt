package org.olcbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.service.RustCore
import org.olcbox.app.vpn.service.RustOlcParams

/**
 * A room on a carrier, or over a lane, that the Rust client no longer carries (Telemost, WB
 * Stream, the SEI lane) is refused with the sentence the app shows for it, before the olcRTC
 * runtime is told anything: by the session, which then starts no engine, and by the engine itself.
 * No carrier is reached: the room names nothing, and the refusal comes first.
 */
@RunWith(AndroidJUnit4::class)
class RustRetiredCarrierE2ETest {
    private val e2e = RustE2eService(InstrumentationRegistry.getInstrumentation())

    @Before
    fun clean() {
        RustCore.testConfig = null
        RustCoreEngine.testCarrier = null
    }

    @Test
    fun aTelemostRoomIsRefusedBeforeAnEngineIsStarted() {
        e2e.consent()
        e2e.save(
            LocationConfig(
                name = "e2e-room",
                id = "e2e-retired-room",
                key = "ab".repeat(32),
                bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                transport = LocationConfig.TRANSPORT_VP8CHANNEL,
                kind = LocationKind.Olcrtc
            )
        )
        try {
            e2e.start()
            e2e.waitFor("a status that says why", 60_000) { e2e.error() != null }
            val message = e2e.error().orEmpty()
            e2e.say("RESULT a Telemost room on the Rust core: $message")
            assertTrue("the status names the carrier and what to use instead: '$message'", message.contains("Telemost rooms are no longer carried"))
            assertFalse(
                "no olcRTC engine was started: ${e2e.logSinceStart().takeLast(20)}",
                e2e.logged("Starting olcRTC (Rust core)")
            )
            assertFalse("the olcRTC runtime does not run", RustCoreEngine.isOlcRunning())
        } finally {
            e2e.stop()
        }
    }

    @Test
    fun theEngineRefusesARetiredCarrierOrLaneWithItsSentence() {
        val cases = listOf(
            Triple(LocationConfig.PROVIDER_TELEMOST, LocationConfig.TRANSPORT_VP8CHANNEL, "Telemost rooms are no longer carried"),
            Triple(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.TRANSPORT_DATACHANNEL, "WB Stream rooms are no longer carried"),
            Triple(LocationConfig.PROVIDER_SALUTEJAZZ, LocationConfig.TRANSPORT_SEICHANNEL, "The SEI lane is no longer carried"),
        )
        RustCoreEngine.load()
        for ((provider, transport, sentence) in cases) {
            val message = try {
                RustCoreEngine.startOlc(params(provider, transport), protect = { true }, log = {}, onSessionOpened = {})
                RustCoreEngine.stopOlc(RustOlcParams.STOP_TIMEOUT_MS)
                "started"
            } catch (e: Exception) {
                e.message.orEmpty()
            }
            e2e.say("RESULT the engine asked for a room on $provider over $transport: $message")
            assertTrue("$provider over $transport is refused with its sentence: '$message'", message.contains(sentence))
            assertFalse("the olcRTC runtime does not run", RustCoreEngine.isOlcRunning())
        }
    }

    private fun params(provider: String, transport: String) = RustOlcParams(
        provider = provider,
        transport = transport,
        room = "e2e-retired-room",
        failoverRooms = emptyList(),
        keyHex = "ab".repeat(32),
        deviceId = "",
        socksHost = "127.0.0.1",
        socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort },
        socksUser = e2e.user,
        socksPass = e2e.pass,
        dns = "",
        directRules = "",
        vp8Fps = 25L,
        vp8Batch = 1L,
        udp = false,
        dtlsProfile = "off"
    )
}
