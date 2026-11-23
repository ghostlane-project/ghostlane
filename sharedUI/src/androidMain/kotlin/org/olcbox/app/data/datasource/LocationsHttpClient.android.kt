package org.olcbox.app.data.datasource

import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Dns
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.io.DataInputStream
import java.io.IOException
import java.net.Authenticator
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory

internal actual fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy?,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long,
    followRedirects: Boolean
): HttpClient {
    return HttpClient(OkHttp) {
        expectSuccess = false
        this.followRedirects = followRedirects

        engine {
            if (subscriptionProxy != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    // Android 6's java.net speaks SOCKS4 alone, which carries no password, and
                    // takes no unresolved host: through a port that demands a login (every tun
                    // session's core) each request failed, and with it the check of every
                    // session. The SOCKS5 handshake is this client's own there.
                    config {
                        proxy(Proxy.NO_PROXY)
                        socketFactory(Socks5SocketFactory(subscriptionProxy))
                        dns(Socks5SocketFactory.HOSTNAME_TO_PROXY)
                    }
                } else {
                    proxy = ProxyBuilder.socks(subscriptionProxy.host, subscriptionProxy.port)
                }
            }
        }

        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }
}

internal actual suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T {
    if (subscriptionProxy == null || subscriptionProxy.username.isBlank()) {
        return block()
    }

    return proxyAuthenticatorMutex.withLock {
        Authenticator.setDefault(subscriptionProxy.authenticator())
        try {
            block()
        } finally {
            Authenticator.setDefault(null)
        }
    }
}

private val proxyAuthenticatorMutex = Mutex()

private fun SubscriptionFetchProxy.authenticator(): Authenticator {
    val proxy = this
    return object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val matchesProxyHost = requestingHost == null ||
                requestingHost == proxy.host ||
                requestingSite?.hostAddress == proxy.host
            if (!matchesProxyHost || requestingPort != proxy.port) {
                return null
            }
            return PasswordAuthentication(proxy.username, proxy.password.toCharArray())
        }
    }
}

/**
 * Sockets that reach their destination through [proxy] by SOCKS5 (RFC 1928), with
 * the proxy's login (RFC 1929) when it has one, for Android 6, whose platform SOCKS
 * client is SOCKS4. OkHttp connects each one to the destination's address with no
 * proxy of its own; a hostname is handed to the proxy to resolve ([HOSTNAME_TO_PROXY]
 * gives OkHttp a placeholder address that carries it), as Android 7's SOCKS5 does.
 */
internal class Socks5SocketFactory(private val proxy: SubscriptionFetchProxy) : SocketFactory() {
    override fun createSocket(): Socket = Socks5Socket(proxy)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)

    companion object {
        /** A hostname is not looked up here: 0.0.0.0 that names it, for the proxy to resolve. */
        val HOSTNAME_TO_PROXY: Dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByAddress(hostname, ByteArray(4)))
        }
    }
}

private class Socks5Socket(private val proxy: SubscriptionFetchProxy) : Socket() {
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IllegalArgumentException("not an internet address: $endpoint")
        super.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
        val readTimeout = soTimeout
        try {
            soTimeout = if (timeout > 0) timeout else HANDSHAKE_TIMEOUT_MS
            handshake(target)
            soTimeout = readTimeout
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    private fun handshake(target: InetSocketAddress) {
        val output = getOutputStream()
        val input = DataInputStream(getInputStream())
        val login = proxy.username.isNotEmpty()
        output.write(if (login) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()
        val version = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        if (version != 5) throw SocketException("the SOCKS proxy answered version $version, not 5")
        when (method) {
            0 -> Unit
            2 -> {
                if (!login) throw SocketException("the SOCKS proxy asks for a login and none is set")
                val user = proxy.username.toByteArray(Charsets.UTF_8)
                val pass = proxy.password.toByteArray(Charsets.UTF_8)
                if (user.size > 255 || pass.size > 255) throw SocketException("the SOCKS login is longer than 255 bytes")
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                output.flush()
                input.readUnsignedByte()
                if (input.readUnsignedByte() != 0) throw SocketException("the SOCKS proxy refused the login")
            }
            else -> throw SocketException("the SOCKS proxy takes none of the offered methods")
        }
        val address = target.address
        val destination = when {
            address == null -> domain(target.hostString)
            address.isAnyLocalAddress && address is Inet4Address -> domain(address.hostName)
            address is Inet4Address -> byteArrayOf(1) + address.address
            else -> byteArrayOf(4) + address.address
        }
        output.write(byteArrayOf(5, 1, 0) + destination + byteArrayOf((target.port shr 8).toByte(), target.port.toByte()))
        output.flush()
        if (input.readUnsignedByte() != 5) throw SocketException("the SOCKS proxy's reply is not SOCKS5")
        val reply = input.readUnsignedByte()
        if (reply != 0) throw SocketException("the SOCKS proxy did not connect (reply $reply)")
        input.readUnsignedByte()
        val bound = when (input.readUnsignedByte()) {
            1 -> 4
            3 -> input.readUnsignedByte()
            4 -> 16
            else -> throw SocketException("the SOCKS proxy's reply holds no address")
        }
        input.readFully(ByteArray(bound + 2))
    }

    private fun domain(host: String): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        if (name.isEmpty() || name.size > 255) throw SocketException("no hostname a SOCKS proxy takes: $host")
        return byteArrayOf(3, name.size.toByte()) + name
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}
