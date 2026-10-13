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
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A session's traffic, as the screen's model keeps it.
 *
 * It was the screen's own, drawn from a value the platform refreshed when it
 * got round to it. The model reads the platform's counters itself, at the
 * moment it is asked to, and keeps what it has read for as long as the session
 * lasts: a screen built again over it shows the chart the last one did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenModelTrafficTest {
    private val models = mutableListOf<HomeScreenViewModel>()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun model(vpn: VpnManager) = HomeScreenViewModel(
        vpnManager = vpn,
        locationsRepository = LocationsRepositoryImpl(NothingStored),
        configImporter = NoImports,
        logExporter = NoExports
    ).also { models += it }

    @Test fun aSecondOfASessionIsWhatThePlatformsCountersMovedByBetweenTwoReadings() = runTest {
        val tunnel = CountedTunnel(TrafficCounters(bytesIn = 1_000L, bytesOut = 100L))
        val model = model(tunnel)
        model.trafficSessionIs(SESSION)

        model.sampleTraffic(atMillis = BEGAN)
        tunnel.counters = TrafficCounters(bytesIn = 4_000L, bytesOut = 150L)
        model.sampleTraffic(atMillis = BEGAN + 1_000L)

        assertEquals(2, tunnel.readings, "the platform is asked once for each second")
        assertEquals(TrafficCounters(4_000L, 150L), model.sessionTraffic.totals)
        assertEquals(3_000L, model.sessionTraffic.window.receivedAgo(0))
        assertEquals(50L, model.sessionTraffic.window.sentAgo(0))
    }

    @Test fun whereThePlatformHasNothingToReadNothingIsPutOnRecord() = runTest {
        // Not a session carrying nothing: nobody is counting. No zeroes.
        val model = model(CountedTunnel(counters = null))
        model.trafficSessionIs(SESSION)

        model.sampleTraffic(atMillis = BEGAN)
        model.sampleTraffic(atMillis = BEGAN + 1_000L)

        assertNull(model.sessionTraffic.totals)
        assertFalse(model.sessionTraffic.window.hasData)
    }

    @Test fun aSessionKeepsItsChartWhenAScreenIsBuiltOverItAgain() = runTest {
        val tunnel = CountedTunnel(TrafficCounters(0L, 0L))
        val model = model(tunnel)
        model.trafficSessionIs(SESSION)
        model.sampleTraffic(atMillis = BEGAN)
        tunnel.counters = TrafficCounters(9_000L, 900L)
        model.sampleTraffic(atMillis = BEGAN + 1_000L)

        // What a screen says first, every time it is built: which session it is showing.
        model.trafficSessionIs(SESSION)

        assertEquals(9_000L, model.sessionTraffic.window.receivedAgo(0))
        assertEquals(TrafficCounters(9_000L, 900L), model.sessionTraffic.totals)
    }

    @Test fun anotherSessionAndNoSessionHaveNoneOfThatTraffic() = runTest {
        val tunnel = CountedTunnel(TrafficCounters(0L, 0L))
        val model = model(tunnel)
        suspend fun aSecondOfTraffic() {
            model.sampleTraffic(atMillis = BEGAN)
            tunnel.counters = TrafficCounters(tunnel.counters!!.bytesIn + 9_000L, 900L)
            model.sampleTraffic(atMillis = BEGAN + 1_000L)
        }

        model.trafficSessionIs(SESSION)
        aSecondOfTraffic()
        assertTrue(model.sessionTraffic.window.hasData)

        model.trafficSessionIs(SESSION + 60_000L)
        assertFalse(model.sessionTraffic.window.hasData, "the next session opened with the last one's chart")
        assertNull(model.sessionTraffic.totals)

        aSecondOfTraffic()
        model.trafficSessionIs(null)
        assertFalse(model.sessionTraffic.window.hasData, "a chart was left standing with no session up")
        assertNull(model.sessionTraffic.totals)
    }

    @Test fun whetherAnythingIsCountedHereIsThePlatformsToSay() {
        assertTrue(model(CountedTunnel(null, counts = true)).countsTraffic)
        assertFalse(model(CountedTunnel(null, counts = false)).countsTraffic)
    }

    private companion object {
        /** When the session came up, which is what tells it from another. */
        const val SESSION = 1_760_000_000_000L
        const val BEGAN = SESSION + 2_000L
    }
}

/** A tunnel whose counters stand wherever the test leaves them. */
private class CountedTunnel(var counters: TrafficCounters?, private val counts: Boolean = true) : VpnManager {
    var readings = 0
        private set

    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status = MutableStateFlow<VpnStatus>(VpnStatus.Connected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)
    override val connectedSince = MutableStateFlow<Long?>(null)
    override val countsTraffic: Boolean get() = counts
    override suspend fun readTraffic(): TrafficCounters? {
        readings++
        return counters
    }
    override fun needsPermission(): Boolean = false
    override fun startVpn() {}
    override fun stopVpn() {}
    override suspend fun ping(locationConfig: LocationConfig): Long? = null
    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
}

private object NothingStored : LocationsDataSource {
    private var stored = LocationBundleV4(settings = SubscriptionSettings(autoUpdate = false))
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

private object NoImports : ConfigImporter {
    override fun getFromClipboard(): String? = null
    override fun copyToClipboard(text: String) {}
    override suspend fun readTextFromSource(source: Any): String? = null
}

private object NoExports : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> = Result.success("")
}
