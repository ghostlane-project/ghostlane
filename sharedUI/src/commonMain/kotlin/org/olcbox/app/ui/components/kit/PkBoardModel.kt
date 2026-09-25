package org.olcbox.app.ui.components.kit

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.net.TransportKind
import org.olcbox.app.net.transportKind

/**
 * Everything the board decides that is not drawing.
 *
 * Kept pure and in one file because a redesign is exactly the kind of change that
 * can be wrong without looking wrong — a seat count off by one, a bar that fills
 * on the wrong side, an action button that offers to join a room that is full.
 * None of that is visible in a screenshot; all of it is visible in a test.
 */

// ── what a card says goes on the wire ──────────────────────────────────────

/**
 * The one line the selected card adds about itself: what this connection looks
 * like to anything watching it.
 *
 * This is the sentence the whole redesign exists to make room for. Every other
 * client lists a protocol name; the protocol name says nothing to the person
 * choosing, and "a TLS handshake to a real website" is the actual claim Reality
 * makes. Sentence case here, uppercased by the caller that wants mono caps.
 */
fun wireShape(config: LocationConfig?, words: BoardWords = BoardWords()): String {
    if (config == null) return words.wireUnknown
    return when (config.transportKind()) {
        TransportKind.Olcrtc -> words.wireOlcrtc.fill(config.providerName())
        TransportKind.Hysteria2 -> words.wireHysteria2
        TransportKind.Xhttp -> words.wireXhttp
        TransportKind.Grpc -> words.wireGrpc
        TransportKind.Reality -> words.wireReality
        TransportKind.Tls -> words.wireHttps
        TransportKind.Trojan -> words.wireHttps
        TransportKind.Vmess -> words.wireStream
        TransportKind.Shadowsocks -> words.wireStreamNoHandshake
    }
}

/**
 * The one word beside a card's name: `VP8`, `Reality`, `Salamander`.
 *
 * The word, not the sentence. The card used to print the whole of
 * `protocolLabels()` here — "Telemost · VP8" — which on a name like "United
 * States" left the name itself about nine characters and ellipsised it. What the
 * longer form adds is already in the wire line under the selected card.
 */
fun transportTag(config: LocationConfig?): String? {
    if (config == null) return null
    if (config.transportKind() == TransportKind.Olcrtc) return config.transportName()
    return config.protocolLabels().lastOrNull()?.takeIf { it.isNotBlank() }
}

// ── latency ────────────────────────────────────────────────────────────────

enum class PkPingState { Measuring, Measured, NoAnswer, Unmeasured }

data class PkPing(val label: String, val state: PkPingState)

/**
 * What the latency column says.
 *
 * A failed measurement used to be printed as **offline**, which is a claim about
 * the server made from a failure that is usually ours: a probe is timed through a
 * connection, and on the bad link that makes probes fail every server in the list
 * reads as dead. It said so even about the room the tunnel was running through,
 * which cannot be offline by definition — the traffic is going through it.
 *
 * So: [connectedHere] is proof of reachability and outranks a failed probe, and a
 * failure elsewhere is reported as what actually happened — no answer — rather
 * than as a verdict on the node.
 */
fun pingReading(
    pingMs: Int?,
    isMeasuring: Boolean,
    failed: Boolean,
    connectedHere: Boolean,
    words: BoardWords = BoardWords()
): PkPing = when {
    isMeasuring -> PkPing("···", PkPingState.Measuring)
    pingMs != null -> PkPing(words.pingMs.fill(pingMs), PkPingState.Measured)
    // Short because this column is beside the room's name and every dp it takes is
    // a dp the name loses on every card — "no answer" cost the title ten of them to
    // say a thing that appears rarely. It still reads as the probe, not the node.
    failed && !connectedHere -> PkPing(words.noPing, PkPingState.NoAnswer)
    else -> PkPing("—", PkPingState.Unmeasured)
}

