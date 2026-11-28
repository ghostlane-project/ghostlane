package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths

/** Where system traffic should be sent, in both forms the platforms need. */
internal data class DesktopProxyTarget(
    val pacUrl: String,
    val socksHost: String,
    val socksPort: Int,
    val username: String = "",
    val password: String = ""
)

internal interface DesktopProxyController {
    suspend fun enable(target: DesktopProxyTarget)
    suspend fun restore()

    /**
     * Puts back what an earlier run that was killed left changed. Called when
     * the app starts; a controller with no journal has nothing to do.
     */
    suspend fun recover() {}

    companion object {
        fun current(): DesktopProxyController {
            return when (DesktopPaths.os) {
                DesktopOs.MacOS -> MacOsProxyController()
                DesktopOs.Windows -> WindowsProxyController()
                DesktopOs.Linux -> UnsupportedProxyController()
                DesktopOs.Other -> UnsupportedProxyController()
            }
        }
    }
}

internal class UnsupportedProxyController : DesktopProxyController {
    override suspend fun enable(target: DesktopProxyTarget) {
        error("System proxy mode supports macOS and Windows")
    }

    override suspend fun restore() = Unit
}

internal data class MacOsAutoProxyState(
    val service: String,
    val enabled: Boolean,
    val url: String?
)

internal class MacOsProxyController : DesktopProxyController {
    private var backup: List<MacOsAutoProxyState>? = null

    /**
     * Configures the native SOCKS proxy rather than a PAC URL.
     *
     * A PAC was installed correctly (scutil confirmed it) and the tunnel carried
     * traffic when dialled directly, yet the browser still went out untunnelled:
     * CFNetwork reads the first proxy token in the PAC result and gives up on one it
     * does not know, and "SOCKS5" is a Chrome/Firefox extension. Setting the SOCKS
     * proxy directly removes that parsing step from the path entirely — the setting
     * is unambiguous and takes credentials natively.
     */
    override suspend fun enable(target: DesktopProxyTarget) {
        val services = enabledNetworkServices()
        backup = services.map { service ->
            readAutoProxyState(service)
        }
        enableCommands(services, target).forEach { runCommand(it) }
    }

    override suspend fun restore() {
        val states = backup ?: return
        restoreCommands(states).forEach { command ->
            runCatching { runCommand(command) }
        }
        backup = null
    }

