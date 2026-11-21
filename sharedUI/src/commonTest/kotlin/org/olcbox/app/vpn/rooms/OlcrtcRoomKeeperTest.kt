package org.olcbox.app.vpn.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RoomKeeper.swift's timing and outcomes, on the Kotlin port, in the test's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class OlcrtcRoomKeeperTest {
    private val key = "a".repeat(64)
    private val initial = OlcrtcRoomList.Parsed("V1", listOf("V2"))
    private val d = '$'

    private fun body(primary: String, vararg extras: String) =
        "olcrtc://vkcalls?vp8channel@$primary#$key${d}DE\n##rooms: ${extras.joinToString(",")}"

    private class FakeIo(private val outcomes: ArrayDeque<RoomFetchOutcome>) : OlcrtcRoomKeeper.Io {
        val fetchedAt = mutableListOf<Long>()
        val applied = mutableListOf<OlcrtcRoomList.Parsed>()
        val remembered = mutableListOf<OlcrtcRoomList.Parsed>()
        val logs = mutableListOf<String>()
        var refuseApply = false
        var cancelFromInside = false
        var clock: () -> Long = { 0 }

        override suspend fun fetch(url: String): RoomFetchOutcome {
            fetchedAt += clock()
            if (cancelFromInside) {
                cancelFromInside = false
                throw CancellationException("a client's own cancellation, the keeper still running")
            }
            return outcomes.removeFirstOrNull() ?: RoomFetchOutcome.Failed(0, "NoMoreScripted")
        }

        override fun apply(rooms: OlcrtcRoomList.Parsed) {
            if (refuseApply) throw IllegalArgumentException("engine says no")
            applied += rooms
        }

        override fun remember(rooms: OlcrtcRoomList.Parsed) {
            remembered += rooms
        }

        override fun log(line: String) {
            logs += line
        }
    }

    private fun TestScope.keeper(
        vararg outcomes: RoomFetchOutcome,
        firstSession: Boolean = false,
        url: String? = "https://lists.example/sub/abc",
    ): Pair<OlcrtcRoomKeeper, FakeIo> {
        val io = FakeIo(ArrayDeque(outcomes.toList())).also { it.clock = { testScheduler.currentTime } }
        val k = OlcrtcRoomKeeper(
            parent = backgroundScope,
            carrier = "vkcalls",
            keyHex = key,
            subscriptionUrl = url,
            initial = initial,
            refreshOnFirstSession = firstSession,
            io = io,
            nowMs = { testScheduler.currentTime }
        )
        k.begin()
        runCurrent()
        return k to io
    }

    private fun ok(primary: String, vararg extras: String) = RoomFetchOutcome.Body(body(primary, *extras))

    @Test fun theFirstSessionRefreshesOnlyWhenAskedTheSecondAlways() = runTest {
        val (k, io) = keeper(ok("V1", "V2"), ok("V1", "V2"))
        k.onSessionOpened("V1"); runCurrent()
        assertEquals(0, io.fetchedAt.size, "the first session of a start is the app's to refresh (iOS)")
        k.onSessionOpened("V2"); runCurrent()
        assertEquals(1, io.fetchedAt.size, "a hop refreshes")

        val (k2, io2) = keeper(ok("V1", "V2"), firstSession = true)
        k2.onSessionOpened("V1"); runCurrent()
        assertEquals(1, io2.fetchedAt.size, "on Android the first session refreshes too")
    }

    @Test fun twoTriggersWithin15SecondsAreOneFetch() = runTest {
        val (k, io) = keeper(ok("V1", "V2"), ok("V1", "V2"), firstSession = true)
        k.onSessionOpened("V1"); runCurrent()
        advanceTimeBy(5_000); k.onSessionOpened("V2"); runCurrent()
        assertEquals(1, io.fetchedAt.size)
        advanceTimeBy(11_000); k.onSessionOpened("V1"); runCurrent()
        assertEquals(2, io.fetchedAt.size, "16 s after the first")
    }

    @Test fun aFailureIsRetriedOnceAfter30SecondsPastTheThrottle() = runTest {
        val (k, io) = keeper(RoomFetchOutcome.Failed(503, null), ok("V7", "V8"), firstSession = true)
        k.onSessionOpened("V1"); runCurrent()
        assertEquals(1, io.fetchedAt.size)
        advanceTimeBy(29_000); runCurrent()
        assertEquals(1, io.fetchedAt.size)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(listOf(0L, 30_000L), io.fetchedAt)
        assertEquals(listOf(OlcrtcRoomList.Parsed("V7", listOf("V8"))), io.applied)
        assertEquals(OlcrtcRoomList.Parsed("V7", listOf("V8")), k.rooms)
    }

    @Test fun aRefusalKeepsTheRoomsAndDropsAPendingRetry() = runTest {
        val refusal = RoomFetchOutcome.Refused(RoomListRefusal("x-hwid-max-devices-reached", "Free a place"))
        val (k, io) = keeper(RoomFetchOutcome.Failed(0, "SocketTimeoutException"), refusal, firstSession = true)
        k.onSessionOpened("V1"); runCurrent()
        advanceTimeBy(20_000); k.onSessionOpened("V2"); runCurrent()
        assertEquals(2, io.fetchedAt.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, io.fetchedAt.size, "no retry after a refusal")
        assertTrue(io.applied.isEmpty() && io.remembered.isEmpty())
        assertEquals(initial, k.rooms)
        assertTrue(io.logs.any { it.contains("refused (x-hwid-max-devices-reached): Free a place - rooms kept") })
    }

    @Test fun thePeriodicRefreshStartsWhenArmed() = runTest {
        val (k, io) = keeper(ok("V1", "V2"), ok("V1", "V2"))
        advanceTimeBy(301_000); runCurrent()
        assertEquals(0, io.fetchedAt.size, "not before armed()")
        k.armed()
        advanceTimeBy(299_000); runCurrent()
        assertEquals(0, io.fetchedAt.size)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(1, io.fetchedAt.size)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, io.fetchedAt.size)
    }

    @Test fun anUnchangedListIsNotHandedOver() = runTest {
        val (k, io) = keeper(ok("V1", "V2"), firstSession = true)
        k.onSessionOpened("V1"); runCurrent()
        assertTrue(io.applied.isEmpty())
        assertTrue(io.logs.any { it.startsWith("olcRTC room list unchanged") })
    }

    @Test fun aListTheEngineRefusesChangesNothing() = runTest {
        val (k, io) = keeper(ok("V5"), firstSession = true)
        io.refuseApply = true
        k.onSessionOpened("V1"); runCurrent()
        assertEquals(initial, k.rooms)
        assertTrue(io.remembered.isEmpty())
        assertTrue(io.logs.any { it.contains("rejected by the engine") })
    }

    @Test fun aSubscriptionWithoutOurLineChangesNothing() = runTest {
        val other = RoomFetchOutcome.Body("olcrtc://mtslink?vp8channel@M1#$key")
        val (k, io) = keeper(other, firstSession = true)
        k.onSessionOpened("V1"); runCurrent()
        assertEquals(initial, k.rooms)
        assertTrue(io.logs.any { it.contains("no line with this location's carrier and key") })
    }

    @Test fun theAppsListIsTakenForThisCarrierAndKeyOnly() = runTest {
        val (k, io) = keeper()
        k.fromStore(OlcrtcRoomList.Parsed("M1", emptyList()), "mtslink", key); runCurrent()
        k.fromStore(OlcrtcRoomList.Parsed("V1", listOf("V2")), "vk", "c".repeat(64)); runCurrent()
        assertTrue(io.applied.isEmpty())
        k.fromStore(OlcrtcRoomList.Parsed("V3", listOf("V1")), "VK", key.uppercase()); runCurrent()
        assertEquals(listOf(OlcrtcRoomList.Parsed("V3", listOf("V1"))), io.applied)
        assertEquals(io.applied, io.remembered)
    }

    @Test fun noAddressNoFetch() = runTest {
        val (k, io) = keeper(firstSession = true, url = null)
        k.onSessionOpened("V1"); k.onSessionOpened("V2"); runCurrent()
        assertEquals(0, io.fetchedAt.size)
    }

    @Test fun nothingLogsARoomItself() = runTest {
        val (k, io) = keeper(ok("SECRET-ROOM-7", "SECRET-ROOM-8"), firstSession = true)
        k.onSessionOpened("SECRET-ROOM-1"); runCurrent()
        assertEquals(1, io.applied.size)
        assertTrue(io.logs.none { it.contains("SECRET") || it.contains("lists.example") || it.contains(key) }, io.logs.joinToString("\n"))
    }

    @Test fun theServiceAsksOnceTheLineIsVerifiedWithinTheThrottle() = runTest {
        val (k, io) = keeper(ok("V5", "V6"), ok("V5", "V6"))
        k.onSessionOpened("V1"); runCurrent()
        assertEquals(0, io.fetchedAt.size, "the first session, inside the engine's start, does not ask")
        k.refreshNow("connected"); runCurrent()
        assertEquals(1, io.fetchedAt.size)
        assertEquals(OlcrtcRoomList.Parsed("V5", listOf("V6")), k.rooms)
        advanceTimeBy(10_000); k.refreshNow("connected"); runCurrent()
        assertEquals(1, io.fetchedAt.size, "a re-verify within 15 s does not ask again")
        advanceTimeBy(6_000); k.refreshNow("connected"); runCurrent()
        assertEquals(2, io.fetchedAt.size)
    }

    @Test fun aCancellationFromInsideTheFetchIsAFailureNotAStall() = runTest {
        val (k, io) = keeper(ok("V7", "V8"))
        io.cancelFromInside = true
        k.refreshNow("connected"); runCurrent()
        assertTrue(io.logs.any { it.startsWith("olcRTC room list refresh failed (status 0") }, io.logs.toString())
        advanceTimeBy(30_001); runCurrent()
        assertEquals(listOf(0L, 30_000L), io.fetchedAt, "retried: the keeper is not left fetching for ever")
        assertEquals(OlcrtcRoomList.Parsed("V7", listOf("V8")), k.rooms)
    }

    @Test fun stopEndsEverything() = runTest {
        val (k, io) = keeper(ok("V1", "V2"), firstSession = true)
        k.armed()
        k.stop()
        k.onSessionOpened("V1")
        advanceTimeBy(1_000_000); runCurrent()
        assertEquals(0, io.fetchedAt.size)
    }
}