// ── seats ──────────────────────────────────────────────────────────────────

enum class SeatState {
    /** Yours. The only seat drawn in lime. */
    Mine,

    /** Somebody else's. */
    Taken,

    Free
}

/**
 * How a node's occupancy is drawn.
 *
 * Individual seats are the point — a room that holds eight people is a fact no
 * percentage communicates — but they stop being readable somewhere past a dozen
 * pips on a phone, so a large node degrades to the proportion instead of drawing
 * a barcode.
 */
sealed interface SeatDisplay {
    data class Pips(val seats: List<SeatState>) : SeatDisplay

    /** [fraction] is 0f..1f. [mine] colours it lime, as a pip would be. */
    data class Bar(val fraction: Float, val mine: Boolean) : SeatDisplay

    /** Not an olcRTC node, or one that has not answered. Draws nothing at all. */
    data object None : SeatDisplay
}

/** Above this many seats, pips become a bar. */
internal const val MAX_SEAT_PIPS = 16

/**
 * `3 / 8`. Null where there are no seats to count.
 *
 * The server's own figure, unmodified. It briefly was not: `holds_slot` was
 * treated as proof that at least one seat was taken, which put a 1 on every empty
 * room in the list — see [seatDisplay] for why that field cannot carry that
 * weight.
 */
fun seatCountText(slots: OlcrtcSlots?): String? {
    if (slots == null || slots.slots_total <= 0) return null
    return "${slots.used} / ${slots.slots_total}"
}

/**
 * Seats as pips, or as a proportion where there are too many to count.
 *
 * [mine] is **the app's own connection state** — connected, to this location —
 * and not the server's `holds_slot`. That field is the answer to "does this key
 * occupy a slot", the key is derived from the location's room key, and a server
 * list issues one key across all its rooms; on top of that presence lingers for
 * five minutes after a session ends. So it came back true for rooms the user had
 * never joined, and every card in the list drew a lime seat and read 1 / 8 with
 * nobody in any of them.
 *
 * The app knows whether it is connected and to what. That is a local fact, it is
 * never wrong, and it is the only thing that should ever paint a seat as yours.
 *
 * A seat is painted yours only when the server has actually counted somebody: if
 * we have just joined and the count is still zero, the honest picture is an empty
 * room for one more poll, not a seat invented to match our own optimism.
 */
fun seatDisplay(slots: OlcrtcSlots?, mine: Boolean = false): SeatDisplay {
    if (slots == null || slots.slots_total <= 0) return SeatDisplay.None
    val total = slots.slots_total
    val used = slots.used
    if (total > MAX_SEAT_PIPS) {
        return SeatDisplay.Bar(
            fraction = (used.toFloat() / total).coerceIn(0f, 1f),
            mine = mine && used > 0
        )
    }
    return SeatDisplay.Pips(
        List(total) { index ->
            when {
                // Yours is drawn first so it is in the same place on every card,
                // rather than wherever the server happened to seat you.
                index == 0 && mine && used > 0 -> SeatState.Mine
                index < used -> SeatState.Taken
                else -> SeatState.Free
            }
        }
    )
}

/**
 * Whether this room will turn us away: no free seat, and we are not in it.
 *
 * [mine] is the app's own connection state, for the reason given on
 * [seatDisplay]. `OlcrtcSlots.isBlocked` answers the same question from
 * `holds_slot`, which is true for rooms we have never joined — harmless there
 * because it errs towards letting the tap through, but not something to build a
 * second control on.
 */
fun roomIsBlocked(slots: OlcrtcSlots?, mine: Boolean): Boolean =
    slots != null && slots.slots_free <= 0 && !mine

/** `7 free`, `full`, or nothing at all where there are no seats to count. */
fun seatFreeText(slots: OlcrtcSlots?, words: BoardWords = BoardWords()): String? {
    if (slots == null || slots.slots_total <= 0) return null
    return if (slots.slots_free <= 0) words.seatsFull else words.seatsFree.fill(slots.slots_free)
}

