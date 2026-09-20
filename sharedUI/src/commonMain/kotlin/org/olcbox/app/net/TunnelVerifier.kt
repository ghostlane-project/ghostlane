package org.olcbox.app.net

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.coroutines.cancellation.CancellationException

/** What the far end of the tunnel looks like from the internet. */
data class TunnelExit(
    val ip: String,
    /** ISO country of the exit, when the probe reports one. */
    val country: String?
) {
    /**
     * Short form for the status line. Long IPv6 exits are shortened from the middle:
     * the tail is what distinguishes two addresses from the same block, so cutting it
     * off would make the exit unrecognisable.
     */
    fun label(): String {
        val shortIp = if (ip.length > MAX_IP_CHARS) {
            ip.take(HEAD_CHARS) + "…" + ip.takeLast(TAIL_CHARS)
        } else {
            ip
        }
        return listOfNotNull(country, shortIp).joinToString(" · ")
    }

    private companion object {
        const val MAX_IP_CHARS = 30
        const val HEAD_CHARS = 12
        const val TAIL_CHARS = 8
    }
}

/**
 * Confirms that traffic actually reaches the internet through the tunnel, instead
 * of trusting that the steps we ran succeeded.
 *
 * Every connect failure seen in the field reported "connected": a core whose port
 * collided with the PAC server, a hysteria2 outbound rejected at TLS, a browser that
 * never used the proxy at all. In each case the app had run its steps and said so,
 * while nothing reached the internet. The only honest signal is a request that comes
 * back.
 *
 * The probe goes THROUGH the tunnel by construction, so censorship where the user
 * sits cannot produce a false negative — only a genuinely broken tunnel can.
 */
object TunnelVerifier {

    /**
     * Cloudflare's trace endpoint: a few hundred bytes, no API key, reachable from
     * anywhere the tunnel exits, and it reports the exit country as well as the
     * address — which is what makes the exit visible in the UI at all.
     */
    const val PROBE_URL = "https://1.1.1.1/cdn-cgi/trace"

    /**
     * Same endpoint reached by name instead of by address. Some networks and some
     * exits blackhole 1.1.1.1 specifically; a tunnel that works must not be called
     * dead because one address is unreachable from the far end.
     */
    const val FALLBACK_PROBE_URL = "https://cloudflare.com/cdn-cgi/trace"

    val DEFAULT_PROBE_URLS = listOf(PROBE_URL, FALLBACK_PROBE_URL)

    const val DEFAULT_TIMEOUT_MS = 8_000L

    /**
     * Parses the `key=value` lines Cloudflare returns. Kept pure and separate from
     * the request so the format is covered by tests without a network.
     */
    fun parseTrace(body: String): TunnelExit? {
        var ip: String? = null
        var loc: String? = null
        body.lineSequence().forEach { line ->
            val key = line.substringBefore('=', missingDelimiterValue = "")
            val value = line.substringAfter('=', missingDelimiterValue = "").trim()
            when (key.trim()) {
                "ip" -> if (value.isNotBlank()) ip = value
                "loc" -> if (value.isNotBlank() && value != "XX") loc = value
            }
        }
        return ip?.let { TunnelExit(ip = it, country = loc) }
    }

    /**
     * Runs the probe through the given local SOCKS proxy. Returns null when the
     * tunnel did not carry the request — the caller decides how loudly to say so.
     *
     * The URLs are tried in order and the first answer wins, so one unreachable
     * endpoint cannot condemn a working tunnel. [probeUrls] is also how tests point
     * the probe at a local server; production callers leave it alone.
     */
    suspend fun verify(
        socksHost: String,
        socksPort: Int,
        username: String = "",
        password: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        probeUrls: List<String> = DEFAULT_PROBE_URLS
    ): TunnelExit? {
        val proxy = SubscriptionFetchProxy(
            host = socksHost,
            port = socksPort,
            username = username,
            password = password
        )
        val client = createProxyHttpClient(
            subscriptionProxy = proxy,
            connectTimeoutMs = timeoutMs,
            requestTimeoutMs = timeoutMs,
            socketTimeoutMs = timeoutMs
        )
        return try {
            // SOCKS credentials reach the client through the platform authenticator,
            // the same route subscription downloads use — passing them to the proxy
            // description alone does nothing. olcRTC's local proxy is the one that
            // demands a login, so without this a working olcRTC tunnel would fail
            // its own verification and be reported as dead.
            withProxyAuthentication(proxy) {
                var exit: TunnelExit? = null
                for (url in probeUrls) {
                    exit = try {
                        parseTrace(client.get(url).bodyAsText())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (exit != null) break
                }
                exit
            }
        } catch (e: CancellationException) {
            // A superseded connect must stay cancelled, not be reported as a dead
            // tunnel — CancellationException is an Exception and would be swallowed
            // by the catch below.
            throw e
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }

    /**
     * Verifies a system TUN without opening a second proxy inbound in the TUN
     * process. The caller must arrange for this application's ordinary network
     * traffic to enter that TUN while only carrier child processes bypass it.
     */
    suspend fun verifyDirect(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        probeUrls: List<String> = DEFAULT_PROBE_URLS
    ): TunnelExit? {
        val client = createProxyHttpClient(
            subscriptionProxy = null,
            connectTimeoutMs = timeoutMs,
            requestTimeoutMs = timeoutMs,
            socketTimeoutMs = timeoutMs
        )
        return try {
            for (url in probeUrls) {
                val exit = try {
                    parseTrace(client.get(url).bodyAsText())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (exit != null) return exit
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }
}
