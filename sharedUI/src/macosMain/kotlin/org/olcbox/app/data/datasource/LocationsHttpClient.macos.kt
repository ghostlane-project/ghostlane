package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import org.olcbox.app.data.repository.SubscriptionFetchProxy

internal actual fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy?,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long,
    followRedirects: Boolean
): HttpClient {
    return HttpClient {
        expectSuccess = false
        this.followRedirects = followRedirects

        // Explicit proxies are used by per-member channel probes. Normal iOS
        // subscription fetches pass null and use the system packet tunnel.
        if (subscriptionProxy != null) engine {
            proxy = ProxyBuilder.socks(subscriptionProxy.host, subscriptionProxy.port)
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
): T = block()
