package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChannelLatencyTest {
    @Test fun aResponseTravelsThroughTheRequestedProbeUrl() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(ChannelLatency.URL, request.url.toString())
            assertEquals("no-cache, no-store", request.headers["Cache-Control"])
            respond("", HttpStatusCode.NoContent)
        })
        assertNotNull(ChannelLatency.measure(client))
    }

    @Test fun successfulStatusAloneDoesNotProveTheExpectedProbeAnswered() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Found, HttpStatusCode.ServiceUnavailable)) {
            val client = HttpClient(MockEngine { respond("", status) }) { followRedirects = false }
            assertNull(ChannelLatency.measure(client), "Unexpected HTTP ${status.value}")
        }
    }

    @Test fun cancellationIsNotReportedAsADeadTunnel() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("superseded connection") })
        assertFailsWith<CancellationException> { ChannelLatency.measure(client) }
    }

    @Test fun aStalledRequestHasAnOverallDeadline() = runTest {
        val client = HttpClient(MockEngine { awaitCancellation() })
        assertNull(ChannelLatency.measure(client))
    }
}
