package org.olcbox.app.vpn.desktop.engine.host

import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.net.XrayGeodata
import org.olcbox.app.net.core.CoreBlockedOnly
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The geodata directory of the engine's session file (`geodata_dir`), for
 * "only blocked sites": Re:filter's list exactly as the app bundles it
 * ([CoreBlockedOnly.FILE], held to its hash), written there when it is
 * missing or is not that file. The engine reads `ext:` lists from this
 * directory alone, and a list it cannot find there fails the start.
 */
internal object HostGeodata {
    /** The directory under the app's data directory. */
    const val DIRECTORY = "geodata"

    /** [refilterIn] for the app's own directory. */
    suspend fun refilterDir(): String = refilterIn(DesktopPaths.appDataDir().resolve(DIRECTORY))

    /** Puts the list in [dir] when it is not there as built, and returns [dir]'s path. */
    suspend fun refilterIn(dir: Path): String {
        val file = CoreBlockedOnly.FILE
        val target = dir.resolve(file.name)
        if (!Files.isRegularFile(target) || sha256(Files.readAllBytes(target)) != file.sha256) {
            val bytes = XrayGeodata.bytes(file)
            check(sha256(bytes) == file.sha256) { "the app's copy of ${file.name} is not the one it was built with" }
            Files.createDirectories(dir)
            val partial = dir.resolve("${file.name}.partial")
            Files.write(partial, bytes)
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return dir.toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
