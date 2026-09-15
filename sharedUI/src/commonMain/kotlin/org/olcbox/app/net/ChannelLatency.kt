package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** HTTP response time through an established tunnel; it does not include joining a room. */
object ChannelLatency {
    const val URL = "https://www.gstatic.com/generate_204"
    const val TIMEOUT_MS = 5_000L

    // A null proxy is for iOS only: the app's requests traverse its packet tunnel.
    suspend fun measure(proxy: SubscriptionFetchProxy?): Long? {
        val client = createChannelHttpClient(proxy)
        return try {
            withProxyAuthentication(proxy) {
                val started = TimeSource.Monotonic.markNow()
                val response = client.get(URL) { header("Cache-Control", "no-cache, no-store") }
                if (response.status.value == 204) started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }
}

internal expect fun createChannelHttpClient(proxy: SubscriptionFetchProxy?): HttpClient
