package org.olcbox.app.ui.features.home

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.*
import org.olcbox.app.net.LocationKind
import org.olcbox.app.vpn.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LowestConnectionTest {
    private fun entry(id: String, subscription: String? = "https://example.com/sub") = LocationEntry(
        storageId = id, name = id, subscriptionUrl = subscription, kind = LocationKind.Vless,
        rawLink = "vless://11111111-1111-1111-1111-111111111111@$id.example.com:443?type=tcp&security=none#$id"
    )

    private class Source(var bundle: LocationBundleV4) : LocationsDataSource {
        override suspend fun loadLocationBundle() = bundle
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) { this.bundle = bundle }
        override suspend fun loadLegacyLocations() = emptyList<Pair<String, String>>()
        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private class Vpn : VpnManager {
        override val logs = MutableStateFlow(emptyList<String>())
        override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
        override val isConnected = MutableStateFlow(false)
        override val connectedSince = MutableStateFlow<Long?>(null)
        override val traffic = MutableStateFlow<TrafficCounters?>(null)
        var onStart: () -> Unit = { status.value = VpnStatus.Connected }
        var probe: suspend (LocationConfig) -> Long? = { if (it.name == "fast") 10 else 100 }
        var starts = 0
        var stops = 0
        override fun needsPermission() = false
        override fun canPing(locationConfig: LocationConfig) = true
        override fun startVpn() { starts++; onStart() }
        override fun stopVpn() { stops++; status.value = VpnStatus.Disconnected }
        override suspend fun ping(locationConfig: LocationConfig) = probe(locationConfig)
        override suspend fun checkConnection(locationConfig: LocationConfig): Long? = error("Must not open a second tunnel")
    }

    private fun repository(entries: List<LocationEntry>) = LocationsRepositoryImpl(Source(LocationBundleV4(
        activeLocationId = entries.first().storageId, locations = entries,
        settings = SubscriptionSettings(autoUpdate = false, autoSelectLowest = true)
    )))

    @Test fun choosesFastestInTheSameSubscriptionAndRetainsItDuringMigration() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast"), entry("outside", "https://other.com/sub")))
        val vpn = Vpn()
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        assertEquals("fast", repo.getActiveLocationId())
        assertEquals(1, vpn.starts)
        vpn.status.value = VpnStatus.Reconnecting
        runCurrent()
        vpn.status.value = VpnStatus.Connected
        advanceTimeBy(60_000)
        assertEquals(1, vpn.starts)
        task.cancelAndJoin()
    }

    @Test fun terminalFailureStopsOldTunnelAndRetriesAfterCooldown() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn()
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        vpn.status.value = VpnStatus.Error("transport exhausted retries")
        runCurrent()
        assertEquals(1, vpn.starts)
        assertTrue(vpn.stops > 0)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(2, vpn.starts)
        assertEquals("slow", repo.getActiveLocationId())
        task.cancelAndJoin()
    }

    @Test fun cancellationDuringRankingNeverStartsATunnel() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn().apply { probe = { awaitCancellation() } }
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        task.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(0, vpn.starts)
        assertEquals("slow", repo.getActiveLocationId())
    }

    @Test fun cancellationDuringCooldownDoesNotReconnect() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn()
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        vpn.status.value = VpnStatus.Error("failed")
        runCurrent()
        task.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, vpn.starts)
    }

    @Test fun missingEchoesAreUnknownAndRoomsAreNeverJoinedForRanking() = runTest {
        val room = LocationEntry(storageId = "room", name = "room", legacyId = "meeting", legacyKey = "key")
        val entries = listOf(entry("first"), entry("selected"), room)
        val vpn = Vpn().apply { probe = {
            assertNotEquals(LocationKind.Olcrtc, it.kind)
            null
        } }
        val ranked = LowestConnection(vpn, repository(entries)) {}.rank(entries, "selected")
        assertEquals(listOf("selected", "first", "room"), ranked.map { it.storageId })
    }

    @Test fun aDeletedFallbackIsNotResurrectedAfterCooldown() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn()
        val outcome = async { runCatching { LowestConnection(vpn, repo) {}.run() } }
        runCurrent()
        vpn.status.value = VpnStatus.Error("failed")
        runCurrent()
        repo.deleteLocation("slow")
        advanceTimeBy(15_000); runCurrent()
        assertTrue(outcome.await().isFailure)
        assertEquals(1, vpn.starts)
    }

    @Test fun stopFromOutsideTheAppDoesNotTriggerFailover() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn()
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        vpn.status.value = VpnStatus.Disconnected
        task.join()
        advanceUntilIdle()
        assertEquals(1, vpn.starts)
    }

    @Test fun failedStartsHaveAFiniteAttemptBudget() = runTest {
        val repo = repository(listOf(entry("a"), entry("b"), entry("c"), entry("d")))
        val vpn = Vpn().apply { onStart = { status.value = VpnStatus.Error("refused") } }
        val outcome = async { runCatching { LowestConnection(vpn, repo) {}.run() } }
        advanceUntilIdle()
        assertTrue(outcome.await().isFailure)
        assertEquals(3, vpn.starts)
    }

    @Test fun disablingLowestDuringCooldownDoesNotStartTheNextServer() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn()
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        vpn.status.value = VpnStatus.Error("failed")
        runCurrent()
        repo.saveSubscriptionSettings(SubscriptionSettings(autoSelectLowest = false))
        advanceUntilIdle()
        task.join()
        assertEquals(1, vpn.starts)
    }

    @Test fun aConnectionStartedElsewhereDuringRankingIsNotReplaced() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn().apply { probe = { delay(1_000); 10 } }
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        vpn.status.value = VpnStatus.Connected
        advanceUntilIdle()
        task.join()
        assertEquals(0, vpn.starts)
        assertEquals(VpnStatus.Connected, vpn.status.value)
    }

    @Test fun stalledRankingHasABudgetAndKeepsUnknownServersEligible() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn().apply { probe = { awaitCancellation() } }
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, vpn.starts)
        assertEquals("slow", repo.getActiveLocationId())
        task.cancelAndJoin()
    }

    @Test fun anOsStopDuringConnectionIsNotRetried() = runTest {
        val repo = repository(listOf(entry("slow"), entry("fast")))
        val vpn = Vpn().apply { onStart = { status.value = VpnStatus.Connecting } }
        val task = launch { LowestConnection(vpn, repo) {}.run() }
        runCurrent()
        vpn.status.value = VpnStatus.Disconnected
        advanceUntilIdle()
        task.join()
        assertEquals(1, vpn.starts)
    }
}