    private suspend fun enabledNetworkServices(): List<String> {
        return runCommand(listOf("networksetup", "-listallnetworkservices"))
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("An asterisk") && !it.startsWith("*") }
            .toList()
    }

    private suspend fun readAutoProxyState(service: String): MacOsAutoProxyState {
        val output = runCommand(listOf("networksetup", "-getautoproxyurl", service))
        val enabled = output.lineSequence()
            .firstOrNull { it.startsWith("Enabled:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.equals("Yes", ignoreCase = true) == true
        val url = output.lineSequence()
            .firstOrNull { it.startsWith("URL:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "(null)" }
        return MacOsAutoProxyState(service, enabled, url)
    }

    companion object {
        /**
         * Sets the native SOCKS proxy per service. The PAC is deliberately left off:
         * having both set makes it ambiguous which one macOS honours, and the PAC is
         * the one that failed.
         */
        fun enableCommands(services: List<String>, target: DesktopProxyTarget): List<List<String>> {
            return services.flatMap { service ->
                val set = mutableListOf(
                    "networksetup", "-setsocksfirewallproxy", service,
                    target.socksHost, target.socksPort.toString()
                )
                // "off" said out loud: without it macOS keeps the authenticated
                // flag and the credentials of the last proxy that had any, and a
                // front that takes none is then greeted with somebody else's login.
                set += if (target.username.isNotBlank()) {
                    listOf("on", target.username, target.password)
                } else {
                    listOf("off")
                }
                listOf(
                    set.toList(),
                    listOf("networksetup", "-setsocksfirewallproxystate", service, "on"),
                    listOf("networksetup", "-setautoproxystate", service, "off")
                )
            }
        }

        /** Turns our SOCKS proxy back off and puts any pre-existing PAC back as it was. */
        fun restoreCommands(states: List<MacOsAutoProxyState>): List<List<String>> {
            return states.flatMap { state ->
                val off = listOf(
                    listOf("networksetup", "-setsocksfirewallproxystate", state.service, "off")
                )
                off + if (state.enabled && !state.url.isNullOrBlank()) {
                    listOf(
                        listOf("networksetup", "-setautoproxyurl", state.service, state.url),
                        listOf("networksetup", "-setautoproxystate", state.service, "on")
                    )
                } else {
                    listOf(listOf("networksetup", "-setautoproxystate", state.service, "off"))
                }
            }
        }
    }
}

internal data class WindowsProxyState(
    val proxyEnable: String?,
    val proxyServer: String?,
    val proxyOverride: String?,
    val autoConfigUrl: String?
)

/**
 * The system proxy on Windows is [WindowsSystemProxy], which keeps a journal of
 * the values it changed in the app's data directory: a run that is killed
 * leaves them to [recover] at the next start. The commands of the companion
 * are the ones its tests have always read.
 */
internal class WindowsProxyController(
    private val proxy: WindowsSystemProxy = WindowsSystemProxy().also { it.journal = defaultJournal() },
) : DesktopProxyController {
    // Windows keeps the PAC path: WinINET parses the result list properly and falls
    // through unknown tokens, so it was never affected by the macOS problem.
    override suspend fun enable(target: DesktopProxyTarget) = proxy.enable(target.pacUrl)

    override suspend fun restore() = proxy.restore()

    override suspend fun recover() {
        proxy.recover()
    }

    companion object {
        internal fun defaultJournal(): java.nio.file.Path =
            org.olcbox.app.desktop.DesktopPaths.appDataDir().resolve("windows-proxy.json")

        private const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

        fun enableCommands(pacUrl: String): List<List<String>> {
            return listOf(
                setDwordCommand("ProxyEnable", "0"),
                setStringCommand("AutoConfigURL", pacUrl)
            )
        }

        fun restoreCommands(state: WindowsProxyState): List<List<String>> {
            return listOf(
                valueCommand("ProxyEnable", state.proxyEnable, isDword = true),
                valueCommand("ProxyServer", state.proxyServer, isDword = false),
                valueCommand("ProxyOverride", state.proxyOverride, isDword = false),
                valueCommand("AutoConfigURL", state.autoConfigUrl, isDword = false)
            )
        }

        private fun valueCommand(name: String, value: String?, isDword: Boolean): List<String> {
            return if (value == null) {
                listOf("reg", "delete", REGISTRY_KEY, "/v", name, "/f")
            } else if (isDword) {
                setDwordCommand(name, value.removePrefix("0x").toIntOrNull(16)?.toString() ?: value)
            } else {
                setStringCommand(name, value)
            }
        }

        private fun setStringCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_SZ", "/d", value, "/f")
        }

        private fun setDwordCommand(name: String, value: String): List<String> {
            return listOf("reg", "add", REGISTRY_KEY, "/v", name, "/t", "REG_DWORD", "/d", value, "/f")
        }

        fun refreshCommand(): List<String> {
            val script = """
                ${'$'}signature = '[System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)] public static extern bool InternetSetOption(System.IntPtr hInternet, int dwOption, System.IntPtr lpBuffer, int dwBufferLength);';
                Add-Type -MemberDefinition ${'$'}signature -Name WinInet -Namespace Native;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 39, [System.IntPtr]::Zero, 0) | Out-Null;
                [Native.WinInet]::InternetSetOption([System.IntPtr]::Zero, 37, [System.IntPtr]::Zero, 0) | Out-Null;
            """.trimIndent()
            return listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script)
        }
    }
}

private suspend fun runCommand(command: List<String>): String = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("${command.joinToString(" ")} failed with code $exitCode: $output")
    }
    output
}
