package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.vpn.TrafficCounters
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a desktop session's tunnel has carried, from whatever keeps count of it.
 *
 * The app keeps none. On a desktop the tunnel is another process's — hev's on
 * Linux, a sing-box's on Windows and in the macOS daemon — and the lines behind
 * it are processes too. So the count is asked for where it is kept: of the
 * kernel on Linux, which counts what crosses the tun device, and of the tun's
 * sing-box elsewhere. Both count the same thing a phone does: what went through
 * the tunnel interface.
 *
 * The answers of one reader never go down. A session's totals take a count that
 * reads less than it did for a counter that started again
 * ([org.olcbox.app.ui.features.home.SessionTraffic]), which is right when the
 * tunnel was built again — a new reader — and would count the session twice if
 * a reader merely wavered.
 */
internal fun interface TunnelTraffic {
    /** The counters as they stand, or null when they could not be read. Never zeroes in place of an answer. */
    suspend fun read(): TrafficCounters?
}

/**
 * Linux: the tun device's own counters, which the kernel keeps and anybody may
 * read.
 *
 * What the kernel hands the device is on its way out, and what the device hands
 * the kernel is on its way in: `tx` is sent, `rx` received, as on any interface.
 * The lines' own traffic is not in either. Cores and engines leave beside the
 * tun there, by their user or by the interface they are bound to.
 */
internal class LinuxTunTraffic(
    device: String = LinuxTunController.TUN_NAME,
    interfaces: Path = Path.of("/sys/class/net")
) : TunnelTraffic {
    private val received = interfaces.resolve(device).resolve("statistics").resolve("rx_bytes")
    private val sent = interfaces.resolve(device).resolve("statistics").resolve("tx_bytes")

    override suspend fun read(): TrafficCounters? = withContext(Dispatchers.IO) {
        val bytesIn = count(received) ?: return@withContext null
        val bytesOut = count(sent) ?: return@withContext null
        TrafficCounters(bytesIn = bytesIn, bytesOut = bytesOut)
    }

    // No device, no file: the tunnel is gone, or not up yet.
    private fun count(file: Path): Long? = runCatching { Files.readString(file).trim().toLong() }.getOrNull()
}

/**
 * macOS: the tun is hev's, and the kernel counts what crosses it, as it does
 * for Linux. There is no sing-box behind an engine's tunnel to ask for flows,
 * so the utun's own link counters are the count: what it received is on its
 * way in, what it transmitted is on its way out. The lines behind it leave
 * beside the tun, in the helper's group or bound to the physical interface,
 * and are not in either.
 *
 * `netstat -ib` prints the link row of an interface with the byte counts the
 * kernel keeps. The header is read for the columns, so an extra column on a
 * newer Mac cannot shift a reading into the wrong one. A tun that is not up,
 * or an answer that cannot be read, is no reading and never a zero: a zero
 * here would say a tunnel that started again had carried nothing.
 */
internal class MacTunTraffic(
    private val interfaceName: String,
    private val readInterfaces: () -> String = ::readNetstatInterfaces,
) : TunnelTraffic {
    override suspend fun read(): TrafficCounters? = withContext(Dispatchers.IO) {
        counters(readInterfaces(), interfaceName)
    }

    internal companion object {
        internal fun counters(output: String, interfaceName: String): TrafficCounters? {
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.size < 2) return null
            val header = lines.first().split(WHITESPACE)
            // The link row has no address, so the cell under "Address" is
            // missing and the numbers shift left by one. Count the columns
            // from the first number the header names, not from the name.
            val columns = header.dropWhile { it in NON_NUMERIC_HEADERS }
            val received = columns.indexOf("Ibytes").takeIf { it >= 0 } ?: return null
            val sent = columns.indexOf("Obytes").takeIf { it >= 0 } ?: return null
            val row = lines.firstOrNull { line ->
                val cells = line.split(WHITESPACE)
                cells.firstOrNull() == interfaceName && cells.any { it.startsWith("<Link#") }
            }?.split(WHITESPACE) ?: return null
            val marker = row.indexOfFirst { it.startsWith("<Link#") }.takeIf { it >= 0 } ?: return null
            val numbers = row.drop(marker + 1)
            if (received >= numbers.size || sent >= numbers.size) return null
            val bytesIn = numbers[received].toLongOrNull() ?: return null
            val bytesOut = numbers[sent].toLongOrNull() ?: return null
            return TrafficCounters(bytesIn = bytesIn, bytesOut = bytesOut)
        }

        private val WHITESPACE = Regex("\\s+")
        private val NON_NUMERIC_HEADERS = setOf("Name", "Mtu", "Network", "Address")

        /** All of the machine's interfaces with their byte counts, as `netstat` writes them. */
        internal fun readNetstatInterfaces(): String = runCatching {
            val process = ProcessBuilder("netstat", "-ib").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }.getOrDefault("")
    }
}

/**
 * Windows, and a macOS tunnel that still runs sing-box: it keeps count for
 * whoever asks its local API with the secret it was started with
 * ([org.olcbox.app.net.SingBoxConfig.LocalApi]). An engine's tunnel on a Mac
 * is hev's and is counted from the interface ([MacTunTraffic]).
 */
internal class SingBoxTunTraffic(
    private val port: Int,
    private val secret: String,
    ownBinaries: Collection<String>,
    private val ask: suspend (port: Int, secret: String) -> String? = ::askSingBoxForItsFlows
) : TunnelTraffic {
    private val flows = SingBoxFlows(ownBinaries.toSet())

    override suspend fun read(): TrafficCounters? = ask(port, secret)?.let(flows::carried)
}

