package org.olcbox.app.net

import io.ktor.client.HttpClient
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.datasource.createProxyHttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** HTTP response time through an established tunnel; it does not include joining a room. */
object ChannelLatency {
    // A plain HTTP endpoint measures the tunnel round trip without adding a
    // fresh TLS handshake to every sample. HTTPS made the displayed value two
    // to three times larger than the connection setup users compare it to.
    const val URL = "http://captive.apple.com/hotspot-detect.html"
    const val TIMEOUT_MS = 5_000L

    /** One HTTP pool per live connection. Owners close it on stop or migration. */
    class Session internal constructor(
        private val client: HttpClient,
        private val proxy: SubscriptionFetchProxy? = null
    ) {
        constructor(proxy: SubscriptionFetchProxy?) : this(
            createProxyHttpClient(proxy, TIMEOUT_MS, TIMEOUT_MS, TIMEOUT_MS, followRedirects = false), proxy
        )

        // Measure and the foreground sampler may overlap. Serializing avoids
        // competing requests; the deadline includes time waiting for this lock.
        private val mutex = kotlinx.coroutines.sync.Mutex()
        private var warmed = false
        suspend fun measure(): Long? = withTimeoutOrNull(TIMEOUT_MS) {
            mutex.lock()
            try {
                if (!warmed) {
                    if (probe(client, proxy) == null) return@withTimeoutOrNull null
                    warmed = true
                }
                probe(client, proxy)
            } finally { mutex.unlock() }
        }

        fun close() = client.close()
    }

    // A null proxy is for iOS only: the app's requests traverse its packet tunnel.
    suspend fun measure(proxy: SubscriptionFetchProxy?): Long? {
        val client = createProxyHttpClient(proxy, TIMEOUT_MS, TIMEOUT_MS, TIMEOUT_MS, followRedirects = false)
        return measure(client, proxy, warmUp = true)
    }

    /** Takes ownership of the client; separate to test status, cancellation and deadlines. */
    internal suspend fun measure(
        client: HttpClient,
        proxy: SubscriptionFetchProxy? = null,
        warmUp: Boolean = false
    ): Long? = try {
        if (warmUp && probe(client, proxy) == null) null else probe(client, proxy)
    } finally {
        client.close()
    }

    private suspend fun probe(client: HttpClient, proxy: SubscriptionFetchProxy?): Long? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            withProxyAuthentication(proxy) {
                val started = TimeSource.Monotonic.markNow()
                // sing-box URLTest times a HEAD after its outbound has dialled.
                // The caller warms the connection once when necessary, so the
                // displayed number has the same meaning instead of including a
                // complete cold TLS/Reality setup.
                val response = client.head(URL) { header("Cache-Control", "no-cache, no-store") }
                if (response.status.value in 200..299) started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                else null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Null measurement state means pending; a completed null result means failure. */
data class ChannelMeasurement(val millis: Long?) {
    fun label(): String = millis?.let { "HTTP ${it}ms" } ?: "HTTP —"
}
