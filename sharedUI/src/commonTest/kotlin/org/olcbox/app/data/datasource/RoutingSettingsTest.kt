package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.SubscriptionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingSettingsTest {
    @Test fun defaultIsEverythingThroughTheTunnel() = runTest {
        val settings = LocationsRepositoryImpl(MemoryLocationsDataSource()).getRoutingSettings()
        assertEquals(RoutingMode.Global, settings.mode)
        assertTrue(settings.disableIpv6)
    }

    @Test fun savedModeComesBack() = runTest {
        val source = MemoryLocationsDataSource()
        val repository = LocationsRepositoryImpl(source)
        repository.saveRoutingSettings(RoutingSettings(RoutingMode.BypassRussia))
        assertEquals(RoutingMode.BypassRussia, repository.getRoutingSettings().mode)
        assertEquals(RoutingMode.BypassRussia, source.stored?.routing?.mode)
    }

    @Test fun theChoiceSurvivesAnEmptyLocationList() = runTest {
        // A fresh install has settings before it has a server list, and a device
        // that deletes its last list keeps its settings. The repository used to
        // rebuild an empty bundle from legacy storage and drop both.
        val source = MemoryLocationsDataSource()
        val repository = LocationsRepositoryImpl(source)
        repository.saveRoutingSettings(RoutingSettings(RoutingMode.BypassRussia))
        repository.saveSubscriptionSettings(SubscriptionSettings(autoUpdate = false))
        repository.acceptVpnDisclosure(atMillis = 1_000L)

        assertEquals(RoutingMode.BypassRussia, repository.getRoutingSettings().mode)
        assertEquals(false, repository.getSubscriptionSettings().autoUpdate)
        assertTrue(repository.isVpnDisclosureAccepted())
        assertTrue(source.stored?.locations.orEmpty().isEmpty())
    }

    @Test fun legacyLocationsAreAdoptedWithoutDroppingTheSettings() = runTest {
        val source = MemoryLocationsDataSource(
            stored = LocationBundleV4(routing = RoutingSettings(RoutingMode.BypassRussia)),
            legacy = listOf(
                "legacy_b" to """{"name":"B","server":"room-b","password":"key-b","turn":{"type":"wbstream"}}"""
            )
        )
        val repository = LocationsRepositoryImpl(source)
        val bundle = repository.getBundle()
        assertEquals(RoutingMode.BypassRussia, bundle.routing.mode)
        assertTrue(bundle.locations.isNotEmpty(), "the legacy location was adopted")
    }

    @Test fun aBundleWrittenBeforeRoutingExistedReadsAsGlobal() {
        val bundle = Json { ignoreUnknownKeys = true }
            .decodeFromString<LocationBundleV4>("""{"version":5,"locations":[]}""")
        assertEquals(RoutingMode.Global, bundle.routing.mode)
    }

    @Test fun serialNamesAreStable() {
        // Persisted on every platform; renaming a constant must not silently
        // reset everyone to Global.
        val json = Json.encodeToString(
            LocationBundleV4.serializer(),
            LocationBundleV4(routing = RoutingSettings(RoutingMode.BypassRussia))
        )
        assertTrue("\"routing\":{\"mode\":\"bypass_russia\"}" in json, json)
    }
}

/** The bundle in memory and nothing else; the repository's other collaborators keep their defaults. */
private class MemoryLocationsDataSource(
    var stored: LocationBundleV4? = null,
    private val legacy: List<Pair<String, String>> = emptyList()
) : LocationsDataSource {
    override suspend fun loadLocationBundle(): LocationBundleV4? = stored
    override suspend fun saveLocationBundle(bundle: LocationBundleV4) { stored = bundle }
    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = legacy
    override suspend fun loadLegacyActiveLocationId(): String? = null
}
