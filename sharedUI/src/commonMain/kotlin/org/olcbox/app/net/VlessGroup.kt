package org.olcbox.app.net

import org.olcbox.app.data.model.ConnectionSelection
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.repository.LocationsRepository

/** A runtime group, never stored as another room or sent to a server. */
data class VlessGroup(val mode: ConnectionSelection, val members: List<LocationConfig>) {
    fun probePort(config: LocationConfig): Int? = members.indexOf(config.normalized())
        .takeIf { it >= 0 }?.let { PROBE_PORT + it }

    companion object {
        const val PROBE_PORT = 31000
        const val MAX_MEMBERS = 64
        fun from(bundle: LocationBundleV4, active: LocationConfig): VlessGroup? {
            if (bundle.settings.connectionSelection == ConnectionSelection.Manual || active.kind != LocationKind.Vless) return null
            val source = bundle.locations.firstOrNull {
                it.storageId == bundle.activeLocationId && it.location.normalized() == active.normalized()
            }?.subscriptionUrl?.takeIf { it.isNotBlank() } ?: return null
            val members = bundle.locations.filter { it.subscriptionUrl == source }
                .map { it.location.normalized() }
                .filter { it.kind == LocationKind.Vless && it.isComplete() }
                .distinct()
            require(members.size <= MAX_MEMBERS) {
                "This VLESS group has more than $MAX_MEMBERS servers. Use Manual or a smaller server list."
            }
            return members.takeIf { it.isNotEmpty() }?.let { VlessGroup(bundle.settings.connectionSelection, it) }
        }
    }
}

suspend fun LocationsRepository.vlessGroup(active: LocationConfig): VlessGroup? =
    VlessGroup.from(getBundle(), active)
