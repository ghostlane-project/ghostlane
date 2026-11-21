package org.olcbox.app.vpn.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the room list of a running olcRTC client current, as the iPhone's tunnel does
 * (iosApp/PacketTunnel/RoomKeeper.swift): the subscription is asked again every
 * [PERIODIC_MS] and whenever the engine opens a session after the first (a handover or a
 * reconnect: the moment a retired room shows), at most once per [MIN_REFRESH_MS]; a failed
 * fetch is retried [RETRY_MS] later, again while it fails; a refusal keeps the rooms; a new list is handed
 * to the engine live, which reads it at its next hop, and remembered for the next start.
 * The app's own refresh reaches it too ([fromStore]), as the app's message does on iOS, and
 * the service asks once the line is verified ([refreshNow]), as the iPhone app does on Connected.
 *
 * The engine's watchdog and restart backoff are not here: the service restarts the
 * transport itself, and a restart makes a new keeper (the rooms carry over in the memory).
 *
 * One event loop: every state change happens on it, in order; the engine's thread only
 * queues ([onSessionOpened] never blocks). Rooms are logged as digests, never as ids,
 * and nothing logs the subscription's address, the key or the device id.
 */
class OlcrtcRoomKeeper(
    parent: CoroutineScope,
    private val carrier: String,
    private val keyHex: String,
    private val subscriptionUrl: String?,
    initial: OlcrtcRoomList.Parsed,
    /** Whether the first session of this start refreshes (iOS: only after its own restart). */
    private val refreshOnFirstSession: Boolean,
    private val io: Io,
    /** A monotonic clock in milliseconds. */
    private val nowMs: () -> Long,
) {
    interface Io {
        suspend fun fetch(url: String): RoomFetchOutcome

        /** Hands the list to the running engine; throws when the engine refuses it. */
        fun apply(rooms: OlcrtcRoomList.Parsed)

        /** Keeps the list for the next start; never throws. */
        fun remember(rooms: OlcrtcRoomList.Parsed)

        fun log(line: String)
    }

    companion object {
        const val MIN_REFRESH_MS = 15_000L
        const val PERIODIC_MS = 5 * 60_000L
        const val RETRY_MS = 30_000L
    }

    private sealed interface Event {
        data class Refresh(val reason: String, val force: Boolean = false) : Event
        data class Session(val room: String) : Event
        data class Fetched(val outcome: RoomFetchOutcome) : Event
        data class FromStore(val rooms: OlcrtcRoomList.Parsed, val carrier: String, val keyHex: String) : Event
    }

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val events = Channel<Event>(Channel.UNLIMITED)

    /** The list the engine was last handed (read on the loop; for tests and the service's log). */
    var rooms: OlcrtcRoomList.Parsed = initial
        private set

    private var sessions = 0
    private var lastRefreshAt: Long? = null
    private var fetching = false
    private var retry: Job? = null
    private var periodic: Job? = null

    /** Before the engine starts: the loop runs, so a session reported during the start is counted. */
    fun begin() {
        io.log("olcRTC room list at start (${rooms.all.size} rooms: ${OlcrtcRoomList.digests(rooms.all)})")
        if (subscriptionUrl == null) io.log("olcRTC room list: no subscription address, nothing to refresh")
        scope.launch {
            for (e in events) {
                // One bad event must not end the loop, nor reach the process's handler.
                try {
                    handle(e)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    fetching = false
                    io.log("olcRTC room list: ${x::class.simpleName} handling an event")
                }
            }
        }
    }

    /** Once the engine is ready (iOS's `armed`): the periodic refresh starts. */
    fun armed() {
        periodic?.cancel()
        periodic = scope.launch {
            while (true) {
                delay(PERIODIC_MS)
                events.trySend(Event.Refresh("periodic"))
            }
        }
    }

    /**
     * Ends the loop, the timers and a fetch in flight. The channel is left open: a coroutine on
     * another thread that queues after this finds it open (unlimited, so never a throw) and its
     * event is simply never read.
     */
    fun stop() {
        scope.cancel()
    }

    /** From the engine's session listener, on its thread. */
    fun onSessionOpened(room: String) {
        events.trySend(Event.Session(room))
    }

    /** Asks the subscription now (still at most once per [MIN_REFRESH_MS]); from any thread. */
    fun refreshNow(reason: String) {
        events.trySend(Event.Refresh(reason))
    }

    /** A list the app stored for this location (its own refresh), as iOS's app message. */
    fun fromStore(rooms: OlcrtcRoomList.Parsed, carrier: String, keyHex: String) {
        events.trySend(Event.FromStore(rooms, carrier, keyHex))
    }

    private fun handle(e: Event) {
        when (e) {
            is Event.Session -> {
                sessions++
                if (sessions > 1 || refreshOnFirstSession) {
                    refresh("session opened in room ${OlcrtcRoomList.digest(e.room)}")
                }
            }
            is Event.Refresh -> {
                if (e.force) lastRefreshAt = null
                refresh(e.reason)
            }
            is Event.Fetched -> fetched(e.outcome)
            is Event.FromStore ->
                if (OlcrtcRoomList.sameCarrier(e.carrier, carrier) && e.keyHex.trim().equals(keyHex.trim(), ignoreCase = true)) {
                    apply(e.rooms, "app")
                } else {
                    io.log("olcRTC room list from the app dropped: another carrier or key")
                }
        }
    }

    private fun refresh(reason: String) {
        if (fetching) return
        val url = subscriptionUrl ?: return
        val now = nowMs()
        lastRefreshAt?.let { if (now - it < MIN_REFRESH_MS) return }
        lastRefreshAt = now
        fetching = true
        io.log("olcRTC room list refresh ($reason)")
        scope.launch {
            val outcome = try {
                io.fetch(url)
            } catch (e: CancellationException) {
                // The keeper stopping: nothing more. A cancellation from inside the fetch while the
                // keeper runs is a failed fetch, or `fetching` would stay set and nothing refresh again.
                if (!isActive) throw e
                RoomFetchOutcome.Failed(0, e::class.simpleName)
            } catch (e: Exception) {
                RoomFetchOutcome.Failed(0, e::class.simpleName)
            }
            events.trySend(Event.Fetched(outcome))
        }
    }

    private fun fetched(outcome: RoomFetchOutcome) {
        fetching = false
        when (outcome) {
            is RoomFetchOutcome.Refused -> {
                // Not a hiccup to retry in 30 s: it holds until the provider frees a place. Asked
                // again at the next periodic refresh or session; a retry from a failure is dropped.
                retry?.cancel()
                retry = null
                val reason = outcome.refusal.reason?.let { ": $it" } ?: ""
                io.log("olcRTC room list refresh refused (${outcome.refusal.flag})$reason - rooms kept")
            }
            is RoomFetchOutcome.Failed -> {
                val cause = outcome.cause?.let { ": $it" } ?: ""
                io.log("olcRTC room list refresh failed (status ${outcome.status}$cause)")
                retry?.cancel()
                retry = scope.launch {
                    delay(RETRY_MS)
                    events.trySend(Event.Refresh("retry", force = true))
                }
            }
            is RoomFetchOutcome.Body -> {
                val parsed = OlcrtcRoomList.parse(outcome.text, keyHex, carrier)
                if (parsed == null) {
                    io.log("olcRTC room list refresh: the subscription has no line with this location's carrier and key")
                } else {
                    apply(parsed, "subscription")
                }
            }
        }
    }

    private fun apply(next: OlcrtcRoomList.Parsed, source: String) {
        if (next == rooms) {
            io.log("olcRTC room list unchanged (${next.all.size} rooms, $source)")
            return
        }
        try {
            io.apply(next)
        } catch (e: Exception) {
            io.log("olcRTC room list rejected by the engine: ${e::class.simpleName}")
            return
        }
        rooms = next
        io.remember(next)
        io.log("olcRTC room list refreshed from the $source (${next.all.size} rooms: ${OlcrtcRoomList.digests(next.all)}) - live, no restart")
    }
}
