package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `jazz` is the name upstream olcrtc gave Sber's SaluteJazz before it renamed the
 * carrier `salutejazz` and then dropped it (May 2026); olcbox kept "Jazz" in its
 * picker all along. Our engine answers to `salutejazz` alone, so the old name must
 * land a location on that carrier, not on a picker entry with no engine behind it.
 */
class LocationConfigProviderTest {

    @Test
    fun jazzIsSaluteJazzUnderItsOldName() {
        for (name in listOf("jazz", "JAZZ", " sberjazz ", "sber_jazz", "salutejazz", "SaluteJazz")) {
            assertEquals(LocationConfig.PROVIDER_SALUTEJAZZ, LocationConfig.normalizeProvider(name), name)
        }
        assertEquals("SaluteJazz", LocationConfig.providerDisplayName("jazz"))
        assertEquals(
            listOf(LocationConfig.TRANSPORT_DATACHANNEL),
            LocationConfig.supportedTransportsForProvider("jazz")
        )
    }

    @Test
    fun thePickerOffersSaluteJazzOnceAndNoJazz() {
        val offered = LocationConfig.supportedBypassProviders
        assertFalse("jazz" in offered, "the picker still offers the old name: $offered")
        assertEquals(1, offered.count { LocationConfig.normalizeProvider(it) == LocationConfig.PROVIDER_SALUTEJAZZ })
        assertEquals(offered, offered.map { LocationConfig.normalizeProvider(it) }, "every entry is its own canonical name")
    }

    /**
     * Before VK Calls had a name here, `vkcalls` fell to the default carrier: a
     * VK link imported as a WB Stream location, and the engine was sent into
     * WB with a vk.ru join link for a room id.
     */
    @Test
    fun vkCallsIsItsOwnCarrierOverVp8Only() {
        for (name in listOf("vkcalls", "VKCalls", " vk ", "vk_calls", "vk-calls", "vkcall")) {
            assertEquals(LocationConfig.PROVIDER_VKCALLS, LocationConfig.normalizeProvider(name), name)
        }
        assertEquals("VK Calls", LocationConfig.providerDisplayName("vk"))
        assertEquals(
            listOf(LocationConfig.TRANSPORT_VP8CHANNEL),
            LocationConfig.supportedTransportsForProvider(LocationConfig.PROVIDER_VKCALLS)
        )
        assertEquals(
            LocationConfig.TRANSPORT_VP8CHANNEL,
            LocationConfig.normalizeTransport(LocationConfig.TRANSPORT_DATACHANNEL, LocationConfig.PROVIDER_VKCALLS)
        )
        assertEquals(1, LocationConfig.supportedBypassProviders.count { it == LocationConfig.PROVIDER_VKCALLS })
    }
}
