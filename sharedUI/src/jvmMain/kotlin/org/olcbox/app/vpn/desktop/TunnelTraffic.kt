package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.TrafficCounters
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a desktop session's tunnel has carried, from whatever keeps count of it.
 *
 * The app keeps none. On a desktop the tunnel is another process's — hev's on
 * Linux and on a Mac, wintun's on Windows — and the lines behind it are
 * processes too. So the count is asked for where it is kept: of the kernel,
 * which counts what crosses the tun device. It counts the same thing a phone
 * does: what went through the tunnel interface.
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
