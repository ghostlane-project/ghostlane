package org.olcbox.app.ui.features.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
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
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the board keeps about its lists — which are folded away — written through
 * the screen's model.
 *
 * It was reported from a phone: fold a list, leave the app, come back, and the
 * list is unfolded. Folding lived in the screen's own memory, which lasts as long
 * as the process does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenModelSettingsTest {
    private val models = mutableListOf<HomeScreenViewModel>()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun entry(id: String, list: String?) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = id, id = "room-$id", key = "k".repeat(64)),
        subscriptionUrl = list
    )

    private fun stored(settings: SubscriptionSettings = SubscriptionSettings(autoUpdate = false)) = LocationBundleV4(
        activeLocationId = "a1",
        locations = listOf(entry("a1", ONE), entry("a2", ONE), entry("b1", TWO)),
        settings = settings
    )

    private fun model(source: LocationsDataSource) = HomeScreenViewModel(
        vpnManager = StillVpn(),
        locationsRepository = LocationsRepositoryImpl(source),
        configImporter = ImportsNothing,
        logExporter = ExportsNothing
    ).also { models += it }

    @Test fun aListFoldedIsFoldedOnTheScreenBeforeItIsWritten() = runTest {
        // The chevron turns under the finger. Written first and shown after,
        // it turned when the disk was done.
        val source = SlowToWrite(stored())
        val model = model(source)

        model.changeSubscriptionSettings { it.withFolded(ONE, folded = true) }

        assertTrue(model.subscriptionSettings.value.isFolded(ONE), "not folded on the screen")
        assertFalse(source.stored.settings.isFolded(ONE), "written before the write was let through")
        source.letWritesThrough()
        assertTrue(source.stored.settings.isFolded(ONE), "never written")
    }

    @Test fun whatIsFoldedIsFoldedTheNextTimeTheAppOpens() = runTest {
        val source = Memory(stored())
        model(source).changeSubscriptionSettings { it.withFolded(ONE, folded = true) }

        // A process later: another model over what the first one left stored.
        val next = model(source)

        assertTrue(next.subscriptionSettings.value.isFolded(ONE))
        assertFalse(next.subscriptionSettings.value.isFolded(TWO))
    }

    @Test fun changesMadeOneAfterAnotherAreAllKept() = runTest {
        // Each is made to the settings as they stand, not to the copy a screen
        // was last drawn from: two presses inside one write lost the first.
        val source = SlowToWrite(stored())
        val model = model(source)

        model.changeSubscriptionSettings { it.withFolded(ONE, folded = true) }
        model.changeSubscriptionSettings { it.withFolded(TWO, folded = true) }
        model.changeSubscriptionSettings { it.copy(sort = SubscriptionSort.Ping) }
        source.letWritesThrough()

        val written = source.stored.settings
        assertEquals(setOf(ONE, TWO), written.foldedSubscriptionUrls)
        assertEquals(SubscriptionSort.Ping, written.sort)
        assertEquals(written, model.subscriptionSettings.value)
    }

    @Test fun nothingIsChangedBeforeTheStoredSettingsHaveArrived() = runTest {
        // Until they have, what the model holds is a fresh object. A change
        // made to that and written would replace everything the user had set.
        val source = SlowToRead(stored(SubscriptionSettings(autoUpdate = false, sort = SubscriptionSort.Ping)))
        val model = model(source)

        model.changeSubscriptionSettings { it.withFolded(ONE, folded = true) }
        source.letReadsThrough()

        assertEquals(SubscriptionSort.Ping, model.subscriptionSettings.value.sort)
        assertFalse(model.subscriptionSettings.value.isFolded(ONE))
        assertEquals(SubscriptionSort.Ping, source.stored.settings.sort)

        model.changeSubscriptionSettings { it.withFolded(ONE, folded = true) }
        assertTrue(source.stored.settings.isFolded(ONE))
        assertEquals(SubscriptionSort.Ping, source.stored.settings.sort)
    }

    @Test fun aListThatIsRemovedIsForgottenByTheSettingsToo() = runTest {
        val source = Memory(stored(SubscriptionSettings(autoUpdate = false, lowestSubscriptionUrls = setOf(ONE, TWO))))
        val model = model(source)
        model.changeSubscriptionSettings { it.withFolded(ONE, folded = true).withFolded(TWO, folded = true) }

        var removed = -1
        model.deleteSubscription(ONE) { removed = it }

        assertEquals(2, removed)
        assertEquals(setOf(TWO), model.subscriptionSettings.value.foldedSubscriptionUrls)
        assertEquals(setOf(TWO), source.stored.settings.foldedSubscriptionUrls)
        assertEquals(setOf(TWO), source.stored.settings.lowestSubscriptionUrls)
    }

    private companion object {
        const val ONE = "https://one.example/sub"
        const val TWO = "https://two.example/sub"
    }
}

private open class Memory(var stored: LocationBundleV4) : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

/** A store whose writes wait to be let through, as a disk's do. */
private class SlowToWrite(stored: LocationBundleV4) : Memory(stored) {
    private val gate = CompletableDeferred<Unit>()
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) {
        gate.await()
        super.saveLocationBundle(bundle)
    }
    fun letWritesThrough() { gate.complete(Unit) }
}

/** A store whose first read has not come back yet. */
private class SlowToRead(stored: LocationBundleV4) : Memory(stored) {
    private val gate = CompletableDeferred<Unit>()
    override suspend fun loadLocationBundle(): LocationBundleV4? {
        gate.await()
        return super.loadLocationBundle()
    }
    fun letReadsThrough() { gate.complete(Unit) }
}

private class StillVpn : VpnManager {
    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val connectedSince = MutableStateFlow<Long?>(null)
    override val traffic: StateFlow<TrafficCounters?> = MutableStateFlow(null)
    override fun needsPermission(): Boolean = false
    override fun startVpn() {}
    override fun stopVpn() {}
    override fun canPing(locationConfig: LocationConfig) = true
    override suspend fun ping(locationConfig: LocationConfig): Long? = null
    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
}

private object ImportsNothing : ConfigImporter {
    override fun getFromClipboard(): String? = null
    override fun copyToClipboard(text: String) {}
    override suspend fun readTextFromSource(source: Any): String? = null
}

private object ExportsNothing : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> = Result.success("")
}