// ── occupancy history ──────────────────────────────────────────────────────

data class PkPoint(val x: Float, val y: Float)

/** How many occupancy samples a card's sparkline remembers. */
const val OCCUPANCY_HISTORY_LIMIT = 16

/**
 * A trace of how full a room has been, scaled into [width] x [height].
 *
 * A single sample draws a flat line at its level. That is not a fabricated
 * trend: the height of the line *is* the occupancy, and one reading is a true
 * statement about one moment. It used to draw nothing below two samples, which
 * meant every card was blank for the first three quarters of a minute after
 * launch — on a device that reads as broken, not as "nothing is known yet".
 *
 * y is inverted — a fuller room draws higher — and inset by a stroke's width at
 * each end so a line at 0 or 1 is not clipped by its own thickness.
 */
fun sparklinePoints(
    history: List<Float>,
    width: Float,
    height: Float,
    inset: Float = 1f
): List<PkPoint> {
    if (history.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val span = (height - inset * 2f).coerceAtLeast(0f)
    fun y(value: Float) = height - inset - value.coerceIn(0f, 1f) * span
    if (history.size == 1) {
        val level = y(history.single())
        return listOf(PkPoint(0f, level), PkPoint(width, level))
    }
    return history.mapIndexed { index, value ->
        PkPoint(x = width * index / (history.size - 1), y = y(value))
    }
}

/**
 * Appends a sample and drops the oldest beyond [OCCUPANCY_HISTORY_LIMIT].
 *
 * The server's figure, like the count beside it. The trace is a record of what
 * the node reported, and nothing the app believes about itself belongs in it.
 */
fun appendOccupancy(history: List<Float>?, slots: OlcrtcSlots): List<Float> {
    val value = if (slots.slots_total > 0) {
        (slots.used.toFloat() / slots.slots_total).coerceIn(0f, 1f)
    } else {
        0f
    }
    return ((history ?: emptyList()) + value).takeLast(OCCUPANCY_HISTORY_LIMIT)
}

// ── the live throughput trace ──────────────────────────────────────────────

/** How many one-second samples the status strip's trace remembers. */
const val THROUGHPUT_TRACE_LIMIT = 44

/**
 * The floor the trace scales against, per one-second sample.
 *
 * Scaling purely against the window's own peak would make any traffic at all fill
 * the height — a trickle of keepalives would draw the same mountain range as a
 * download, which is a lie told confidently. Below this the trace stays near the
 * floor and reads as "barely anything", which is what it is.
 */
const val THROUGHPUT_TRACE_FLOOR_BYTES = 96L * 1024L

/** Appends one sample of bytes-since-last-tick. Negative deltas read as zero. */
fun appendThroughput(history: List<Long>, deltaBytes: Long): List<Long> =
    (history + deltaBytes.coerceAtLeast(0L)).takeLast(THROUGHPUT_TRACE_LIMIT)

/**
 * Scales a throughput window into 0f..1f for drawing.
 *
 * Against the window's own peak, so the shape of the last minute is always
 * legible whatever the absolute rate — but never below
 * [THROUGHPUT_TRACE_FLOOR_BYTES], so an idle tunnel draws a flat line along the
 * bottom instead of a dramatic one made of nothing.
 */
fun throughputTrace(history: List<Long>): List<Float> {
    if (history.isEmpty()) return emptyList()
    val peak = maxOf(history.max(), THROUGHPUT_TRACE_FLOOR_BYTES)
    return history.map { (it.toDouble() / peak).coerceIn(0.0, 1.0).toFloat() }
}

// ── the action bar ─────────────────────────────────────────────────────────

enum class PkActionKind {
    /** Lime. Joining a room or connecting to a server. */
    Go,

    /** Red. Ending a live session. */
    Stop,

    /** Grey, with a spinner. */
    Busy,

    /** Grey, inert. Nothing here can be joined. */
    Blocked
}

data class PkAction(val label: String, val kind: PkActionKind)

/** Longest exit name the bar prints before it truncates. */
private const val ACTION_NAME_MAX = 18

/**
 * What the one button at the bottom says.
 *
 * It always names its object. "START" over a list of twelve servers is a button
 * that does not say what it will do, and a user who scrolled since selecting has
 * no way to check without scrolling back; "TAKE A SEAT IN NETHERLANDS" carries
 * the selection with it.
 *
 * [requiresSetup] is the caller's existing predicate, not a second opinion about
 * it — the bar must not offer to connect where the screen would refuse.
 */
fun boardAction(
    requiresSetup: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    selectedIsRoom: Boolean,
    selectedIsFull: Boolean,
    exitName: String?,
    words: BoardWords = BoardWords()
): PkAction {
    val name = exitName?.trim()?.takeIf { it.isNotEmpty() }?.let(::shortenExitName)?.uppercase()
    return when {
        requiresSetup -> PkAction(words.addServerList, PkActionKind.Go)
        isConnecting -> PkAction(words.cancel, PkActionKind.Busy)
        isConnected -> PkAction(
            name?.let { words.leave.fill(it) } ?: words.disconnect,
            PkActionKind.Stop
        )
        // Only blocks a room the user is not already in; the caller derives this
        // from roomIsBlocked, which asks the app rather than the server.
        selectedIsFull -> PkAction(words.roomFull, PkActionKind.Blocked)
        selectedIsRoom -> PkAction(
            name?.let { words.takeSeatIn.fill(it) } ?: words.takeSeat,
            PkActionKind.Go
        )
        else -> PkAction(
            name?.let { words.connectVia.fill(it) } ?: words.connect,
            PkActionKind.Go
        )
    }
}

/**
 * Trims an exit name to something a single-line button can hold.
 *
 * Cut at the last separator inside the budget where there is one, so
 * "Netherlands-Amsterdam-03" becomes "Netherlands" rather than "Netherlands-Amst…".
 */
internal fun shortenExitName(raw: String, max: Int = ACTION_NAME_MAX): String {
    val name = raw.trim()
    if (name.length <= max) return name
    val head = name.take(max)
    val cut = head.indexOfLast { it == ' ' || it == '-' || it == '·' || it == ',' }
    // A separator too near the start would leave a stub, which reads worse than
    // an ellipsis on a name that simply is one long word.
    //
    // [max] is the budget for the finished string, so the ellipsis comes out of
    // it rather than being added on top — the button has a width, not a
    // character count.
    return if (cut >= max / 2) head.take(cut).trimEnd() else name.take(max - 1).trimEnd() + "…"
}

// ── board head ─────────────────────────────────────────────────────────────

/**
 * `Rooms` where any of them is one, `Servers` where none is.
 *
 * The seat vocabulary is the app's own and it is worth using, but it is only true
 * of olcRTC. A list of nothing but Reality endpoints calling itself Rooms would
 * be the app describing itself as something it is not on that screen.
 */
fun boardHeading(hasRooms: Boolean, words: BoardWords = BoardWords()): String =
    if (hasRooms) words.rooms else words.servers

fun sortLabel(sort: SubscriptionSort, words: BoardWords = BoardWords()): String = when (sort) {
    SubscriptionSort.None -> words.sortAsServed
    SubscriptionSort.Ping -> words.sortPing
    SubscriptionSort.Alphabetical -> words.sortAlphabetical
}

fun nextSort(sort: SubscriptionSort): SubscriptionSort = when (sort) {
    SubscriptionSort.None -> SubscriptionSort.Ping
    SubscriptionSort.Ping -> SubscriptionSort.Alphabetical
    SubscriptionSort.Alphabetical -> SubscriptionSort.None
}

// ── the plan bar ───────────────────────────────────────────────────────────

private val QUOTA_UNITS = listOf(
    "TB" to 1024L * 1024 * 1024 * 1024,
    "GB" to 1024L * 1024 * 1024,
    "MB" to 1024L * 1024,
    "KB" to 1024L,
    "B" to 1L
)

/**
 * `"148.2 GB"` → bytes, or null where the provider sent something else.
 *
 * Providers write this header by hand and it shows: `148.2 GB`, `300GB`, `9.4 MiB`
 * and `1 TB` all appear. Binary and decimal spellings are read the same way — the
 * difference is 7% on a progress bar and no provider is consistent enough for the
 * distinction to mean anything.
 *
 * Null on anything unparseable, which is the whole point: the bar is drawn only
 * when both sides are understood, so a wrong bar is impossible rather than merely
 * unlikely.
 */
fun parseQuotaBytes(text: String?): Long? {
    val raw = text?.trim()?.uppercase()?.replace("IB", "B") ?: return null
    if (raw.isEmpty()) return null
    val unit = QUOTA_UNITS.firstOrNull { (suffix, _) -> raw.endsWith(suffix) } ?: return null
    val number = raw.dropLast(unit.first.length).trim().replace(',', '.')
    if (number.isEmpty()) return null
    val value = number.toDoubleOrNull() ?: return null
    if (value < 0 || value.isNaN() || value.isInfinite()) return null
    return (value * unit.second).toLong()
}

/**
 * How much of a plan is spent, 0f..1f, or null where no honest bar can be drawn.
 *
 * A plan of zero has no fraction — not 0%, not 100%. Drawing either would invent
 * a fact about an allowance the provider did not state.
 */
fun planFraction(used: String?, available: String?): Float? {
    val spent = parseQuotaBytes(used) ?: return null
    val total = parseQuotaBytes(available) ?: return null
    if (total <= 0L) return null
    return (spent.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

// ── words ──────────────────────────────────────────────────────────────────

/**
 * The words of the board's lines and its button. English by default, so the
 * helpers here stay pure and their tests read as the English screen does; the
 * board passes `boardWords()`, read from string resources. A pattern's `%1$s` /
 * `%1$d` is its one value, filled in by [fill].
 */
data class BoardWords(
    val wireUnknown: String = "an encrypted tunnel",
    val wireOlcrtc: String = "a %1\$s media session",
    val wireHysteria2: String = "obfuscated QUIC over UDP",
    val wireXhttp: String = "ordinary HTTP requests",
    val wireGrpc: String = "HTTP/2 gRPC requests",
    val wireReality: String = "a TLS handshake to a real website",
    val wireHttps: String = "ordinary HTTPS",
    val wireStream: String = "an encrypted stream",
    val wireStreamNoHandshake: String = "an encrypted stream with no handshake",
    val pingMs: String = "%1\$d ms",
    val noPing: String = "no ping",
    val seatsFull: String = "full",
    val seatsFree: String = "%1\$d free",
    val addServerList: String = "ADD SERVER LIST",
    val cancel: String = "CANCEL",
    val leave: String = "LEAVE %1\$s",
    val disconnect: String = "DISCONNECT",
    val roomFull: String = "ROOM IS FULL",
    val takeSeatIn: String = "TAKE A SEAT IN %1\$s",
    val takeSeat: String = "TAKE A SEAT",
    val connectVia: String = "CONNECT VIA %1\$s",
    val connect: String = "CONNECT",
    val rooms: String = "Rooms",
    val servers: String = "Servers",
    val sortAsServed: String = "AS SERVED",
    val sortPing: String = "PING",
    val sortAlphabetical: String = "A–Z"
)

/** A resource pattern with its values in place of `%1$s` / `%1$d`, `%2$s`, … */
internal fun String.fill(vararg values: Any): String =
    values.foldIndexed(this) { index, text, value ->
        text.replace("%${index + 1}\$s", value.toString()).replace("%${index + 1}\$d", value.toString())
    }
