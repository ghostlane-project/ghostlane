package org.olcbox.app.ui.features.home

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.mismatch_explanation
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.net.ImportLink
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenModelImportLinkTest {
    private val source = MemoryLocationsDataSource()
    private val repository = LocationsRepositoryImpl(source)
    private val models = mutableListOf<HomeScreenViewModel>()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() {
        // IO continuations must finish before resetting the test Main dispatcher;
        // otherwise a previous view model can crash the following test class.
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun viewModel(vpn: IdleVpnManager = IdleVpnManager()) = HomeScreenViewModel(
        vpnManager = vpn,
        locationsRepository = repository,
        configImporter = NoConfigImporter,
        logExporter = NoLogExporter
    ).also { models += it }

    // The import hops to Dispatchers.IO, a real thread; virtual time would
    // race ahead of it, so the wait is measured in real seconds.
    private suspend fun awaitForReal(outcome: CompletableDeferred<String>): String =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { outcome.await() } }

    private val realityLink = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
        "?security=reality&encryption=none&pbk=PUBKEY&sid=ab12&fp=chrome&sni=www.example.com&flow=xtls-rprx-vision&type=tcp#DE"

    @Test fun stopDuringLowestRankingCancelsThePendingStart() = runTest {
        source.stored = LocationBundleV4(
            activeLocationId = "one",
            locations = listOf(org.olcbox.app.data.model.LocationEntry(
                storageId = "one", subscriptionUrl = "https://example.com/sub",
                kind = org.olcbox.app.net.LocationKind.Vless, rawLink = realityLink
            )),
            settings = org.olcbox.app.data.model.SubscriptionSettings(autoUpdate = false, autoSelectLowest = true)
        )
        val vpn = IdleVpnManager()
        val entered = CompletableDeferred<Unit>()
        vpn.probe = { entered.complete(Unit); awaitCancellation() }
        val vm = viewModel(vpn)
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.subscriptionSettingsLoaded.first { it } } }
            // The sentence a connect reads on its way to the ranking, read here
            // first. The first string a process asks for is read from its file
            // on a real thread, and the model went on from there: the ranking
            // then ran beside this test instead of inside it, and the test's
            // clock, which is not a real one, ran the ranking's six seconds
            // out before a single probe had been entered. So this test passed
            // after any test that had read a string, and hung for its whole
            // minute when it was the first in its process, as it is when run
            // by itself.
            getString(Res.string.mismatch_explanation)
            vm.ToggleVpn()
            entered.await()
            assertTrue(vm.state.value.isVpnLoading)
            vm.ToggleVpn()
            assertEquals(0, vpn.starts)
            assertEquals(1, vpn.stops)
            assertEquals(false, vm.state.value.isVpnLoading)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun migrationKeepsStartupMeasurementAndMarksItsLateAnswerHistorical() = runTest {
        repository.importText(realityLink)
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            error("Address measurements must not query room occupancy")
        })
        val vm = org.olcbox.app.ui.features.locations.LocationViewModel(
            repository, org.olcbox.app.net.OlcrtcStatusClient(client)
        )
        try {
            val answer = CompletableDeferred<Long>()
            val finished = CompletableDeferred<Unit>()
            vm.refreshPings(performPing = { answer.await() }, canPing = { true },
                onComplete = { _, _ -> finished.complete(Unit) })
            vm.markPingsStale()
            answer.complete(42)
            finished.await()
            val id = vm.locations.single().storageId
            assertEquals(42, (vm.pingsState as org.olcbox.app.ui.features.locations.PingsState.Success).pings[id])
            assertTrue(id in vm.stalePingIds)
            val refreshed = CompletableDeferred<Unit>()
            vm.refreshPings(performPing = { 17 }, canPing = { true },
                onComplete = { _, _ -> refreshed.complete(Unit) })
            refreshed.await()
            assertTrue(vm.stalePingIds.isEmpty())
            assertEquals(17, (vm.pingsState as org.olcbox.app.ui.features.locations.PingsState.Success).pings[id])
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
        }
    }

    @Test fun cancellingAListMeasurementStillCompletesItsRequest() = runTest {
        repository.importText(realityLink)
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            error("Address measurements must not query room occupancy")
        })
        val vm = org.olcbox.app.ui.features.locations.LocationViewModel(
            repository, org.olcbox.app.net.OlcrtcStatusClient(client)
        )
        try {
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Pair<Int, Int>>()
            vm.refreshPings(
                performPing = { entered.complete(Unit); awaitCancellation() },
                canPing = { true },
                onComplete = { online, total -> finished.complete(online to total) }
            )
            entered.await()
            vm.cancelPings()
            assertEquals(0 to 1, withTimeout(5_000) { finished.await() })
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
        }
    }

    @Test fun preconnectMeasurementCompletesAtItsCallerBudget() = runTest {
        repository.importText(realityLink)
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            error("Address measurements must not query room occupancy")
        })
        val vm = org.olcbox.app.ui.features.locations.LocationViewModel(
            repository, org.olcbox.app.net.OlcrtcStatusClient(client)
        )
        try {
            val finished = CompletableDeferred<Pair<Int, Int>>()
            vm.refreshPings(
                performPing = { awaitCancellation() },
                canPing = { true },
                overallDeadlineMs = 50,
                onComplete = { online, total -> finished.complete(online to total) }
            )
            assertEquals(0 to 1, withTimeout(5_000) { finished.await() })
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
        }
    }

    @Test fun migrationDiscardsAnInflightSampleEvenWhenTheConnectionClockIsUnchanged() = runTest {
        val vpn = IdleVpnManager()
        val vm = viewModel(vpn)
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.subscriptionSettingsLoaded.first { it } } }
            vpn.connectedSince.value = 123L
            vpn.status.value = VpnStatus.Connected
            val entered = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Long?>()
            vpn.measure = { entered.complete(Unit); answer.await() }
            val request = async { vm.measureActiveChannel() }
            entered.await()
            assertNull(vm.channelLatency.value)
            vpn.status.value = VpnStatus.Reconnecting
            vpn.status.value = VpnStatus.Connected
            answer.complete(42L)
            request.await()
            assertNull(vm.channelLatency.value)
            vpn.measure = { 17L }
            vm.measureActiveChannel()
            assertEquals("HTTP 17ms", vm.channelLatency.value?.label())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun failureOnlyAppearsAfterTheFirstCompletedSample() = runTest {
        val vpn = IdleVpnManager()
        val vm = viewModel(vpn)
        try {
            vpn.status.value = VpnStatus.Connected
            assertNull(vm.channelLatency.value)
            vm.measureActiveChannel()
            assertEquals("HTTP —", vm.channelLatency.value?.label())
            vpn.status.value = VpnStatus.Disconnected
            assertNull(vm.channelLatency.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun anImportLinkGoesThroughTheSameImportAsAPaste() = runTest {
        val outcome = CompletableDeferred<String>()
        viewModel().onImportLink(
            uri = ImportLink.schemeLink(realityLink),
            onComplete = { outcome.complete("ok") },
            onError = { outcome.complete("error: $it") }
        )
        assertEquals("ok", awaitForReal(outcome))
        val names = repository.getAllLocations().map { it.name }
        assertEquals(listOf("DE"), names)
    }

    @Test fun somethingThatIsNotAnImportLinkIsRefusedBeforeAnyImport() = runTest {
        val outcome = CompletableDeferred<String>()
        viewModel().onImportLink(
            uri = "https://example.org/add#$realityLink",
            onComplete = { outcome.complete("ok") },
            onError = { outcome.complete("error: $it") }
        )
        assertEquals("error: Not a Ghostlane import link", awaitForReal(outcome))
        assertTrue(repository.getAllLocations().isEmpty())
    }

    // ── a list's host refusing this device ────────────────────────────────

    private val ours = "https://example.com/ours"
    private val theirs = "https://partner.example.com/sub"
    private val roomKey = "c".repeat(64)

    /** A room of [list], refreshed a moment ago: the due pass the model runs at its start leaves it be. */
    private fun room(id: String, list: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(
            name = id,
            id = "room-$id",
            key = roomKey,
            bypassProvider = LocationConfig.PROVIDER_TELEMOST,
            transport = LocationConfig.TRANSPORT_VP8CHANNEL
        ),
        subscriptionUrl = list,
        metadata = LocationMetadata(subscription = SubscriptionMetadata(lastRefreshAtEpochMs = nowMillis()))
    )

    /** Our list serves its room; the partner's host refuses this device, as the server words it. */
    private fun answering(store: LocationsDataSource) = LocationsRepositoryImpl(
        dataSource = store,
        httpClient = HttpClient(MockEngine { request ->
            if (request.url.host == "partner.example.com") {
                respond(
                    content = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:1?type=tcp&security=none#No%20room",
                    headers = headersOf(
                        "x-hwid-max-devices-reached" to listOf("true"),
                        "announce" to listOf("No room for this device")
                    )
                )
            } else {
                respond("olcrtc://telemost?vp8channel@room-ours#$roomKey${'$'}ours")
            }
        })
    )

    /** The tunnel comes up, the model asks for every list through it, and the one write that makes is waited for. */
    private suspend fun connectAndAwaitTheRefresh(vm: HomeScreenViewModel, vpn: IdleVpnManager, repo: LocationsRepositoryImpl) {
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.subscriptionSettingsLoaded.first { it } } }
        val before = repo.changes.value
        vpn.status.value = VpnStatus.Connected
        // The pass runs on a real thread, so its write is waited for in real time.
        withContext(Dispatchers.Default) { withTimeout(10_000) { repo.changes.first { it > before } } }
    }

    // A list refused while the tunnel is up — asked through it as the session
    // begins — loses its servers there and then, and nothing else moves: the
    // session runs on, on the server chosen in another list, which stays chosen,
    // and nothing is started or stopped for it.
    @Test fun aRefusalKeepsTheRunningTunnel() = runTest {
        val store = MemoryLocationsDataSource(
            LocationBundleV4(activeLocationId = "ours", locations = listOf(room("ours", ours), room("theirs", theirs)))
        )
        val repo = answering(store)
        val vpn = IdleVpnManager()
        val vm = HomeScreenViewModel(
            vpnManager = vpn,
            locationsRepository = repo,
            configImporter = NoConfigImporter,
            logExporter = NoLogExporter
        ).also { models += it }
        try {
            connectAndAwaitTheRefresh(vm, vpn, repo)

            assertEquals(listOf(theirs), store.stored!!.refusedLists.map { it.subscriptionUrl })
            assertEquals(listOf("ours"), store.stored!!.locations.map { it.storageId })
            assertEquals("ours", repo.getActiveLocationId())
            assertEquals(0, vpn.starts)
            assertEquals(0, vpn.stops)
        } finally { vm.viewModelScope.cancel() }
    }

    // The list refused is the one the session runs on, beside another. Its
    // servers go, that one among them, and nothing is selected in its place:
    // the other list's server, which the store would otherwise have chosen,
    // is one the person never chose, and the platform's next start of the
    // tunnel would have carried the session onto it. Nothing is started or
    // stopped now, and the screen names no server as the one in use.
    @Test fun aRefusalOfTheListInUseCutsNothingAndChoosesNothing() = runTest {
        val store = MemoryLocationsDataSource(
            LocationBundleV4(activeLocationId = "theirs", locations = listOf(room("ours", ours), room("theirs", theirs)))
        )
        val repo = answering(store)
        val vpn = IdleVpnManager()
        val vm = HomeScreenViewModel(
            vpnManager = vpn,
            locationsRepository = repo,
            configImporter = NoConfigImporter,
            logExporter = NoLogExporter
        ).also { models += it }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.state.first { it.selectedLocation?.storageId == "theirs" } }
            }
            connectAndAwaitTheRefresh(vm, vpn, repo)

            assertNull(repo.getActiveLocationId())
            assertEquals(listOf("ours"), store.stored!!.locations.map { it.storageId })
            assertEquals("room-ours", store.stored!!.locations.single().location.id)
            assertEquals(listOf(theirs), store.stored!!.refusedLists.map { it.subscriptionUrl })
            assertEquals(0, vpn.starts)
            assertEquals(0, vpn.stops)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.state.first { it.selectedLocation == null } }
            }
        } finally { vm.viewModelScope.cancel() }
    }

    /** A refresh of one list, as a settings screen makes it: the report, then the one rule for a restart. */
    private suspend fun refreshAsTheSettingsScreensDo(vm: HomeScreenViewModel, url: String): org.olcbox.app.data.repository.SubscriptionRefreshReport {
        val done = CompletableDeferred<org.olcbox.app.data.repository.SubscriptionRefreshReport>()
        vm.refreshSubscription(url) { report ->
            vm.restartVpnAfterRefresh(report)
            done.complete(report)
        }
        return withContext(Dispatchers.Default) { withTimeout(10_000) { done.await() } }
    }

    // The selection a refusal withdrew stays withdrawn through later refreshes,
    // and a settings screen restarts the tunnel after every refresh: with
    // nothing selected that start would end the session the host still
    // carries, at the first refresh of any list after the refusal.
    @Test fun aRefreshWhileNothingIsSelectedStartsNothing() = runTest {
        val store = MemoryLocationsDataSource(
            LocationBundleV4(activeLocationId = "theirs", locations = listOf(room("ours", ours), room("theirs", theirs)))
        )
        val repo = answering(store)
        val vpn = IdleVpnManager()
        val vm = HomeScreenViewModel(
            vpnManager = vpn,
            locationsRepository = repo,
            configImporter = NoConfigImporter,
            logExporter = NoLogExporter
        ).also { models += it }
        try {
            connectAndAwaitTheRefresh(vm, vpn, repo)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.state.first { it.selectedLocation == null } }
            }

            val report = refreshAsTheSettingsScreensDo(vm, ours)

            assertEquals(1, report.updatedCount)
            assertNull(repo.getActiveLocationId())
            assertEquals(0, vpn.starts, "a refresh started a tunnel with nothing selected")
            assertEquals(0, vpn.stops)
        } finally { vm.viewModelScope.cancel() }
    }

    // A refresh of the list the selected server is in starts the tunnel again,
    // as a settings screen always has.
    @Test fun aRefreshOfTheSelectedListStartsTheTunnelAgainAsBefore() = runTest {
        val store = MemoryLocationsDataSource(
            LocationBundleV4(activeLocationId = "ours", locations = listOf(room("ours", ours), room("theirs", theirs)))
        )
        val repo = answering(store)
        val vpn = IdleVpnManager()
        val vm = HomeScreenViewModel(
            vpnManager = vpn,
            locationsRepository = repo,
            configImporter = NoConfigImporter,
            logExporter = NoLogExporter
        ).also { models += it }
        try {
            connectAndAwaitTheRefresh(vm, vpn, repo)

            val report = refreshAsTheSettingsScreensDo(vm, ours)

            assertEquals(1, report.updatedCount)
            assertEquals("ours", repo.getActiveLocationId())
            assertEquals(1, vpn.starts)
        } finally { vm.viewModelScope.cancel() }
    }

    // The board reads the store's selection and, finding none, used to choose
    // the first location and write it back: a withdrawn selection stays so.
    @Test fun theBoardLeavesAWithdrawnSelectionWithdrawn() = runTest {
        val store = MemoryLocationsDataSource(
            LocationBundleV4(
                locations = listOf(
                    LocationEntry.from(
                        storageId = "ours",
                        location = LocationConfig(name = "DE", kind = org.olcbox.app.net.LocationKind.Vless, rawLink = realityLink),
                        subscriptionUrl = ours
                    )
                ),
                selectionWithdrawn = true
            )
        )
        val repo = LocationsRepositoryImpl(store)
        val client = HttpClient(MockEngine { error("Address locations must not query room occupancy") })
        val vm = org.olcbox.app.ui.features.locations.LocationViewModel(repo, org.olcbox.app.net.OlcrtcStatusClient(client))
        try {
            val loaded = CompletableDeferred<Unit>()
            vm.loadLocations { loaded.complete(Unit) }
            withContext(Dispatchers.Default) { withTimeout(10_000) { loaded.await() } }

            assertEquals(listOf("ours"), vm.locations.map { it.storageId })
            assertNull(vm.selectedLocationId)
            assertNull(repo.getActiveLocationId())
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
        }
    }

    // The person who pasted the link hears why there is nothing to connect to,
    // in the provider's words, and the list stands on the board with its reason.
    @Test fun anImportItsHostRefusesSaysWhyAndKeepsTheList() = runTest {
        val store = MemoryLocationsDataSource()
        val repo = answering(store)
        val outcome = CompletableDeferred<String>()
        HomeScreenViewModel(
            vpnManager = IdleVpnManager(),
            locationsRepository = repo,
            configImporter = NoConfigImporter,
            logExporter = NoLogExporter
        ).also { models += it }.onImportFullConfig(
            rawText = theirs,
            onComplete = { outcome.complete("ok") },
            onError = { outcome.complete("error: $it") }
        )
        assertEquals("error: No room for this device", awaitForReal(outcome))
        assertTrue(repo.getAllLocations().isEmpty())
        assertEquals(listOf(theirs), store.stored!!.refusedLists.map { it.subscriptionUrl })
    }
}

private class MemoryLocationsDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

private class IdleVpnManager : VpnManager {
    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val connectedSince = MutableStateFlow<Long?>(null)
    var measure: suspend () -> Long? = { null }
    var probe: suspend () -> Long? = { null }
    var starts = 0
    var stops = 0
    override suspend fun measureCurrentChannel(): Long? = measure()
    override fun needsPermission(): Boolean = false
    override fun startVpn() { starts++ }
    override fun stopVpn() { stops++ }
    override fun canPing(locationConfig: LocationConfig) = true
    override suspend fun ping(locationConfig: LocationConfig): Long? = probe()
    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
}

private object NoConfigImporter : ConfigImporter {
    override fun getFromClipboard(): String? = null
    override fun copyToClipboard(text: String) {}
    override suspend fun readTextFromSource(source: Any): String? = null
}

private object NoLogExporter : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> = Result.success("")
}
