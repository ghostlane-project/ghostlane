package org.olcbox.app.vpn.desktop.engine.host

import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path

/**
 * Where ghostlane-engine is found, and what it says of itself.
 *
 * The program is the Core build's, at the app's one pin of the core: the
 * build bundles it as the resource `native/ghostlane-engine` (`.exe` on
 * Windows), and at run time it is copied into the app's own `bin` directory,
 * only when its bytes differ (Windows locks a program while it runs, and a
 * probe may start one beside the session's). [ENV] names another file in
 * its place, for a developer's build and for the checks that run the Core
 * build's program as it is.
 */
internal object HostAssets {
    /** A path to the program to run instead of the bundled one. */
    const val ENV = "GHOSTLANE_ENGINE_BINARY"

    /** The protocol of the session file and the events this app writes and reads. */
    const val PROTOCOL = 1

    /** The program's file name on [os]. */
    fun fileName(os: DesktopOs = DesktopPaths.os): String =
        if (os == DesktopOs.Windows) "ghostlane-engine.exe" else "ghostlane-engine"

    /** The resource the build bundles the program as. */
    fun resourceName(os: DesktopOs = DesktopPaths.os): String = "native/${fileName(os)}"

    /**
     * The program to run: [ENV]'s file when it is set, else the bundled one
     * copied out. Throws IllegalStateException with a sentence when there is
     * none: the build carries no engine.
     */
    fun resolve(): Path {
        System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let { named ->
            val path = Path(named)
            check(Files.isRegularFile(path)) { "$ENV names no file: $named" }
            return path
        }
        val resource = resourceName()
        val bytes = javaClass.classLoader.getResourceAsStream(resource)
            ?: error("this build carries no ghostlane-engine ($resource)")
        val target = DesktopPaths.appDataDir().resolve("bin").resolve(fileName())
        Files.createDirectories(target.parent)
        val staged = Files.createTempFile(target.parent, "engine-", ".tmp")
        try {
            bytes.use { Files.copy(it, staged, StandardCopyOption.REPLACE_EXISTING) }
            if (!Files.exists(target) || Files.mismatch(staged, target) != -1L) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
        target.toFile().setExecutable(true, true)
        return target
    }

    /**
     * What [binary] says of itself (`ghostlane-engine version`), or null when
     * it printed no `version` line within [timeoutMs].
     */
    fun version(binary: Path, timeoutMs: Long = 10_000L): HostEvent.Version? =
        runCatching { HostProcess.ask(binary, listOf("version"), timeoutMs) }
            .getOrDefault(emptyList())
            .filterIsInstance<HostEvent.Version>()
            .firstOrNull()

    /** Why this app cannot run [version]'s program, or null when it can. */
    fun refusal(version: HostEvent.Version): String? =
        if (version.protocol != PROTOCOL) {
            "ghostlane-engine reads protocol ${version.protocol}; this app writes protocol $PROTOCOL"
        } else {
            null
        }

    /** [version] in the words of the app's log: the pin first, as the log's head names it. */
    fun describe(version: HostEvent.Version): String =
        "ghostlane-engine ${version.engine} at ${version.pin.take(12)} (${version.target}; " +
            (if (version.rooms) "rooms" else "no rooms") + ", " +
            (if (version.boringssl) "BoringSSL" else "no BoringSSL") + ")"
}
