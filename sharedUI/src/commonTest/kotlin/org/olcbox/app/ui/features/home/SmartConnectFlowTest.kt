package org.olcbox.app.ui.features.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.SmartConnect
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smart connect as the home screen runs it: which transport ends up active, what
 * it remembers, what it says, and that it connects the user's choice when nothing
 * got through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartConnectFlowTest {
    private val sub = "https://proofkit.org/sub/aaa"
    private val models = mutableListOf<HomeScreenViewModel>()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun core(id: String, name: String, kind: LocationKind, link: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = name, id = "x", kind = kind, rawLink = link),
        subscriptionUrl = sub
    )

    private val reality = core(
        "us-reality", "US via RU | 0.1320TON/GB", LocationKind.Vless,
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:443?type=tcp&security=reality&sni=www.zoom.us" +
            "&fp=chrome&pbk=abc&sid=14090023&flow=xtls-rprx-vision#US"
    )
    private val hy2 = core(
        "us-hy2", "US via RU | 0.1320TON/GB · Hysteria2", LocationKind.Hysteria2,
        "hysteria2://pass@1.2.3.4:30023?sni=www.zoom.us&obfs=salamander&obfs-password=secret&insecure=0#US"
    )
    private val room = LocationEntry.from(
        storageId = "us-room",
        location = LocationConfig(name = "US · olcRTC", id = "room-us", key = "k".repeat(64)),
        subscriptionUrl = sub
    )

    private fun repository(vararg entries: LocationEntry) = LocationsRepositoryImpl(
        MemorySource(
            LocationBundleV4(
                activeLocationId = entries.first().storageId,
                locations = entries.toList(),
                settings = SubscriptionSettings(autoUpdate = false)
            )
        )
    )

    private fun viewModel(repository: LocationsRepositoryImpl, vpn: VpnManager, whitelist: Boolean = false) =
        HomeScreenViewModel(
            vpnManager = vpn,
            locationsRepository = repository,
            configImporter = NoImporter,
            logExporter = NoExporter
        ).also {
            it.whitelistCheck = { whitelist }
            models += it
        }

    @Test fun theFirstTransportThatGetsThroughBecomesActiveAndIsRemembered() = runTest {
        val repo = repository(reality, hy2, room)
        val vpn = ProbingVpn(mapOf("us-reality" to false, "us-hy2" to true))
        val vm = viewModel(repo, vpn)

        vm.chooseTransport(reality)

        assertEquals("us-hy2", repo.getActiveLocationId())
        assertEquals(listOf("us-reality", "us-hy2"), vpn.probed)
        assertEquals("us-hy2", repo.getSubscriptionSettings().lastKnownGoodTransport[SmartConnect.groupKey(reality)])
        assertEquals("Connecting through Hysteria2", vm.state.value.progress)
    }

    @Test fun nothingElseGettingThroughEndsOnOlcrtcOfTheSameCountry() = runTest {
        val repo = repository(reality, hy2, room)
        val vpn = ProbingVpn(mapOf("us-reality" to false, "us-hy2" to false))
        val vm = viewModel(repo, vpn)

        vm.chooseTransport(reality)

        assertEquals("us-room", repo.getActiveLocationId())
        assertEquals("Nothing else got through: connecting through olcRTC", vm.state.value.progress)
    }

    @Test fun inWhitelistModeOlcrtcIsChosenWithoutProbingACore() = runTest {
        val repo = repository(reality, hy2, room)
        val vpn = ProbingVpn(mapOf("us-reality" to true, "us-hy2" to true))
        val vm = viewModel(repo, vpn, whitelist = true)

        vm.chooseTransport(reality)

        assertEquals("us-room", repo.getActiveLocationId())
        assertEquals(emptyList(), vpn.probed)
        assertEquals("Only domestic sites answer here: connecting through olcRTC", vm.state.value.progress)
    }

    @Test fun whenNothingGetsThroughTheUsersChoiceStays() = runTest {
        val repo = repository(reality, hy2)
        val vpn = ProbingVpn(mapOf("us-reality" to false, "us-hy2" to false))
        val vm = viewModel(repo, vpn)

        vm.chooseTransport(reality)

        assertEquals("us-reality", repo.getActiveLocationId())
        assertEquals("Could not check a transport: connecting as chosen", vm.state.value.progress)
    }

    @Test fun aPlatformThatCannotProbeLeavesEverythingAsItWas() = runTest {
        val repo = repository(reality, hy2, room)
        val vpn = ProbingVpn(emptyMap(), canProbe = false)
        val vm = viewModel(repo, vpn)

        vm.chooseTransport(reality)

        assertEquals("us-reality", repo.getActiveLocationId())
        assertEquals(listOf("us-reality"), vpn.probed)
    }

    @Test fun theUsersRowThatGetsThroughIsKeptWithoutANote() = runTest {
        val repo = repository(reality, hy2, room)
        val vpn = ProbingVpn(mapOf("us-reality" to true))
        val vm = viewModel(repo, vpn)

        vm.chooseTransport(reality)

        assertEquals("us-reality", repo.getActiveLocationId())
        assertEquals(null, vm.state.value.progress)
    }
}

/** Answers each probe from [results] by storage id ("x" configs carry the name only). */
private class ProbingVpn(
    private val results: Map<String, Boolean>,
    private val canProbe: Boolean = true
) : VpnManager {
    val probed = mutableListOf<String>()
    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val connectedSince = MutableStateFlow<Long?>(null)
    override val traffic: StateFlow<TrafficCounters?> = MutableStateFlow(null)
    override val canProbeTransports: Boolean get() = true
    override suspend fun probeTransport(locationConfig: LocationConfig): Boolean? {
        val id = when (locationConfig.name) {
            "US via RU | 0.1320TON/GB" -> "us-reality"
            "US via RU | 0.1320TON/GB · Hysteria2" -> "us-hy2"
            else -> locationConfig.name
        }
        probed += id
        return if (canProbe) results[id] ?: false else null
    }
    override fun needsPermission(): Boolean = false
    override fun startVpn() {}
    override fun stopVpn() {}
    override fun canPing(locationConfig: LocationConfig) = false
    override suspend fun ping(locationConfig: LocationConfig): Long? = null
    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
}

private class MemorySource(var stored: LocationBundleV4? = null) : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

private object NoImporter : ConfigImporter {
    override fun getFromClipboard(): String? = null
    override fun copyToClipboard(text: String) {}
    override suspend fun readTextFromSource(source: Any): String? = null
}

private object NoExporter : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> = Result.success("")
}
