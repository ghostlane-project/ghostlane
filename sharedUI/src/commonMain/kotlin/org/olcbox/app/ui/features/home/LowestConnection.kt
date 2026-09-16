package org.olcbox.app.ui.features.home

import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.net.LocationKind

/**
 * App-side selection only: one normal platform connection at a time. The owner
 * cancels this coroutine before a manual selection/stop. No core config changes.
 */
internal class LowestConnection(
    private val vpn: VpnManager,
    private val repository: LocationsRepository,
    private val onSelected: suspend () -> Unit
) {
    suspend fun run() {
        val selected = repository.getActiveLocation() ?: return
        val subscription = selected.subscriptionUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val candidates = repository.getAllLocations().filter {
            it.subscriptionUrl?.trim() == subscription && it.location.isComplete() && TransportMismatch.explanation(it) == null
        }.sortedBy { if (it.storageId == selected.storageId) 0 else 1 }
            .distinctBy { connectionKey(it) }
        if (candidates.isEmpty()) return

        // ICMP/TCP address probes only make sense outside our own tunnel on
        // iOS. Wait for the old tunnel to stop before ranking any destination.
        if (!stopAndWait()) return
        var expectedId = selected.storageId
        val ranked = rank(candidates, selected.storageId)
        var attempts = 0
        for (candidate in ranked) {
            currentCoroutineContext().ensureActive()
            if (repository.getActiveLocationId() != expectedId) return
            if (!repository.getSubscriptionSettings().autoSelectLowest) return
            val fresh = repository.getAllLocations().firstOrNull { it.storageId == candidate.storageId }
            // Never resurrect a deleted/replaced entry from the ranking snapshot.
            if (fresh == null || fresh.subscriptionUrl?.trim() != subscription ||
                connectionKey(fresh) != connectionKey(candidate)) continue
            if (attempts++ >= MAX_ATTEMPTS) break
            if (attempts > 1) {
                delay(RETRY_DELAY_MS)
                currentCoroutineContext().ensureActive()
                if (repository.getActiveLocationId() != expectedId) return
                if (!repository.getSubscriptionSettings().autoSelectLowest) return
            }
            currentCoroutineContext().ensureActive()
            // A VPN started from system settings during our wait wins too.
            if (vpn.status.value !is VpnStatus.Disconnected) return
            // Recheck after cooldown: a subscription may have refreshed meanwhile.
            val current = repository.getAllLocations().firstOrNull { it.storageId == fresh.storageId }
            if (current == null || TransportMismatch.explanation(current) != null ||
                connectionKey(current) != connectionKey(fresh) ||
                current.subscriptionUrl?.trim() != subscription) continue
            repository.setActiveLocationId(current.storageId)
            expectedId = current.storageId
            onSelected()
            currentCoroutineContext().ensureActive()
            vpn.startVpn()
            val established = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                // startVpn may schedule its work. Ignore the initial idle value,
                // but honor a later Disconnected/Stopping (e.g. an OS stop).
                vpn.status.dropWhile { it is VpnStatus.Disconnected }.first {
                    it is VpnStatus.Connected || it is VpnStatus.Error ||
                        it is VpnStatus.Disconnected || it is VpnStatus.Stopping
                }
            }
            if (established is VpnStatus.Disconnected || established is VpnStatus.Stopping) return
            if (established is VpnStatus.Connected) {
                // Let platform reconnect logic handle migrations. Only a terminal
                // Error moves to another exit; Stop/Disconnected respect the user.
                val ended = vpn.status.first {
                    it is VpnStatus.Error || it is VpnStatus.Disconnected || it is VpnStatus.Stopping
                }
                if (ended !is VpnStatus.Error) return
            }
            if (!stopAndWait()) return
        }
        throw IllegalStateException("Lowest could not connect. Tried up to $MAX_ATTEMPTS servers; select a server manually or retry.")
    }

    private suspend fun stopAndWait(): Boolean {
        if (vpn.status.value is VpnStatus.Disconnected) return true
        vpn.stopVpn()
        return withTimeoutOrNull(STOP_TIMEOUT_MS) {
            vpn.status.first { it is VpnStatus.Disconnected }
            true
        } ?: throw IllegalStateException("The previous VPN is still stopping. Retry once it disconnects.")
    }

    internal suspend fun rank(entries: List<LocationEntry>, selectedId: String): List<LocationEntry> {
        val times = mutableMapOf<String, Long>()
        val lock = Mutex()
        val semaphore = Semaphore(3)
        // A large subscription must not delay Connect indefinitely or create a
        // coroutine per provider entry. Unmeasured entries remain fallbacks.
        withTimeoutOrNull(RANK_TIMEOUT_MS) {
            coroutineScope {
                entries.filter { it.location.kind != LocationKind.Olcrtc }
                    .take(MAX_PROBES).map { entry -> async {
                        semaphore.withPermit {
                            val result = try {
                                if (vpn.canPing(entry.location)) vpn.ping(entry.location) else null
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                            if (result != null && result >= 0) lock.withLock { times[entry.storageId] = result }
                        }
                    } }.awaitAll()
            }
        }
        // An unanswered echo does not prove the VPN is blocked. Keep the user's
        // selection first among unknown entries, then preserve provider order.
        return entries.sortedWith(compareBy<LocationEntry> { times[it.storageId] ?: Long.MAX_VALUE }
            .thenBy { if (it.storageId == selectedId) 0 else 1 })
    }

    private fun connectionKey(entry: LocationEntry): String {
        val config = entry.location.normalized()
        return if (config.rawLink != null) config.kind.toString() + ":" + config.rawLink.substringBefore('#')
        else config.copy(name = "").toString()
    }

    private companion object {
        const val MAX_PROBES = 32
        const val MAX_ATTEMPTS = 3
        const val RANK_TIMEOUT_MS = 6_000L
        const val CONNECT_TIMEOUT_MS = 90_000L
        const val STOP_TIMEOUT_MS = 10_000L
        const val RETRY_DELAY_MS = 15_000L
    }
}
