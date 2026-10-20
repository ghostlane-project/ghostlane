package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmLocationsDataSourceImplTest {

    @Test
    fun storesLocationBundleInProvidedDirectory() = runTest {
        val dir = Files.createTempDirectory("olcbox-locations-test")
        val source = JvmLocationsDataSourceImpl(dir)
        val bundle = LocationBundleV4(
            activeLocationId = "desk",
            locations = listOf(
                LocationEntry.from(
                    "desk",
                    LocationConfig("Desktop", "room", "a".repeat(64), LocationConfig.PROVIDER_WB_STREAM)
                )
            )
        )

        source.saveLocationBundle(bundle)

        val loaded = source.loadLocationBundle()
        assertNotNull(loaded)
        assertEquals("desk", loaded.activeLocationId)
        assertEquals(LocationConfig.PROVIDER_WB_STREAM, loaded.locations.first().location.bypassProvider)
    }

    @Test
    fun storesDeviceIdentityInProvidedDirectory() = runTest {
        val dir = Files.createTempDirectory("olcbox-device-id-test")
        val source = JvmLocationsDataSourceImpl(dir)

        source.saveDeviceIdentity("install-test")

        assertEquals("install-test", source.loadDeviceIdentity())
    }

    // No file is no id, and the provider makes one. A file that is there and
    // cannot be read (here a folder in its place) is an error, never "no id":
    // a new id would be another device on every list that counts them.
    @Test
    fun aDeviceIdThatIsThereAndUnreadableIsAnErrorNotNone() = runTest {
        val dir = Files.createTempDirectory("olcbox-device-id-unreadable")
        val source = JvmLocationsDataSourceImpl(dir)
        assertNull(source.loadDeviceIdentity())

        Files.createDirectory(dir.resolve("device_identity"))
        assertFailsWith<java.io.IOException> { source.loadDeviceIdentity() }

        val provider = org.olcbox.app.data.identity.PersistentDeviceIdentityProvider(source)
        assertNull(provider.hwid(), "an unreadable id was replaced")
        assertTrue(Files.isDirectory(dir.resolve("device_identity")), "the place of the id was written over")
    }

    @Test
    fun keepsFailoverRoomsAcrossASaveAndLoad() = runTest {
        val dir = Files.createTempDirectory("olcbox-failover-rooms-test")
        val source = JvmLocationsDataSourceImpl(dir)
        val bundle = LocationBundleV4(
            activeLocationId = "desk",
            locations = listOf(
                LocationEntry.from(
                    "desk",
                    LocationConfig(
                        name = "Desktop",
                        id = "11115586048655",
                        key = "a".repeat(64),
                        bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                        failoverRoomIds = listOf("81055221156696")
                    )
                )
            )
        )

        source.saveLocationBundle(bundle)

        // The standby has to survive storage: it is the only room the client
        // can move to when the server retires the primary.
        val loaded = source.loadLocationBundle()
        assertNotNull(loaded)
        assertEquals(listOf("81055221156696"), loaded.locations.first().location.failoverRoomIds)
        assertEquals(
            listOf("11115586048655", "81055221156696"),
            loaded.locations.first().location.failoverRooms()
        )
    }
}
