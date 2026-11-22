package org.olcbox.app.vpn.olc

import org.olcbox.app.data.model.LocationConfig
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
}
