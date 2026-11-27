package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Go cores an earlier desktop version staged beside the app's own files.
 *
 * The Linux AppImage used to copy sing-box, Xray and the olcRTC engine into
 * [DesktopPaths.appDataDir]'s `bin` directory on first use. This build starts
 * only ghostlane-engine, so those copies are dead weight at best: a person
 * could still run one by hand, and the old credentials in its config could
 * still open a SOCKS port. [sweep] takes them away and keeps the engine and
 * the tun's C helper. It is idempotent, and the launcher calls the same list
 * of names on the first start of a new install.
 */
internal object StaleBinarySweep {
    /** The files the old cores were copied as, whatever their name in the jar. */
    private val exactNames = setOf("sing-box", "sing-box.exe", "xray", "xray.exe", "olcrtc", "libolcrtc.so")

    /** Per-system olcRTC copies, including the library the Go builder made. */
    private val prefixes = listOf("olcrtc-linux-", "libolcrtc-linux-", "olcrtc-darwin-", "libolcrtc-darwin-", "olcrtc-windows-")

    /** Whether [name] is one of the staged Go cores, not the Rust engine or hev. */
    fun isStale(name: String): Boolean =
        name in exactNames || prefixes.any { name.startsWith(it) }

    /**
     * Removes the stale cores from [bin] and returns the names it removed.
     * A missing directory is a clean one: nothing was staged there yet.
     */
    fun sweep(bin: Path): List<String> {
        if (!Files.isDirectory(bin)) return emptyList()
        val removed = mutableListOf<String>()
        Files.newDirectoryStream(bin).use { entries ->
            for (path in entries) {
                val name = path.fileName.toString()
                if (Files.isRegularFile(path) && isStale(name) && Files.deleteIfExists(path)) {
                    removed += name
                }
            }
        }
        return removed.sorted()
    }

    /** Sweeps the bin directory of [appData], the app's own by default. */
    fun sweepAppData(appData: Path = DesktopPaths.appDataDir()): List<String> = sweep(appData.resolve("bin"))

    /** A command form, so the list can be asked about outside the app. */
    @JvmStatic
    fun main(args: Array<String>) {
        val bin = args.firstOrNull()?.let { Path.of(it) } ?: DesktopPaths.appDataDir().resolve("bin")
        sweep(bin).forEach(::println)
    }
}
