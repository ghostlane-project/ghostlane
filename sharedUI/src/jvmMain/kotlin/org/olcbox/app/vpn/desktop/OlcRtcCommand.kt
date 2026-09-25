package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import java.nio.file.Path

internal data class OlcRtcCommand(
    val binary: Path,
    val location: LocationConfig,
    val socksHost: String = PacServer.LOCAL_SOCKS_HOST,
    val socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
    val socksUser: String = "",
    val socksPass: String = "",
    val dnsServer: String,
    /**
     * A file of the engine's direct rules ([org.olcbox.app.net.OlcrtcDirectRules]),
     * or null for none: the destinations the engine dials itself instead of through
     * the room. Named, not inlined: Russia's lists are 224 KB.
     */
    val directRulesFile: Path? = null
) {
    fun args(configPath: Path): List<String> {
        return listOf(binary.toString(), configPath.toString())
    }

    fun yaml(): String {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)

        return buildString {
            appendLine("mode: cnc")
            appendLine("auth:")
            appendLine("  provider: ${provider.yamlValue()}")
            appendLine("room:")
            appendLine("  id: ${config.id.yamlValue()}")
            appendLine("crypto:")
            appendLine("  key: ${config.key.yamlValue()}")
            appendLine("net:")
            appendLine("  transport: ${config.transport.yamlValue()}")
            appendLine("  dns: ${dnsServer.yamlValue()}")
            appendLine("socks:")
            appendLine("  host: ${socksHost.yamlValue()}")
            appendLine("  port: $socksPort")
            if (socksUser.isNotBlank()) {
                appendLine("  user: ${socksUser.yamlValue()}")
                appendLine("  pass: ${socksPass.yamlValue()}")
            }
            if (directRulesFile != null) {
                appendLine("route:")
                appendLine("  direct_file: ${directRulesFile.toAbsolutePath().toString().yamlValue()}")
            }
            // The engine's UDP relay is opt-in from yaml; without this line
            // SOCKS5 UDP ASSOCIATE is refused and calls fall back to nothing.
            appendLine("udp:")
            appendLine("  enabled: true")
            when (config.transport) {
                LocationConfig.TRANSPORT_VP8CHANNEL -> {
                    appendLine("vp8:")
                    appendLine("  fps: ${config.vp8Fps}")
                    appendLine("  batch_size: ${config.vp8Batch}")
                }
                LocationConfig.TRANSPORT_SEICHANNEL -> {
                    appendLine("sei:")
                    appendLine("  fps: 60")
                    appendLine("  batch_size: 64")
                    appendLine("  fragment_size: 900")
                    appendLine("  ack_timeout_ms: 2000")
                }
            }
        }
    }

    companion object {
        fun desktopProviderArg(provider: String): String {
            val normalizedProvider = LocationConfig.normalizeProvider(provider)
            return when (normalizedProvider) {
                LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
                else -> normalizedProvider
            }
        }
    }
}

private fun String.yamlValue(): String {
    return "'${replace("'", "''")}'"
}
