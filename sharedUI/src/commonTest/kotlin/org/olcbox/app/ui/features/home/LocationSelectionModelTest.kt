package org.olcbox.app.ui.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.LocationKind
import org.olcbox.app.ui.features.home.components.buildBoardModel
import org.olcbox.app.ui.features.locations.LocationItem

class LocationSelectionModelTest {
    @Test
    fun lowestSortsOnlyItsSubscriptionByMeasuredLatency() {
        val firstUrl = "https://one.example/sub"
        val secondUrl = "https://two.example/sub"
        val locations = listOf(
            item("one-slow", firstUrl),
            item("one-fast", firstUrl),
            item("two-first", secondUrl),
            item("two-second", secondUrl)
        )
        val pings = mapOf(
            "one-slow" to 300,
            "one-fast" to 40,
            "two-first" to 200,
            "two-second" to 20
        )

        val board = buildBoardModel(
            locations = locations,
            activeFilterKey = null,
            sort = SubscriptionSort.None,
            lowestSubscriptionUrls = setOf(firstUrl),
            pingFor = pings::get
        )

        assertEquals(listOf("one-fast", "one-slow"), board.subscriptionGroups[0].locations.map { it.storageId })
        assertEquals(listOf("two-first", "two-second"), board.subscriptionGroups[1].locations.map { it.storageId })
    }

    private fun item(id: String, subscriptionUrl: String) = LocationItem(
        storageId = id,
        fullName = id,
        config = LocationConfig(kind = LocationKind.Vless, name = id),
        subscriptionUrl = subscriptionUrl
    )
}
