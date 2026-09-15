package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import org.olcbox.app.data.repository.SubscriptionFetchProxy

internal actual fun createChannelHttpClient(proxy: SubscriptionFetchProxy?): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    engine { if (proxy != null) this.proxy = ProxyBuilder.socks(proxy.host, proxy.port) }
    install(HttpTimeout) {
        connectTimeoutMillis = ChannelLatency.TIMEOUT_MS
        requestTimeoutMillis = ChannelLatency.TIMEOUT_MS
        socketTimeoutMillis = ChannelLatency.TIMEOUT_MS
    }
}
