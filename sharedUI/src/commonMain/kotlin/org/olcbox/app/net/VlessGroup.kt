package org.olcbox.app.net

import org.olcbox.app.data.model.ConnectionSelection
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.repository.LocationsRepository

/** A runtime group, never stored as another room or sent to a server. */
data class VlessGroup(
    val mode: ConnectionSelection,
    val members: List<LocationConfig>,
    val fallbackIndex: Int = 0,
) {
    fun probePort(config: LocationConfig): Int? {
        if (config.kind != LocationKind.Vless) return null
        return members.indexOfFirst { it.connectionKey() == config.connectionKey() }
            .takeIf { it >= 0 }?.let { PROBE_PORT + it }
    }

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
                .distinctBy { it.connectionKey() }
            require(members.size <= MAX_MEMBERS) {
                "This VLESS group has more than $MAX_MEMBERS servers. Use Manual or a smaller server list."
            }
            return members.takeIf { it.isNotEmpty() }?.let { VlessGroup(bundle.settings.connectionSelection, it, it.indexOfFirst { member -> member.connectionKey() == active.connectionKey() }) }
        }
    }
}

suspend fun LocationsRepository.vlessGroup(active: LocationConfig): VlessGroup? =
    VlessGroup.from(getBundle(), active)

/** Names and URI fragments do not create another outbound to the same server. */
private fun LocationConfig.connectionKey(): String? = rawLink?.trim()?.substringBefore('#')
