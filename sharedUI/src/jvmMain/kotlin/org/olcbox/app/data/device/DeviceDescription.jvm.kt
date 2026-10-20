package org.olcbox.app.data.device

import java.util.concurrent.TimeUnit

/**
 * The system the desktop app runs on, its version as the JVM reads it and, on
 * a Mac, the model the machine reports (`Mac14,2`). Windows and Linux offer a
 * program no model worth asking for, so they say `PC`.
 *
 * Read once: the Mac's model is a process to start, and none of it changes
 * while the app runs.
 */
internal actual fun deviceDescription(): DeviceDescription = desktopDevice

private val desktopDevice: DeviceDescription by lazy {
    val os = desktopOs(System.getProperty("os.name").orEmpty())
    DeviceDescription(
        os = os,
        osVersion = System.getProperty("os.version").orEmpty(),
        model = (if (os == "macOS") macModel() else null) ?: "PC"
    )
}

/**
 * `os.name` as `x-device-os` says it: `macOS`, `Windows`, and `Linux` for
 * anything else. By its start, not by what it holds: "darwin" holds "win".
 */
internal fun desktopOs(osName: String): String {
    val name = osName.trim().lowercase()
    return when {
        name.startsWith("mac") || name.startsWith("darwin") -> "macOS"
        name.startsWith("windows") -> "Windows"
        else -> "Linux"
    }
}

/** `sysctl -n hw.model`, or null when it fails or has not answered within [MODEL_TIMEOUT_MS]. */
private fun macModel(): String? = runCatching {
    val process = ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.model")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    try {
        if (process.waitFor(MODEL_TIMEOUT_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } else {
            null
        }
    } finally {
        process.destroyForcibly()
    }
}.getOrNull()?.takeIf { it.isNotEmpty() }

private const val MODEL_TIMEOUT_MS = 2_000L
