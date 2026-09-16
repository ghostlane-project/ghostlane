package org.olcbox.app.ui.features.home

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
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.ImportLink
import org.olcbox.app.vpn.TrafficCounters
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
            vm.ToggleVpn()
            entered.await()
            assertTrue(vm.state.value.isVpnLoading)
            vm.ToggleVpn()
            assertEquals(0, vpn.starts)
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
        assertEquals("error: Not a ProofKit import link", awaitForReal(outcome))
        assertTrue(repository.getAllLocations().isEmpty())
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
    override suspend fun measureCurrentChannel(): Long? = measure()
    override val traffic: StateFlow<TrafficCounters?> = MutableStateFlow(null)
    override fun needsPermission(): Boolean = false
    override fun startVpn() { starts++ }
    override fun stopVpn() {}
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
