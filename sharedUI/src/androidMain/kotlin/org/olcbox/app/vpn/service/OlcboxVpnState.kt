package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.log.LogScrubber

object OlcboxVpnState {
    @Volatile var channelProxy: org.olcbox.app.data.repository.SubscriptionFetchProxy? = null
    @Volatile var activeGroup: org.olcbox.app.net.VlessGroup? = null
    @Volatile var activeLocation: org.olcbox.app.data.model.LocationConfig? = null

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince = _connectedSince.asStateFlow()

    private val _traffic = MutableStateFlow<TrafficCounters?>(null)
    val traffic = _traffic.asStateFlow()

    fun setTraffic(bytesIn: Long, bytesOut: Long) {
        _traffic.value = TrafficCounters(bytesIn = bytesIn, bytesOut = bytesOut)
    }

    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        // A session's counters belong to that session; carrying them into the
        // next one would show yesterday's gigabytes on a fresh connection.
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) {
            _traffic.value = null
        }
        _connectedSince.value = when (status) {
            // Only the first Connected of a session stamps the clock; a
            // reconnect passes through Reconnecting and back, and must not
            // restart it.
            VpnStatus.Connected -> _connectedSince.value ?: nowMillis()
            VpnStatus.Reconnecting -> _connectedSince.value
            else -> null
        }
    }

    fun addLog(msg: String) {
        // Scrubbed once, at the top: logcat and a captured bug report are exports too.
        val safe = LogScrubber.default.scrub(msg)
        Log.d(TAG, safe)
        _logs.update { (it + safe).takeLast(MAX_LOG_ENTRIES) }
    }

    private const val MAX_LOG_ENTRIES = 1_000
    private const val TAG = "OlcboxVpnService"
}