/**
 * What a tun's sing-box says it has carried, less what the session's own lines
 * put through it.
 *
 * Its totals are of everything it routed, and on Windows and macOS that is not
 * only the user's traffic. A line whose server the tun could not be told to
 * leave out — every olcRTC room, whose meeting service has no fixed address, and
 * a core whose server is named by hostname — reaches the internet through the
 * tun as well: its packets enter, the rule on its binary sends them straight
 * out, and they are counted. Counted with the traffic they carry, that is every
 * byte of a room's session twice.
 *
 * So the flows of those binaries are taken off. The API lists the flows that
 * are open, each with what it has moved; a line's are few and long — a room's is
 * one media stream for as long as it stands — so what each had moved when it was
 * last seen is what it moved, give or take its last second, and that much is
 * kept off the totals for good once the flow is gone.
 */
internal class SingBoxFlows(private val ownBinaries: Set<String>) {
    /** What each of the lines' open flows had moved at the last reading, by the flow's id. */
    private var open = emptyMap<String, Moved>()

    /** What their flows that have since closed had moved, all told. */
    private var closed = Moved(0L, 0L)
    private var totals = Moved(0L, 0L)
    private var answered = Moved(0L, 0L)

    private data class Moved(val received: Long, val sent: Long) {
        operator fun plus(other: Moved) = Moved(received + other.received, sent + other.sent)
    }

    /**
     * Takes one answer of `GET /connections` and says what the tunnel has
     * carried for the user by then; null for an answer that cannot be read.
     */
    fun carried(connections: String): TrafficCounters? {
        val snapshot = runCatching { json.decodeFromString(Snapshot.serializer(), connections) }.getOrNull() ?: return null
        val now = Moved(received = snapshot.downloadTotal, sent = snapshot.uploadTotal)
        if (now.received < totals.received || now.sent < totals.sent) {
            // Totals only grow while a sing-box lives. Less is another one, in
            // the same place, and nothing kept about the last one is about it.
            open = emptyMap()
            closed = Moved(0L, 0L)
            answered = Moved(0L, 0L)
        }
        totals = now

        val own = snapshot.connections.orEmpty()
            .filter { isALines(it.metadata.processPath) }
            .associate { it.id to Moved(received = it.download, sent = it.upload) }
        for ((id, moved) in open) if (id !in own) closed += moved
        open = own
        val theirs = own.values.fold(closed, Moved::plus)

        // Never less than was said before. The totals and each flow's count are
        // read one after another, not at one instant, so a flow that moved in
        // between can be ahead of the totals it is taken off by a packet or two.
        answered = Moved(
            received = maxOf(answered.received, now.received - theirs.received),
            sent = maxOf(answered.sent, now.sent - theirs.sent)
        )
        return TrafficCounters(bytesIn = answered.received, bytesOut = answered.sent)
    }

    /**
     * Whether [program], as the API names the program behind a flow, is one of
     * the lines' binaries.
     *
     * sing-box writes the path and after it, in brackets, who runs it:
     * `/…/sing-box (roman)`, `(501)` where the system has no name for them, and
     * the bare path where it knows nobody, which is Windows. It matches its own
     * rule against the path alone; compared whole, no flow here was ever a
     * line's, and a room's session was counted twice. Anything in one pair of
     * brackets at the end is taken for the user: a user's name may have
     * brackets of its own in it.
     */
    private fun isALines(program: String): Boolean = ownBinaries.any { binary ->
        program == binary ||
            (program.length > binary.length + 2 &&
                program.startsWith(binary) &&
                program.startsWith(" (", startIndex = binary.length) &&
                program.endsWith(")"))
    }

    @Serializable
    private class Snapshot(
        val downloadTotal: Long = 0L,
        val uploadTotal: Long = 0L,
        val connections: List<Flow>? = null
    )

    @Serializable
    private class Flow(
        val id: String = "",
        val download: Long = 0L,
        val upload: Long = 0L,
        val metadata: Metadata = Metadata()
    )

    @Serializable
    private class Metadata(val processPath: String = "")

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * `GET /connections` of the sing-box listening on [port], or null: nobody
 * there, the wrong secret, or an answer that did not come in time.
 *
 * Straight to the loopback, whatever proxy the system has been set to, and on
 * `java.base` alone: the app's runtime image is cut down to the modules it
 * names, and an HTTP client from another one is a class missing only from the
 * build that ships.
 */
internal suspend fun askSingBoxForItsFlows(port: Int, secret: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI("http://127.0.0.1:$port/connections").toURL()
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = API_CONNECT_TIMEOUT_MS
        connection.readTimeout = API_READ_TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $secret")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.errorStream?.close()
            return@runCatching null
        }
        connection.inputStream.use { body ->
            val bytes = body.readNBytes(API_ANSWER_MOST_BYTES + 1)
            // Longer than any list of flows a desktop has open: not an answer to read.
            if (bytes.size > API_ANSWER_MOST_BYTES) null else bytes.toString(Charsets.UTF_8)
        }
    }.getOrNull()
}

/** The API is on the loopback and answers from memory: if it has not in this long, it will not this second. */
private const val API_CONNECT_TIMEOUT_MS = 300
private const val API_READ_TIMEOUT_MS = 700

/** A flow is some four hundred bytes of the answer; this is tens of thousands of them. */
private const val API_ANSWER_MOST_BYTES = 16 * 1024 * 1024
