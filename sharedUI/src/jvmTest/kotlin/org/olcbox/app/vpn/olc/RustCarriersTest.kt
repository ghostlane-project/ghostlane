package org.olcbox.app.vpn.olc

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.net.LocationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Telemost, WB Stream and the SEI transport have no path in the Rust core.
 * They are refused with a sentence before any tunnel starts, hidden in the
 * carrier picker, and the default carrier is one that is live.
 */
class RustCarriersTest {

    @Test
    fun retiredCarriersAreRefusedWithASentence() {
        for (name in listOf("telemost", "Telemost", "yandex", "yandex_telemost", "wbstream", "WB-Stream", "wildberries")) {
            val why = RustCarriers.refusal(name, LocationConfig.TRANSPORT_VP8CHANNEL)
            assertNotNull(why, name)
            assertTrue(why.endsWith("."), "a sentence: $why")
            assertFalse("—" in why || "–" in why, "no long dash: $why")
        }
    }

    @Test
    fun theSentenceNamesTheCarrier() {
        assertTrue("Telemost" in RustCarriers.refusal(LocationConfig.PROVIDER_TELEMOST, LocationConfig.TRANSPORT_VP8CHANNEL)!!)
        assertTrue("WB Stream" in RustCarriers.refusal(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.TRANSPORT_VP8CHANNEL)!!)
    }

    @Test
    fun seichannelIsRefusedWhateverTheCarrier() {
        for (name in listOf("seichannel", "sei", "SEI-Channel", "h264_sei")) {
            assertNotNull(RustCarriers.refusal(LocationConfig.PROVIDER_VKCALLS, name), name)
        }
    }

    @Test
    fun liveCarriersAreNotRefused() {
        assertNull(RustCarriers.refusal(LocationConfig.PROVIDER_SALUTEJAZZ, LocationConfig.TRANSPORT_DATACHANNEL))
        assertNull(RustCarriers.refusal("jazz", LocationConfig.TRANSPORT_DATACHANNEL))
        assertNull(RustCarriers.refusal(LocationConfig.PROVIDER_VKCALLS, LocationConfig.TRANSPORT_VP8CHANNEL))
        assertNull(RustCarriers.refusal(LocationConfig.PROVIDER_MTSLINK, LocationConfig.TRANSPORT_VP8CHANNEL))
    }

    @Test
    fun jitsiIsLeftAlone() {
        assertNull(RustCarriers.refusal(LocationConfig.PROVIDER_JITSI, LocationConfig.TRANSPORT_DATACHANNEL))
    }

    @Test
    fun aLocationIsAskedThroughTheSession() {
        val wb = LocationConfig("WB", "room", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
        assertNotNull(OlcSession.refusal(wb))
        val jazz = LocationConfig("Jazz", "room", "a".repeat(64), LocationConfig.PROVIDER_SALUTEJAZZ)
        assertNull(OlcSession.refusal(jazz))
    }

    @Test
    fun thePickerHidesTheRetiredOnes() {
        val offered = LocationConfig.supportedBypassProviders
        assertFalse(LocationConfig.PROVIDER_TELEMOST in offered, "$offered")
        assertFalse(LocationConfig.PROVIDER_WB_STREAM in offered, "$offered")
        for (provider in offered) {
            assertNull(RustCarriers.refusal(provider, LocationConfig.normalizeTransport(LocationConfig.DEFAULT_TRANSPORT, provider)), provider)
            assertFalse(
                LocationConfig.TRANSPORT_SEICHANNEL in LocationConfig.supportedTransportsForProvider(provider),
                "$provider offers SEI"
            )
        }
    }

    @Test
    fun theDefaultCarrierIsLive() {
        assertTrue(LocationConfig.DEFAULT_BYPASS_PROVIDER in LocationConfig.supportedBypassProviders)
        assertNull(RustCarriers.refusal(LocationConfig.DEFAULT_BYPASS_PROVIDER, LocationConfig.DEFAULT_TRANSPORT))
        assertNull(OlcSession.refusal(LocationConfig(name = "n", id = "room", key = "k")))
        assertEquals(LocationConfig.DEFAULT_BYPASS_PROVIDER, LocationConfig.normalizeProvider("no such carrier"))
    }

    @Test
    fun aCoreLineIsNeverRefusedForTheCarrierItWasStoredWith() {
        val link = "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#x"
        for (kind in LocationKind.values().filter { it != LocationKind.Olcrtc }) {
            val config = LocationConfig(
                name = "core",
                id = "",
                key = "",
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
                kind = kind,
                rawLink = link
            )
            assertNull(OlcSession.refusal(config), "$kind")
            assertNull(RustCarriers.refusal(config), "$kind")
        }
    }

    @Test
    fun aStoredCoreLineWithTheOldDefaultCarrierIsNotRefused() {
        val entry = LocationEntry(
            storageId = "s1",
            name = "core",
            authProvider = "wbstream",
            kind = LocationKind.Vless,
            rawLink = "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#x"
        )
        assertNull(OlcSession.refusal(entry.location))
        assertNull(OlcSession.refusal(entry.normalized().location))
        val room = LocationEntry(storageId = "s2", name = "room", authProvider = "wbstream", kind = LocationKind.Olcrtc)
        assertNotNull(OlcSession.refusal(room.location))
    }
}
