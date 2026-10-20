@file:OptIn(ExperimentalForeignApi::class)

package org.olcbox.app.data.device

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo
import platform.posix.uname
import platform.posix.utsname

/**
 * The version as `UIDevice.systemVersion` writes it (`18.0`, `18.0.1`) and the
 * machine identifier (`iPhone15,2`), which names the model exactly.
 *
 * The version is read from NSProcessInfo and not from UIDevice: UIDevice is a
 * main-thread class (NS_SWIFT_UI_ACTOR in the SDK), and a list is fetched on
 * Dispatchers.IO. The numbers are the same.
 */
internal actual fun deviceDescription(): DeviceDescription = DeviceDescription(
    os = "iOS",
    osVersion = systemVersion(),
    model = machine()
)

private fun systemVersion(): String =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        val version = "$majorVersion.$minorVersion"
        if (patchVersion > 0) "$version.$patchVersion" else version
    }

/** `utsname.machine`; a simulator answers with its host's architecture. */
private fun machine(): String = memScoped {
    val system = alloc<utsname>()
    if (uname(system.ptr) == 0) system.machine.toKString() else ""
}
