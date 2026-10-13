package org.olcbox.app.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
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
import org.olcbox.app.ui.components.kit.pkTied
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.util.nowMillis
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
 * What is left of the board when its screen is built again over the same models.
 *
 * On an iPhone that is every return to the app. Its host lets go of the whole
 * Compose scene in the background, for the memory a tunnel's extension needs
 * more, and builds another on the way back in (`ComposeSceneHost`, iosApp); the
 * models live outside it. So whatever the screen only remembers is gone each
 * time, and it was reported from a phone a piece at a time: first the lists that
 * had been folded away, then the transport that had been chosen.
 *
 * Here the screen is the app's own, over its own models and a store kept in
 * memory, and it is built again the way the phone does it: everything composed
 * is dropped, and composed anew.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class BoardKeptTest {
    private val phone = Phone("iphone", 393, 852)
    private val models = mutableListOf<ViewModel>()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun restore() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private class Models(val home: HomeScreenViewModel, val locations: LocationViewModel)

    /** The models an app holds for as long as its process lives, over [source]. */
    private fun models(source: LocationsDataSource): Models {
        val repository = LocationsRepositoryImpl(source)
        val home = HomeScreenViewModel(
            vpnManager = NoTunnel(),
            locationsRepository = repository,
            configImporter = ImportsNothing,
            logExporter = ExportsNothing
        )
        // No room is asked how full it is: nothing here goes to a network.
        val nobodyAnswers = OlcrtcStatusClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }))
        val locations = LocationViewModel(repository, nobodyAnswers)
        models += home
        models += locations
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
            onToggleClick = {},
            onImportFileRequested = {},
            onImportFromClipboardRequested = { _, _ -> },
            onSaveLogsRequested = { _, _ -> },
            showAppSettingsButton = true,
            onAppSettingsClick = {}
        )
    }

    /** The app's screen over [models], with a way to have it built again as an iPhone builds it. */
    private fun <T> onTheBoard(models: Models, read: DesktopComposeUiTest.(buildAgain: () -> Unit) -> T): T {
        val built = mutableIntStateOf(0)
        return measuredOn(phone, { key(built.intValue) { App(models) } }) {
            read {
                built.intValue++
                waitForIdle()
            }
        }
    }

    private fun DesktopComposeUiTest.names(): Set<String> = laidTexts().map { it.text }.toSet()

    @Test fun aTransportChosenIsStillChosenWhenTheScreenIsBuiltAgain() {
        onTheBoard(models(Memory(stored()))) { buildAgain ->
            assertTrue(REALITY_ROW in names() && XHTTP_ROW in names(), "the board as it opens: ${names()}")

            onNodeWithText("REALITY").performClick()
            waitForIdle()
            assertTrue(REALITY_ROW in names(), "the transport chosen has no rows: ${names()}")
            assertFalse(XHTTP_ROW in names(), "a row of another transport stayed on the board")

            buildAgain()

            assertTrue(REALITY_ROW in names(), "the transport chosen has no rows after the screen was built again")
            assertFalse(XHTTP_ROW in names(), "the choice of transport was gone when the screen was built again")
        }
    }

    @Test fun aTransportChosenIsStillChosenTheNextTimeTheAppOpens() {
        val source = Memory(stored())
        onTheBoard(models(source)) {
            onNodeWithText("REALITY").performClick()
            waitForIdle()
        }

        // A process later: other models, over what the first ones left stored.
        onTheBoard(models(source)) {
            assertTrue(REALITY_ROW in names(), "the transport chosen has no rows: ${names()}")
            assertFalse(XHTTP_ROW in names(), "the choice of transport did not outlive the process")
        }
    }

    @Test fun choosingAllAgainIsKeptToo() {
        val source = Memory(stored())
        onTheBoard(models(source)) {
            onNodeWithText("REALITY").performClick()
            waitForIdle()
            onNodeWithText("ALL").performClick()
            waitForIdle()
        }

        onTheBoard(models(source)) {
            assertTrue(REALITY_ROW in names() && XHTTP_ROW in names(), "the board did not go back to every transport")
        }
    }

    @Test fun theListIsWhereItWasScrolledToWhenTheScreenIsBuiltAgain() {
        onTheBoard(models(Memory(stored()))) { buildAgain ->
            fun whereTheLastRoomIs(): Float = laidTexts().first { it.text == LAST_ROOM }.node.positionInRoot.y

            val asItOpens = whereTheLastRoomIs()
            onNodeWithText(LAST_ROOM).performScrollTo()
            waitForIdle()
            val scrolledTo = whereTheLastRoomIs()
            assertTrue(scrolledTo < asItOpens - 100f, "the list did not scroll: $asItOpens, then $scrolledTo")

            buildAgain()

            assertEquals(scrolledTo, whereTheLastRoomIs(), 1f, "the list was back at its top when the screen was built again")
        }
    }

    private companion object {
        val REALITY_ROW = pkTied("ES · Madrid")
        const val XHTTP_ROW = "Netherlands-Amsterdam-03"

        /** The last room of our own list, a screen and a half down. */
        val LAST_ROOM = pkTied("US · olcRTC · WB")

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
                onboardingSeenAt = 1L
            )
        }
    }
}

private class Memory(var stored: LocationBundleV4) : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

/** A platform with no tunnel up and none asked for. */
private class NoTunnel : VpnManager {
    override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
    override val connectedSince = MutableStateFlow<Long?>(null)
    override val traffic: StateFlow<TrafficCounters?> = MutableStateFlow(null)
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
