@file:OptIn(ExperimentalForeignApi::class)

package org.olcbox.app.data.device

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.darwin.sysctlbyname
import platform.posix.size_tVar

/**
 * As on iOS, but a Mac's model is `hw.model` (`Mac14,2`): its `utsname.machine`
 * is only the architecture.
 */
internal actual fun deviceDescription(): DeviceDescription = DeviceDescription(
    os = "macOS",
    osVersion = systemVersion(),
    model = sysctlText("hw.model")
)

private fun systemVersion(): String =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        val version = "$majorVersion.$minorVersion"
        if (patchVersion > 0) "$version.$patchVersion" else version
    }

/** A string sysctl, or empty when the system does not answer it. */
private fun sysctlText(name: String): String = memScoped {
    val size = alloc<size_tVar>()
    if (sysctlbyname(name, null, size.ptr, null, 0u) != 0 || size.value == 0uL) return@memScoped ""
    val buffer = allocArray<ByteVar>(size.value.toInt())
    if (sysctlbyname(name, buffer, size.ptr, null, 0u) != 0) return@memScoped ""
    buffer.toKString()
}
