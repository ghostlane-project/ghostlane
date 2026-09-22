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
}
