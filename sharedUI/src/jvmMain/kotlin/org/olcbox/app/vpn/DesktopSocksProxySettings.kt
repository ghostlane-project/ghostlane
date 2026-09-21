package org.olcbox.app.vpn

import kotlinx.serialization.Serializable
import org.olcbox.app.vpn.desktop.PacServer
import java.security.SecureRandom

@Serializable
data class DesktopSocksProxySettings(
    val host: String = PacServer.LOCAL_SOCKS_HOST,
    val port: Int = PacServer.LOCAL_SOCKS_PORT,
    val username: String = "",
    val password: String = "",
    val shareOnLan: Boolean = false,
    val lanAddress: String = "",
    /** Gateway identity captured when the user explicitly trusted this LAN. */
    val lanNetworkId: String = "",
    val lanPort: Int = DEFAULT_LAN_PORT,
    val lanUsername: String = "",
    val lanPassword: String = ""
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    fun normalized(): DesktopSocksProxySettings {
        return copy(
            host = host.ifBlank { PacServer.LOCAL_SOCKS_HOST },
            port = sanitizePort(port),
            username = username.take(MAX_CREDENTIAL_LENGTH),
            password = password.take(MAX_CREDENTIAL_LENGTH),
            lanAddress = lanAddress.trim(),
            lanNetworkId = lanNetworkId.trim(),
            lanPort = sanitizePort(lanPort).let { if (it == sanitizePort(port)) alternatePort(it) else it },
            lanUsername = lanUsername.take(MAX_CREDENTIAL_LENGTH),
            lanPassword = lanPassword.take(MAX_CREDENTIAL_LENGTH)
        )
    }

    fun withGeneratedLanCredentials(): DesktopSocksProxySettings = copy(
        lanUsername = "ghostlane-${randomToken(8)}",
        lanPassword = randomToken(24)
    )

    companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MAX_CREDENTIAL_LENGTH = 64
        const val DEFAULT_LAN_PORT = 10818
        private const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private val secureRandom = SecureRandom()

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        fun sanitizePort(port: Int?): Int {
            return port?.takeIf { isValidPort(it) } ?: PacServer.LOCAL_SOCKS_PORT
        }

        private fun alternatePort(port: Int): Int = if (port == MAX_PORT) port - 1 else port + 1

        internal fun randomToken(length: Int): String = buildString(length) {
            repeat(length) { append(TOKEN_ALPHABET[secureRandom.nextInt(TOKEN_ALPHABET.length)]) }
        }
    }
}
