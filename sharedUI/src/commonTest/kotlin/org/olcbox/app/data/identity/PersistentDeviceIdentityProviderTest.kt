package org.olcbox.app.data.identity

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.model.LocationBundleV4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The install's id is what a list's provider counts a device by: it is made
 * once, when none is stored, and never in place of one that could not be read.
 */
class PersistentDeviceIdentityProviderTest {

    /** Stores the id in memory; [readFails] and [writeFails] stand for storage that will not answer. */
    private class IdStore(
        var stored: String? = null,
        var readFails: Boolean = false,
        var writeFails: Boolean = false
    ) : LocationsDataSource {
        var writes = 0
        override suspend fun loadLocationBundle(): LocationBundleV4? = null
        override suspend fun saveLocationBundle(bundle: LocationBundleV4) = Unit
        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()
        override suspend fun loadLegacyActiveLocationId(): String? = null
        override suspend fun loadDeviceIdentity(): String? {
            if (readFails) throw IllegalStateException("device_identity is there and could not be read")
            return stored
        }
        override suspend fun saveDeviceIdentity(value: String) {
            if (writeFails) throw IllegalStateException("device_identity could not be written")
            writes++
            stored = value
        }
    }

    @Test
    fun noIdStoredMakesOneOnceAndKeepsIt() = runTest {
        val store = IdStore()
        val provider = PersistentDeviceIdentityProvider(store)

        val first = assertNotNull(provider.hwid())
        assertTrue(Regex("install-[0-9a-f]{32}").matches(first), first)
        assertEquals(first, store.stored)
        assertEquals(first, provider.hwid())
        assertEquals(first, PersistentDeviceIdentityProvider(store).hwid(), "another start, the same device")
        assertEquals(1, store.writes)
    }

    // A transient read error must not cost the person a device place on every
    // list that counts them: no id this time, none made, the stored one kept.
    @Test
    fun anIdThatCannotBeReadIsNotReplaced() = runTest {
        val store = IdStore(stored = "install-0123456789abcdef0123456789abcdef", readFails = true)
        val provider = PersistentDeviceIdentityProvider(store)

        assertNull(provider.hwid())
        assertNull(provider.hwid())
        assertEquals(0, store.writes, "a new id was made in place of one that could not be read")
        assertEquals("install-0123456789abcdef0123456789abcdef", store.stored)

        store.readFails = false
        assertEquals("install-0123456789abcdef0123456789abcdef", provider.hwid(), "read again once it can be")
        assertEquals(0, store.writes)
    }

    // An id that could not be kept is not handed out: the next run would make
    // another, and each would be a device of its own.
    @Test
    fun anIdThatCannotBeStoredIsNotSent() = runTest {
        val store = IdStore(writeFails = true)
        val provider = PersistentDeviceIdentityProvider(store)

        assertNull(provider.hwid())
        assertNull(store.stored)

        store.writeFails = false
        val made = assertNotNull(provider.hwid())
        assertEquals(made, store.stored)
        assertEquals(made, provider.hwid())
    }
}
