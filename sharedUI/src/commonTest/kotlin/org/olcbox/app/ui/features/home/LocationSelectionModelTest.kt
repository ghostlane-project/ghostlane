package org.olcbox.app.ui.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.PinnedLocation
import org.olcbox.app.data.model.RefusedList
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.TransportKind
import org.olcbox.app.ui.features.home.components.buildBoardModel
import org.olcbox.app.ui.features.home.components.headMarksSelection
import org.olcbox.app.ui.features.home.components.locationDisplayParts
import org.olcbox.app.ui.features.home.components.pin
import org.olcbox.app.ui.features.home.components.refusalNote
import org.olcbox.app.ui.features.home.components.subscriptionTitle
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

    // ── rooms pinned to the top of their lists ─────────────────────────────

    private val ours = "https://one.example/sub"
    private val theirs = "https://two.example/sub"

    private fun room(
        id: String,
        name: String,
        list: String? = ours,
        provider: String = LocationConfig.PROVIDER_TELEMOST,
        roomId: String = "meeting-$id"
    ) = LocationItem(
        storageId = id,
        fullName = name,
        config = LocationConfig(
            name = name,
            id = roomId,
            key = "k".repeat(64),
            bypassProvider = provider,
            transport = LocationConfig.TRANSPORT_VP8CHANNEL
        ).normalized(),
        subscriptionUrl = list,
        metadata = LocationMetadata(name = name)
    )

    private fun board(
        locations: List<LocationItem>,
        pinned: List<PinnedLocation>,
        sort: SubscriptionSort = SubscriptionSort.None,
        filter: String? = null,
        pings: Map<String, Int> = emptyMap()
    ) = buildBoardModel(
        locations = locations,
        activeFilterKey = filter,
        sort = sort,
        pinned = pinned,
        pingFor = pings::get
    )

    @Test
    fun pinnedRoomsStandFirstInTheirListInTheOrderTheyWerePinned() {
        val rooms = listOf(room("a", "CA"), room("b", "DE"), room("c", "ES"), room("d", "FR"))

        val model = board(rooms, pinned = listOf(rooms[3].pin(), rooms[1].pin()))

        assertEquals(listOf("d", "b", "a", "c"), model.subscriptionGroups.single().locations.map { it.storageId })
        assertEquals(setOf("d", "b"), model.pinnedIds)
    }

    @Test
    fun theRestOfTheListIsSortedAsItWasAskedToBe() {
        val rooms = listOf(room("a", "Delta"), room("b", "Alpha"), room("c", "Charlie"), room("d", "Bravo"))

        val byName = board(rooms, pinned = listOf(rooms[0].pin()), sort = SubscriptionSort.Alphabetical)
        assertEquals(listOf("a", "b", "d", "c"), byName.subscriptionGroups.single().locations.map { it.storageId })

        val byPing = board(
            rooms,
            pinned = listOf(rooms[0].pin()),
            sort = SubscriptionSort.Ping,
            pings = mapOf("a" to 900, "b" to 300, "c" to 20, "d" to 100)
        )
        assertEquals(listOf("a", "c", "d", "b"), byPing.subscriptionGroups.single().locations.map { it.storageId })
    }

    @Test
    fun aPinStaysOnItsRoomWhenAFetchOfTheListGivesTheRoomANewMeeting() {
        // The service ended the meeting and the server made another: the line
        // changed, so the entry has a new storage id. It is the same room to
        // the person who pinned it — same list, same service, same name.
        val before = room("imported_es", "ES · olcRTC", roomId = "meeting-one")
        val after = room("imported_es_2", "ES · olcRTC", roomId = "meeting-two")

        val model = board(listOf(room("a", "CA · olcRTC"), after), pinned = listOf(before.pin()))

        assertEquals(listOf("imported_es_2", "a"), model.subscriptionGroups.single().locations.map { it.storageId })
        assertEquals(setOf("imported_es_2"), model.pinnedIds)
    }

    @Test
    fun roomsOfOneNameOnDifferentServicesArePinnedOneAtATime() {
        val telemost = room("tm", "ES · VP8", provider = LocationConfig.PROVIDER_TELEMOST)
        val vk = room("vk", "ES · VP8", provider = LocationConfig.PROVIDER_VKCALLS)

        val model = board(listOf(telemost, vk), pinned = listOf(vk.pin()))

        assertEquals(listOf("vk", "tm"), model.subscriptionGroups.single().locations.map { it.storageId })
        assertEquals(setOf("vk"), model.pinnedIds)
    }

    @Test
    fun aPinInOneListDoesNotPinTheRoomOfThatNameInAnother() {
        val mine = room("mine", "ES", list = ours)
        val other = room("other", "ES", list = theirs)
        val model = board(listOf(room("a", "CA", list = ours), mine, room("b", "CA", list = theirs), other), pinned = listOf(mine.pin()))

        assertEquals(listOf("mine", "a"), model.subscriptionGroups[0].locations.map { it.storageId })
        assertEquals(listOf("b", "other"), model.subscriptionGroups[1].locations.map { it.storageId })
    }

    @Test
    fun aPinnedRoomIsFilteredLikeAnyOther() {
        // The chips say what is on the board. A pinned room of another
        // transport shown under "Reality" would make the chip a liar.
        val pinnedRoom = room("room", "ES · olcRTC")
        val server = item("server", ours)
        val model = board(listOf(server, pinnedRoom), pinned = listOf(pinnedRoom.pin()), filter = TransportKind.Tls.name)

        assertEquals(listOf("server"), model.subscriptionGroups.single().locations.map { it.storageId })
        assertEquals(emptySet(), model.pinnedIds)
    }

    @Test
    fun aLocationAddedByHandIsPinnedAmongItsOwn() {
        val first = room("first", "Home", list = null)
        val second = room("second", "Office", list = null)
        val model = board(listOf(first, second, room("a", "CA")), pinned = listOf(second.pin()))

        assertEquals(listOf("second", "first"), model.customLocations.map { it.storageId })
        assertEquals(setOf("second"), model.pinnedIds)
    }

    @Test
    fun aPinWhoseRoomHasLeftTheListChangesNothing() {
        val rooms = listOf(room("a", "CA"), room("b", "DE"))
        val model = board(rooms, pinned = listOf(room("gone", "ES").pin()))

        assertEquals(listOf("a", "b"), model.subscriptionGroups.single().locations.map { it.storageId })
        assertEquals(emptySet(), model.pinnedIds)
    }

    @Test
    fun aListThatPicksItsFastestStillPutsItsPinnedRoomsFirst() {
        val rooms = listOf(room("slow", "CA"), room("fast", "DE"), room("mine", "ES"))
        val model = buildBoardModel(
            locations = rooms,
            activeFilterKey = null,
            sort = SubscriptionSort.None,
            lowestSubscriptionUrls = setOf(ours),
            pinned = listOf(rooms[2].pin()),
            pingFor = mapOf("slow" to 300, "fast" to 40, "mine" to 500)::get
        )
        assertEquals(listOf("mine", "fast", "slow"), model.subscriptionGroups.single().locations.map { it.storageId })
    }

    @Test
    fun aFoldedListsHeadMarksTheExitInUseOnlyWhenNoRowOnTheBoardDoes() {
        val rooms = listOf(room("a", "CA"), room("b", "DE"))
        // Folded with nothing pinned: no row is left to say it, so the head does.
        assertTrue(headMarksSelection(rooms, shown = emptyList(), selectedLocationId = "b", lowestSelected = false))
        // The exit in use is pinned: its own card is on the board, drawn as chosen.
        assertFalse(headMarksSelection(rooms, shown = listOf(rooms[1]), selectedLocationId = "b", lowestSelected = false))
        // Another room is pinned and on the board; the one in use is folded away.
        assertTrue(headMarksSelection(rooms, shown = listOf(rooms[0]), selectedLocationId = "b", lowestSelected = false))
        // The list picks its fastest. No card is drawn as chosen then, and the
        // row that is — the one that picks — is folded away with the rest.
        assertTrue(headMarksSelection(rooms, shown = listOf(rooms[1]), selectedLocationId = "b", lowestSelected = true))
        // The exit in use is in another list.
        assertFalse(headMarksSelection(rooms, shown = emptyList(), selectedLocationId = "elsewhere", lowestSelected = false))
        assertFalse(headMarksSelection(rooms, shown = emptyList(), selectedLocationId = null, lowestSelected = false))
    }

    // A list whose server puts no flag into its names stood beside one whose
    // server does, as letters beside pictures. Where the system draws flags, a
    // line named by its country's code gets the country's.
    @Test
    fun aLineNamedByItsCountrysCodeIsShownWithTheCountrysFlag() {
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA" to "DE Direct", locationDisplayParts(room("a", "DE Direct"), drawFlags = true))
        assertEquals("\uD83C\uDDEC\uD83C\uDDE7" to "GB · olcRTC · VK", locationDisplayParts(room("b", "GB · olcRTC · VK"), drawFlags = true))
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA" to "DE via RU · Hysteria2", locationDisplayParts(room("c", "DE via RU · Hysteria2"), drawFlags = true))
    }

    @Test
    fun aLineThatBringsItsOwnPictureKeepsIt() {
        // A partner's server writes the flag into the name: it is the name's, and the name is what is left.
        assertEquals("\uD83C\uDDE8\uD83C\uDDE6" to "CA · VP8", locationDisplayParts(room("a", "\uD83C\uDDE8\uD83C\uDDE6 CA · VP8"), drawFlags = true))
        // Another picture in front of a country's code is not replaced by the country's flag.
        assertEquals("\uD83D\uDE80" to "DE Direct", locationDisplayParts(room("b", "\uD83D\uDE80 DE Direct"), drawFlags = true))
        // Nor is the line's own icon, or its list's.
        val withIcon = room("c", "DE Direct").let { it.copy(metadata = it.metadata?.copy(icon = "\u26A1")) }
        assertEquals("\u26A1" to "DE Direct", locationDisplayParts(withIcon, drawFlags = true))
    }

    @Test
    fun aNameThatDoesNotOpenWithACountrysCodeGetsNoFlag() {
        for (name in listOf("fra-1 Direct", "Frankfurt", "de Direct", "DEU Direct", "Мой VPN DE Direct")) {
            assertEquals("" to name, locationDisplayParts(room("a", name), drawFlags = true), name)
        }
    }

    @Test
    fun whereFlagsAreNotDrawnNoneIsAdded() {
        // Windows prints a flag as its two letters, beside a name that opens with them already.
        assertEquals("" to "DE Direct", locationDisplayParts(room("a", "DE Direct"), drawFlags = false))
        // What a name brings is still shown as it came.
        assertEquals("\uD83C\uDDE8\uD83C\uDDE6" to "CA · VP8", locationDisplayParts(room("b", "\uD83C\uDDE8\uD83C\uDDE6 CA · VP8"), drawFlags = false))
    }

    // ── a list whose host refused this device ──────────────────────────────

    private val refusedList = RefusedList(
        subscriptionUrl = theirs,
        subscription = SubscriptionMetadata(name = "Partner VPN", announce = "No room for this device")
    )

    @Test
    fun aRefusedListIsAListWithNoRowsAfterTheOthers() {
        val model = buildBoardModel(
            locations = listOf(room("a", "CA")),
            activeFilterKey = null,
            sort = SubscriptionSort.None,
            refused = listOf(refusedList),
            pingFor = { null }
        )

        assertEquals(listOf(listOf("a"), emptyList()), model.subscriptionGroups.map { group -> group.locations.map { it.storageId } })
        assertEquals(refusedList, model.subscriptionGroups.last().refused)
        // It counts no rows, and its head is the list's own name.
        assertEquals(1, model.totalCount)
        assertEquals("Partner VPN", refusedList.subscriptionTitle())
    }

    @Test
    fun aBoardOfARefusedListAloneIsNotTheEmptyBoard() {
        // The empty board introduces the app to somebody with no list. This
        // person has one, and the board has to say why it holds nothing.
        val model = buildBoardModel(emptyList(), null, SubscriptionSort.None, refused = listOf(refusedList)) { null }
        assertFalse(model.isEmpty)
        assertEquals(listOf(refusedList), model.subscriptionGroups.map { it.refused })
    }

    @Test
    fun aRefusedListIsOnTheBoardWhateverTheFilter() {
        // It has no rows of any transport, and what it says is about the list.
        val model = buildBoardModel(
            locations = listOf(item("server", ours), room("room", "ES · olcRTC")),
            activeFilterKey = TransportKind.Tls.name,
            sort = SubscriptionSort.None,
            refused = listOf(refusedList)
        ) { null }
        assertEquals(listOf(listOf("server"), emptyList()), model.subscriptionGroups.map { group -> group.locations.map { it.storageId } })
        assertEquals(refusedList, model.subscriptionGroups.last().refused)
    }

    @Test
    fun aRefusalIsSaidInTheProvidersWordsOrOurs() {
        assertEquals("No room", refusalNote("No room", ours = "Ours", providersOwnText = true))
        assertEquals("Ours", refusalNote(null, ours = "Ours", providersOwnText = true))
        assertEquals("Ours", refusalNote("  ", ours = "Ours", providersOwnText = true))
        // Never the provider's own text on the iPhone, where its notes are not shown.
        assertEquals("Ours", refusalNote("No room", ours = "Ours", providersOwnText = false))
    }

    private fun item(id: String, subscriptionUrl: String) = LocationItem(
        storageId = id,
        fullName = id,
        config = LocationConfig(kind = LocationKind.Vless, name = id),
        subscriptionUrl = subscriptionUrl
    )
}
