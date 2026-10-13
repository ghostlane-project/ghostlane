package org.olcbox.app.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.SubscriptionSettings
import org.olcbox.app.net.OlcrtcStatusClient
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

/**
 * The app itself, for tests that are about more than how a screen is laid out:
 * its own screen over its own models, a store kept in memory and a tunnel that
 * is whatever the test says it is.
 *
 * [BoardFixture] draws the board from made-up state, which is the way to look at
 * a layout. What the app does with a press, and what is left of that when its
 * screen is built again, is in the models and the screen between them, and those
 * are here.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
internal class AppFixture {
    private val made = mutableListOf<ViewModel>()

    /** How many times the screen asked for the tunnel to be switched: a press of its button, or the app's own. */
    var toggled = 0
        private set

    /** The models an app holds for as long as its process lives. */
    class Models(val home: HomeScreenViewModel, val locations: LocationViewModel)

    /** Before the first model is made: their coroutines run where the test does. */
    fun begin() = Dispatchers.setMain(UnconfinedTestDispatcher())

    fun end() {
        runBlocking { made.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    /** [clock] is what the models read the time off; a test that counts seconds holds it still or moves it. */
    fun models(
        source: LocationsDataSource,
        tunnel: VpnManager = Tunnel(),
        clock: () -> Long = ::nowMillis
    ): Models {
        val repository = LocationsRepositoryImpl(source)
        val home = HomeScreenViewModel(
            vpnManager = tunnel,
            locationsRepository = repository,
            configImporter = ImportsNothing,
            logExporter = ExportsNothing,
            clock = clock
        )
        // No room is asked how full it is: nothing here goes to a network.
        val nobodyAnswers = OlcrtcStatusClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }))
        val locations = LocationViewModel(repository, nobodyAnswers)
        made += home
        made += locations
        locations.loadLocations()
        return Models(home, locations)
    }

    @Composable
    private fun App(models: Models) {
        OlcboxAppContent(
            homeViewModel = models.home,
            locationViewModel = models.locations,
            currentScreen = AppScreen.Home,
            onNavigate = {},
            onToggleClick = { toggled++ },
            onImportFileRequested = {},
            onImportFromClipboardRequested = { _, _ -> },
            onSaveLogsRequested = { _, _ -> },
            showAppSettingsButton = true,
            onAppSettingsClick = {}
        )
    }

    /**
     * The app's screen over [models] on [phone], with a way to have it built
     * again as an iPhone builds it on every return to the app: everything that
     * was composed is dropped, and composed anew over the same models.
     */
    fun <T> onTheScreen(
        phone: Phone,
        models: Models,
        /** Whether anybody can see the screen, where a test says so; the test scene's own answer where it does not. */
        seenBy: LifecycleOwner? = null,
        read: DesktopComposeUiTest.(buildAgain: () -> Unit) -> T
    ): T {
        val built = mutableIntStateOf(0)
        val screen: @Composable () -> Unit = {
            if (seenBy == null) App(models) else CompositionLocalProvider(LocalLifecycleOwner provides seenBy) { App(models) }
        }
        return measuredOn(phone, { key(built.intValue) { screen() } }) {
            read {
                built.intValue++
                waitForIdle()
            }
        }
    }

    companion object {
        /** What a phone has stored: the fixture's two lists and its own rooms. */
        fun stored(): LocationBundleV4 {
            val entries = BoardFixture.locations(nowMillis()).map { item ->
                LocationEntry.from(
                    storageId = item.storageId,
                    location = item.config ?: error("a fixture row with no location"),
                    subscriptionUrl = item.subscriptionUrl,
                    subscriptionOriginLink = item.subscriptionOriginLink,
                    metadata = item.metadata
                )
            }
            return LocationBundleV4(
                activeLocationId = BoardFixture.ROOM_IN_USE,
                locations = entries,
                // Nothing fetches a list by itself while a test looks at the board.
                settings = SubscriptionSettings(autoUpdate = false),
                onboardingSeenAt = 1L,
                vpnDisclosureAcceptedAt = 1L
            )
        }
    }
}

/**
 * A screen that is in front of somebody or is not, as a test says: an app in the
 * background, a window put away in the tray.
 */
internal class Seen(inFront: Boolean = true) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    var inFront: Boolean = inFront
        set(value) {
            field = value
            registry.currentState = if (value) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        }

    init {
        this.inFront = inFront
    }
}

/** A store that is a variable. */
internal class Memory(var stored: LocationBundleV4) : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

/**
 * A platform's tunnel as a test describes it: down unless it is given the moment
 * its session came up, and counting its traffic or not.
 */
internal class Tunnel(
    upSince: Long? = null,
    private val counts: Boolean = true,
    var counters: TrafficCounters? = null
) : VpnManager {
    /** How many times the counters were asked for. */
    var readings = 0
        private set

    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status = MutableStateFlow(if (upSince != null) VpnStatus.Connected else VpnStatus.Disconnected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(upSince != null)
    override val connectedSince = MutableStateFlow(upSince)
    override val countsTraffic: Boolean get() = counts
    override suspend fun readTraffic(): TrafficCounters? {
        readings++
        return counters
    }
    override fun needsPermission(): Boolean = false
    override fun startVpn() {}
    override fun stopVpn() {}
    override fun canPing(locationConfig: LocationConfig) = false
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
