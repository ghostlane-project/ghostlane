package org.olcbox.app.data.identity

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import org.olcbox.app.data.datasource.LocationsDataSource

interface DeviceIdentityProvider {
    /**
     * This install's id, or null when there is none to send now: the stored
     * one could not be read, or a new one could not be stored. Null is never
     * worked around with another id, which a list's provider would count as a
     * device of its own.
     */
    suspend fun hwid(): String?
}

class PersistentDeviceIdentityProvider(
    private val dataSource: LocationsDataSource
) : DeviceIdentityProvider {
    /**
     * The stored id; a new one, made and stored, only when none is stored.
     *
     * An id that is stored and cannot be read is not replaced: a read that
     * fails once (a file the system will not open for the moment, storage
     * that is busy) would otherwise cost the person a place on every list
     * that holds its devices to a number, for good. The request goes without
     * an id this time, and the stored one is read again at the next. A new id
     * is handed out only once it is stored, so the next run sends the same.
     */
    override suspend fun hwid(): String? {
        val stored = try {
            dataSource.loadDeviceIdentity()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (stored != null) return stored

        val generated = generateInstallId()
        return try {
            dataSource.saveDeviceIdentity(generated)
            generated
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun generateInstallId(): String {
        val random = Random.Default.nextBytes(16)
        return "install-" + random.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
