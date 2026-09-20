package org.olcbox.app.data.model

import kotlin.test.Test
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
}
