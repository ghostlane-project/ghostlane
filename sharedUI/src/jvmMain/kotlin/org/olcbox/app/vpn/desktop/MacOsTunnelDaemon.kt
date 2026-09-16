package org.olcbox.app.vpn.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs the root tunnel daemon, and reports where that request got to.
 *
 * macOS has no TUN without a process running as root, and since macOS 13 the
 * supported way to have one is a launchd daemon registered through
 * `SMAppService` — the plist ships inside the bundle, the user approves it once
 * in Login Items, and deleting the app removes it again.
 *
 * Absent is a normal state, not an error: every non-macOS desktop build has no
 * such library, and this object is reachable from shared code.
 */
object MacOsTunnelDaemon {

    /** Mirrors the Swift status codes; the numbers are the contract. */
    enum class Registration(val code: Int) {
        NotRegistered(0),
        RequiresApproval(1),
        Enabled(2),

        /** The plist is not in the bundle — a build made without the daemon. */
        NotFound(3),

        /** Not macOS, macOS older than 13, or a build without the bridge. */
        Unsupported(-1);

        companion object {
            /**
             * An unrecognised code becomes [NotRegistered], never [Enabled]: a
             * status this side does not understand is not evidence that a root
             * daemon is installed, and the only safe direction to round is down.
             */
            fun from(code: Int): Registration = entries.firstOrNull { it.code == code } ?: NotRegistered
        }
    }

    private interface Bridge : Library {
        fun olcbox_tunneld_status(): Int
        fun olcbox_tunneld_register(): Int
        fun olcbox_tunneld_unregister(): Int
        fun olcbox_tunneld_open_settings()
        fun olcbox_tunneld_message(buffer: ByteArray, capacity: Int): Int
    }

    private const val LIBRARY_RESOURCE = "native/libolcboxtunneld.dylib"
    private const val MESSAGE_CAPACITY = 1024

    /**
     * Extracted from resources and loaded by absolute path, the way the olcRTC
     * library already is.
     *
     * Not `Native.load("olcboxtunneld")`: that searches `jna.library.path`, which
     * this app points at `user.dir/native` — a directory that exists on a
     * developer's machine and not inside a packaged `.app`. A library found by
     * name in the one place and not the other fails by making the settings row
     * quietly not appear, with nothing anywhere saying why.
     */
    private val bridge: Bridge? by lazy {
        runCatching {
            val stream = MacOsTunnelDaemon::class.java.classLoader
                ?.getResourceAsStream(LIBRARY_RESOURCE)
                ?: return@runCatching null
            val temp = Files.createTempFile("olcboxtunneld-", ".dylib")
            temp.toFile().deleteOnExit()
            stream.use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
            Native.load(temp.toAbsolutePath().toString(), Bridge::class.java)
        }.getOrNull()
    }

    val available: Boolean get() = bridge != null

    fun register(): Registration =
        bridge?.let { Registration.from(it.olcbox_tunneld_register()) } ?: Registration.Unsupported

    fun unregister(): Registration =
        bridge?.let { Registration.from(it.olcbox_tunneld_unregister()) } ?: Registration.Unsupported

    fun status(): Registration =
        bridge?.let { Registration.from(it.olcbox_tunneld_status()) } ?: Registration.Unsupported

    fun openLoginItemsSettings() {
        bridge?.olcbox_tunneld_open_settings()
    }

    /**
     * One line for the settings row, or null on a platform with no such
     * component — which is every platform but macOS, and macOS builds made
     * before the daemon was embedded.
     *
     * The wording says what the person reading it has to do next, because for
     * most of these states that is the only useful thing to say: macOS asks the
     * user to approve the daemon, and an app that reports "not installed"
     * without mentioning where the approval lives has told them nothing.
     */
    fun settingsSummary(): String? {
        if (DesktopPaths.os != DesktopOs.MacOS) return null
        return when (status()) {
            Registration.Unsupported -> null
            Registration.NotRegistered -> "Not installed — tap to install"
            Registration.RequiresApproval -> "Approve in System Settings › General › Login Items"
            Registration.Enabled -> "Installed"
            Registration.NotFound -> "Missing from this build — reinstall Ghostlane"
        }
    }

    /** The last thing the native side had to say — an Apple error, verbatim. */
    fun message(): String {
        val lib = bridge ?: return "tunnel daemon bridge not present in this build"
        val buffer = ByteArray(MESSAGE_CAPACITY)
        val written = lib.olcbox_tunneld_message(buffer, MESSAGE_CAPACITY)
        return if (written <= 0) "" else String(buffer, 0, written, Charsets.UTF_8)
    }
}
