package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether traffic really crosses a transport, asked through the local SOCKS port
 * of that transport's core before any tunnel exists (smart connect).
 *
 * One small request is not enough. Russian DPI lets a TCP connection to a foreign
 * address carry its first 16-20 KB and then freezes it, silently, so a HEAD passes
 * exactly where browsing then stalls. The check asks for a 204 and then for 64 KB
 * whole, through the same client.
 */
object TransportCheck {
    const val LATENCY_URL = ChannelLatency.URL
    const val BULK_URL = "https://speed.cloudflare.com/__down?bytes=65536"
    const val BULK_BYTES = 65_536
    const val TIMEOUT_MS = 10_000L

    /** [client] is proxied through the candidate's core; its owner closes it. */
    suspend fun passes(client: HttpClient): Boolean = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            val head = client.head(LATENCY_URL) { header("Cache-Control", "no-cache, no-store") }
            if (head.status.value != 204) return@withTimeoutOrNull false
            val bulk = client.get(BULK_URL) { header("Cache-Control", "no-cache, no-store") }
            bulk.status.value in 200..299 && bulk.readRawBytes().size >= BULK_BYTES
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/**
 * Whether the network is in "whitelist" mode, as Russian mobile networks are during
 * a shutdown: only whitelisted domestic services answer. Asked directly, outside
 * any tunnel, of one foreign and one domestic address at once. Foreign silent and
 * domestic answering is the pattern; anything else (both answer, both silent, an
 * offline phone) is not, so a wrong answer only reorders smart connect's steps.
 */
object WhitelistCheck {
    const val FOREIGN_URL = ChannelLatency.URL
    const val DOMESTIC_URL = "https://ya.ru/"
    const val TIMEOUT_MS = 4_000L

    /** [client] goes out directly; its owner closes it. */
    suspend fun detect(client: HttpClient): Boolean = coroutineScope {
        val foreign = async { answers(client, FOREIGN_URL) }
        val domestic = async { answers(client, DOMESTIC_URL) }
        !foreign.await() && domestic.await()
    }

    private suspend fun answers(client: HttpClient, url: String): Boolean = try {
        withTimeoutOrNull(TIMEOUT_MS) { client.head(url).status.value in 200..399 } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
