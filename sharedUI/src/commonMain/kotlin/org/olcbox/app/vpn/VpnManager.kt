package org.olcbox.app.vpn

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy

/** Bytes carried by the current session, as the platform counts them. */
data class TrafficCounters(val bytesIn: Long, val bytesOut: Long)

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    object Connecting : VpnStatus()
    object Connected : VpnStatus()
    object Reconnecting : VpnStatus()
    object Stopping : VpnStatus()
    data class Error(val message: String) : VpnStatus()
}

interface VpnManager {
    val logs: StateFlow<List<String>>
    val status: StateFlow<VpnStatus>
    val isConnected: StateFlow<Boolean>

    /**
     * When the current session came up, in epoch milliseconds, or null when
     * there is no session.
     *
     * A reconnect carries the value over rather than restarting it: rebuilding
     * the tunnel after a network handover is not a new session, and a timer
     * that resets every time the phone changes network says nothing useful.
     */
    val connectedSince: StateFlow<Long?>

    /**
     * Bytes carried this session, or null where the platform has no counter to
     * read. Null rather than zeroes: a pair of counters frozen at 0 looks like a
     * tunnel carrying nothing, which is a very different thing from a tunnel
     * nobody is measuring.
     */
    val traffic: StateFlow<TrafficCounters?>

    fun needsPermission(): Boolean

    /**
     * Whether [ping] can produce a real figure for this location right now.
     *
     * Asked before probing, because a probe with no way of succeeding does not
     * return "unknown" — it returns null, and the list drew null as **Offline**.
     * A working exit marked dead is worse than no figure at all.
     */
    fun canPing(locationConfig: LocationConfig): Boolean = locationConfig.isPingable()
    fun startVpn()
    fun stopVpn()
    suspend fun ping(locationConfig: LocationConfig): Long?
    suspend fun checkConnection(locationConfig: LocationConfig): Long?

    /** Measure the active channel without starting another engine or room. */
    suspend fun measureCurrentChannel(): Long? = null
    fun subscriptionFetchProxy(): SubscriptionFetchProxy? = null

    /**
     * What the platform's tunnel component wrote, for the exported log only.
     *
     * On iOS the tunnel is another process, and its files reached [logs] only
     * when a start had failed — a session that connected and then routed
     * wrongly exported nothing about itself. Empty where the tunnel runs
     * in-process and its lines already reach [logs].
     */
    fun diagnosticsLog(): String = ""
}
