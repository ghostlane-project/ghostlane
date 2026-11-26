package org.olcbox.app.log

import androidx.lifecycle.viewModelScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

/** The head names a core only when the manager says its session ran the Rust core. */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsExportWiringTest {
    private val models = mutableListOf<HomeScreenViewModel>()

    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private class Fake(private val pin: String?, private val diag: String = "") : VpnManager {
        override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
        override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
        override val connectedSince = MutableStateFlow<Long?>(null)
        override fun needsPermission(): Boolean = false
        override fun startVpn() {}
        override fun stopVpn() {}
        override suspend fun ping(locationConfig: LocationConfig): Long? = null
        override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
        override suspend fun corePin(): String? = pin
        override suspend fun diagnosticsLog(): String = diag
    }

    @Test fun aManagerWithNoRustSessionLeavesTheCoreOutOfTheHead() {
        val out = runBlocking { LogsExport.build(Fake(null), listOf("one")) }
        assertFalse(out.lines().any { it.startsWith("Core:") }, out)
    }

    @Test fun aManagerThatRanRustNamesItsPin() {
        val out = runBlocking { LogsExport.build(Fake(CorePin.COMMIT), listOf("one")) }
        assertEquals("Core: ghostlane-core ${CorePin.COMMIT.take(12)}", out.lines()[1])
    }

    @Test fun theDefaultManagerRunsNoRustCore() {
        // The interface default, not an override, is what a manager that says nothing gets.
        val bare = object : VpnManager {
            override val logs: StateFlow<List<String>> = MutableStateFlow(emptyList())
            override val status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
            override val isConnected: StateFlow<Boolean> = MutableStateFlow(false)
            override val connectedSince = MutableStateFlow<Long?>(null)
            override fun needsPermission(): Boolean = false
            override fun startVpn() {}
            override fun stopVpn() {}
            override suspend fun ping(locationConfig: LocationConfig): Long? = null
            override suspend fun checkConnection(locationConfig: LocationConfig): Long? = null
        }
        assertTrue(runBlocking { bare.corePin() } == null)
    }

    /** What the user actually shares: the screen's call, not just the builder. */
    private fun screenExport(pin: String?): String {
        val shared = CompletableDeferred<String>()
        val model = HomeScreenViewModel(
            vpnManager = Fake(pin, "core: Rust core ready"),
            locationsRepository = LocationsRepositoryImpl(NoLocations),
            configImporter = NoImport,
            logExporter = object : LogExporter {
                override suspend fun writeLogs(target: Any, content: String): Result<String> = Result.success("")
                override suspend fun shareLogs(content: String): Result<String> {
                    shared.complete(content)
                    return Result.success("shared")
                }
            }
        )
        models += model
        model.onShareLogs()
        return runBlocking { shared.await() }
    }

    @Test fun theScreenSharesNoCoreWhenTheManagerRanNoRustCore() {
        val out = screenExport(null)
        assertFalse(out.lines().any { it.startsWith("Core:") }, out)
        assertTrue(out.contains("--- tunnel diagnostics ---"), out)
    }

    @Test fun theScreenSharesThePinTheManagerReports() {
        val out = screenExport(CorePin.COMMIT)
        assertEquals("Core: ghostlane-core ${CorePin.COMMIT.take(12)}", out.lines()[1])
    }
}

private object NoLocations : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = null
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) = Unit
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
    override suspend fun loadLegacyActiveLocationId(): String? = null
}

private object NoImport : ConfigImporter {
    override fun getFromClipboard(): String? = null
    override fun copyToClipboard(text: String) = Unit
    override suspend fun readTextFromSource(source: Any): String? = null
}
