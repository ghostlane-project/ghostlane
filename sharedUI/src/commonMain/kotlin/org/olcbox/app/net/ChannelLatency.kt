package org.olcbox.app.net

import io.ktor.client.HttpClient
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.datasource.createProxyHttpClient
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
        val client = createProxyHttpClient(proxy, TIMEOUT_MS, TIMEOUT_MS, TIMEOUT_MS, followRedirects = false)
        return measure(client, proxy)
    }

    /** Takes ownership of the client; separate to test status, cancellation and deadlines. */
    internal suspend fun measure(client: HttpClient, proxy: SubscriptionFetchProxy? = null): Long? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            withProxyAuthentication(proxy) {
                val started = TimeSource.Monotonic.markNow()
                val response = client.get(URL) { header("Cache-Control", "no-cache, no-store") }
                if (response.status.value == 204) started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                else null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        client.close()
    }
}
