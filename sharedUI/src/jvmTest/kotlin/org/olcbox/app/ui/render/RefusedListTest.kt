package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import org.olcbox.app.data.model.DeviceRefusal
import org.olcbox.app.data.model.RefusedList
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.repository.DEVICE_LIMIT_REACHED_MESSAGE
import org.olcbox.app.data.repository.DEVICE_NOT_SUPPORTED_MESSAGE
import org.olcbox.app.ui.components.kit.pkTied
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A list whose host refused this device, as the board draws it: its head, and
 * where its rooms would be, why there are none.
 *
 * Its host turned the device away (the list is at its device limit, or this
 * device was removed from it), so it keeps no room. A list that vanished
 * would leave nobody any wiser; one that stood empty and silent would read
 * as the app having lost it.
 */
@OptIn(ExperimentalTestApi::class)
class RefusedListTest {
    private val phone = Phone("iphone", 393, 852)
    private val sentence = "Your provider: this link is already in use on its 1 device. Ask your provider to free one."
    private val listUrl = "https://partner.example.org/sub/0123456789abcdef"

    private fun refused(announce: String?, refusal: DeviceRefusal = DeviceRefusal.LimitReached) = RefusedList(
        subscriptionUrl = listUrl,
        subscription = SubscriptionMetadata(
            name = "Partner VPN",
            announce = announce,
            supportUrl = "https://t.me/partner_support"
        ),
        refusal = refusal
    )

    private fun textsOn(scene: BoardFixture.Scene): List<String> =
        measuredOn(phone, { BoardFixture.Home(scene) }) { laidTexts().map { it.text } }

    @Test fun aRefusedListShowsItsHeadAndWhyInPlaceOfItsRooms() {
        val texts = textsOn(BoardFixture.Scene(connected = false, empty = true, refused = listOf(refused(sentence))))
        assertTrue("Partner VPN" in texts, "the refused list has no head: $texts")
        assertTrue(sentence in texts, "the refused list does not say why it is empty: $texts")
    }

    @Test fun aRefusedListTheProviderGaveNoWordsToSaysOursForItsKind() {
        val limit = textsOn(BoardFixture.Scene(connected = false, empty = true, refused = listOf(refused(null))))
        assertTrue(DEVICE_LIMIT_REACHED_MESSAGE in limit, "nothing says why the list is empty: $limit")
        val unread = textsOn(
            BoardFixture.Scene(connected = false, empty = true, refused = listOf(refused(null, DeviceRefusal.NotSupported)))
        )
        assertTrue(DEVICE_NOT_SUPPORTED_MESSAGE in unread, "an unread device is said as the list's limit: $unread")
    }

    @Test fun aRefusedListStandsBesideTheListsThatAreServed() {
        val texts = textsOn(BoardFixture.Scene(connected = true, refused = listOf(refused(sentence))))
        assertTrue(sentence in texts, "the refused list is not on a board with others: $texts")
        assertTrue(pkTied("ES · Madrid") in texts, "the served list lost its rows: $texts")
    }

    // Folded before its host refused this device, the list would hide the one
    // thing it has to say. It stands open.
    @Test fun aRefusedListThatWasFoldedStillSaysWhy() {
        val texts = textsOn(
            BoardFixture.Scene(connected = false, empty = true, refused = listOf(refused(sentence)), folded = setOf(listUrl))
        )
        assertTrue(sentence in texts, "a folded refused list hid why it is empty: $texts")
    }
}
