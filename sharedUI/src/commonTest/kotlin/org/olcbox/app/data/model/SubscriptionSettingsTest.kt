package org.olcbox.app.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionSettingsTest {
    @Test fun lowestCanBelongToOneSubscription() {
        val first = "https://one.example/sub"
        val second = "https://two.example/sub"
        val settings = SubscriptionSettings(lowestSubscriptionUrls = setOf("  $first  ")).normalized()
        assertTrue(settings.lowestEnabledFor(first))
        assertFalse(settings.lowestEnabledFor(second))
    }

    @Test fun oldGlobalLowestSettingStillReadsBackWithoutChangingStoredBundles() {
        val settings = SubscriptionSettings(autoSelectLowest = true)
        assertTrue(settings.lowestEnabledFor("https://one.example/sub"))
        assertFalse(settings.lowestEnabledFor(null))
    }

    @Test fun choosingARealServerTurnsOffLowestOnlyForItsOwnSubscription() {
        val first = "https://one.example/sub"
        val second = "https://two.example/sub"
        val changed = SubscriptionSettings(lowestSubscriptionUrls = setOf(first, second))
            .withLowestEnabled(first, enabled = false, knownSubscriptionUrls = setOf(first, second))
        assertFalse(changed.lowestEnabledFor(first))
        assertTrue(changed.lowestEnabledFor(second))
    }

    @Test fun editingLegacyLowestMaterialisesEveryKnownSubscriptionFirst() {
        val first = "https://one.example/sub"
        val second = "https://two.example/sub"
        val changed = SubscriptionSettings(autoSelectLowest = true)
            .withLowestEnabled(first, enabled = false, knownSubscriptionUrls = setOf(first, second))
        assertFalse(changed.autoSelectLowest)
        assertFalse(changed.lowestEnabledFor(first))
        assertTrue(changed.lowestEnabledFor(second))
    }

    // ── which lists are folded away ────────────────────────────────────────

    @Test fun aFoldedListIsKnownByItsAddress() {
        val list = "https://one.example/sub"
        val settings = SubscriptionSettings().withFolded("  $list ", folded = true)
        assertTrue(settings.isFolded(list))
        assertTrue(settings.isFolded(" $list"))
        assertFalse(settings.isFolded("https://two.example/sub"))
        assertFalse(settings.isFolded(null))
    }

    @Test fun unfoldingAListForgetsItAndLeavesTheOthersFolded() {
        val first = "https://one.example/sub"
        val second = "https://two.example/sub"
        val settings = SubscriptionSettings()
            .withFolded(first, folded = true)
            .withFolded(second, folded = true)
            .withFolded(first, folded = false)
        assertFalse(settings.isFolded(first))
        assertTrue(settings.isFolded(second))
        assertEquals(setOf(second), settings.foldedSubscriptionUrls)
    }

    @Test fun aListWithNoAddressIsNotFoldedAndCannotBe() {
        val settings = SubscriptionSettings().withFolded("   ", folded = true).withFolded(null, folded = true)
        assertEquals(SubscriptionSettings(), settings)
    }

    @Test fun whatIsFoldedIsWrittenAndReadBack() {
        // Folding lived in the screen's own memory, which a phone throws away
        // with the process: fold a list, leave the app, come back to it unfolded.
        val list = "https://one.example/sub"
        val json = Json { ignoreUnknownKeys = true }
        val written = json.encodeToString(SubscriptionSettings.serializer(), SubscriptionSettings().withFolded(list, folded = true))
        assertTrue(json.decodeFromString(SubscriptionSettings.serializer(), written).isFolded(list))
    }

    @Test fun settingsStoredBeforeFoldingWasKeptHaveNothingFolded() {
        val stored = """{"sort":"ping","collapsible":true,"lowest_subscription_urls":["https://one.example/sub"]}"""
        val settings = Json { ignoreUnknownKeys = true }.decodeFromString(SubscriptionSettings.serializer(), stored)
        assertEquals(emptySet(), settings.foldedSubscriptionUrls)
        assertEquals(SubscriptionSort.Ping, settings.sort)
    }

    @Test fun aListThatIsRemovedLeavesNothingBehindInTheSettings() {
        // Its address is what the settings know it by, and for an encrypted
        // list the address is the thing its link was hiding.
        val gone = "https://one.example/sub"
        val kept = "https://two.example/sub"
        val settings = SubscriptionSettings(lowestSubscriptionUrls = setOf(gone, kept))
            .withFolded(gone, folded = true)
            .withFolded(kept, folded = true)
            .withoutSubscription(" $gone ")
        assertEquals(setOf(kept), settings.foldedSubscriptionUrls)
        assertEquals(setOf(kept), settings.lowestSubscriptionUrls)
    }

    @Test fun removingTheLastListThatChoseItsFastestDoesNotTurnTheOldGlobalChoiceBackOn() {
        // An empty set with the old flag set means "every list"; an empty set
        // left by a removal must not come to mean that.
        val gone = "https://one.example/sub"
        val settings = SubscriptionSettings(lowestSubscriptionUrls = setOf(gone)).withoutSubscription(gone)
        assertFalse(settings.lowestEnabledFor("https://two.example/sub"))
        assertFalse(settings.hasAnyLowest())
    }

    // ── which rooms are pinned to the top of their lists ───────────────────

    private fun pin(name: String, list: String = "https://one.example/sub", wire: String = "olcrtc:telemost:vp8channel") =
        PinnedLocation(subscriptionUrl = list, wire = wire, name = name)

    @Test fun pinsAreKeptInTheOrderTheyWereMade() {
        // The first room somebody pinned is the one they reach for without
        // looking; a later pin goes under it, not over it.
        val settings = SubscriptionSettings()
            .withPinned(pin("ES"), pinned = true)
            .withPinned(pin("DE"), pinned = true)
            .withPinned(pin("CA"), pinned = true)
        assertEquals(listOf("ES", "DE", "CA"), settings.pinnedLocations.map { it.name })
        assertTrue(settings.isPinned(pin("DE")))
        assertFalse(settings.isPinned(pin("FR")))
    }

    @Test fun pinningARoomTwiceIsOnePinWhereItWas() {
        val settings = SubscriptionSettings()
            .withPinned(pin("ES"), pinned = true)
            .withPinned(pin("DE"), pinned = true)
            .withPinned(pin(" ES "), pinned = true)
        assertEquals(listOf("ES", "DE"), settings.pinnedLocations.map { it.name })
    }

    @Test fun unpinningTakesOutThatRoomAndLeavesTheOrderOfTheRest() {
        val settings = SubscriptionSettings()
            .withPinned(pin("ES"), pinned = true)
            .withPinned(pin("DE"), pinned = true)
            .withPinned(pin("CA"), pinned = true)
            .withPinned(pin("DE"), pinned = false)
        assertEquals(listOf("ES", "CA"), settings.pinnedLocations.map { it.name })
        assertFalse(settings.isPinned(pin("DE")))
    }

    @Test fun aRoomIsPinnedInItsOwnListAndOnItsOwnWire() {
        // A list names one exit's rooms alike, a room to a meeting service; and
        // two lists may both have an "ES".
        val settings = SubscriptionSettings().withPinned(pin("ES"), pinned = true)
        assertFalse(settings.isPinned(pin("ES", list = "https://two.example/sub")))
        assertFalse(settings.isPinned(pin("ES", wire = "olcrtc:vkcalls:vp8channel")))
        assertTrue(settings.isPinned(pin("ES")))
    }

    @Test fun aRoomWithNoNameCannotBePinned() {
        assertEquals(SubscriptionSettings(), SubscriptionSettings().withPinned(pin("   "), pinned = true))
    }

    @Test fun pinsAreWrittenAndReadBackInOrder() {
        val json = Json { ignoreUnknownKeys = true }
        val settings = SubscriptionSettings().withPinned(pin("ES"), pinned = true).withPinned(pin("DE"), pinned = true)
        val read = json.decodeFromString(SubscriptionSettings.serializer(), json.encodeToString(SubscriptionSettings.serializer(), settings))
        assertEquals(settings.pinnedLocations, read.pinnedLocations)
    }

    @Test fun settingsStoredBeforePinsWereKeptHaveNone() {
        val settings = Json { ignoreUnknownKeys = true }.decodeFromString(SubscriptionSettings.serializer(), """{"sort":"name"}""")
        assertEquals(emptyList(), settings.pinnedLocations)
    }

    @Test fun aListThatIsRemovedTakesItsPinsWithIt() {
        val gone = "https://one.example/sub"
        val kept = "https://two.example/sub"
        val settings = SubscriptionSettings()
            .withPinned(pin("ES", list = gone), pinned = true)
            .withPinned(pin("DE", list = kept), pinned = true)
            .withPinned(pin("Mine", list = ""), pinned = true)
            .withoutSubscription(gone)
        assertEquals(listOf("DE", "Mine"), settings.pinnedLocations.map { it.name })
    }

    @Test fun thereIsAMostThatCanBePinnedAndTheOldestGiveWay() {
        // A pin's rooms can leave their list for good, and their pins stay
        // written where nobody sees them. The most keeps that from growing.
        var settings = SubscriptionSettings()
        repeat(SubscriptionSettings.MAX_PINNED + 3) { settings = settings.withPinned(pin("room-$it"), pinned = true) }
        assertEquals(SubscriptionSettings.MAX_PINNED, settings.pinnedLocations.size)
        assertEquals("room-3", settings.pinnedLocations.first().name)
        assertEquals("room-${SubscriptionSettings.MAX_PINNED + 2}", settings.pinnedLocations.last().name)
    }
}
