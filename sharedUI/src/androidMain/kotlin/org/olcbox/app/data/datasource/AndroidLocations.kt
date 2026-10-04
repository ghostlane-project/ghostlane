package org.olcbox.app.data.datasource

import android.content.Context
import org.olcbox.app.data.repository.LocationsRepository

/**
 * The one location repository of the process.
 *
 * The app and the VPN service each built their own, and a repository's lock is
 * its own: two of them are two writers of one file, each saving the bundle as
 * it read it. A server list refreshed by the app while the service wrote a
 * setting would lose one of the two. With one repository every change waits
 * its turn.
 */
object AndroidLocations {
    @Volatile
    private var instance: LocationsRepository? = null

    fun repository(context: Context): LocationsRepository =
        instance ?: synchronized(this) {
            instance ?: LocationsRepositoryImpl(LocationsDataSourceImpl(context.applicationContext))
                .also { instance = it }
        }
}
