package org.olcbox.app.vpn.rooms

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.device.DeviceDescription

class RoomListFetchTest {
    private val device = DeviceDescription(os = "Android", osVersion = "15", model = "Pixel 9é Pro")
    private val url = "https://lists.example/sub/abc"

    private fun client(onRequest: (HttpRequestData) -> Unit = {}, answer: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine { req -> onRequest(req); answer() }) { expectSuccess = false }

    @Test fun theRequestCarriesTheAppsHeaders() = runTest {
        var seen: HttpRequestData? = null
        val c = client({ seen = it }) { respond("olcrtc://vkcalls?vp8channel@V1#k") }
        val out = fetchRoomList(c, url, "hw-1234", device)
        assertEquals(RoomFetchOutcome.Body("olcrtc://vkcalls?vp8channel@V1#k"), out)
        val h = seen!!.headers
        assertEquals(ROOM_LIST_ACCEPT, h[HttpHeaders.Accept])
        assertEquals(CurrentAppInfo.userAgent, h[HttpHeaders.UserAgent])
        assertEquals("hw-1234", h["x-hwid"])
        assertEquals("Android", h["x-device-os"])
        assertEquals("15", h["x-ver-os"])
        assertEquals("Pixel 9 Pro", h["x-device-model"], "cut by asciiHeader")
    }

    @Test fun noDeviceIdNoHeader() = runTest {
        var seen: HttpRequestData? = null
        fetchRoomList(client({ seen = it }) { respond("x") }, url, null, device)
        assertNull(seen!!.headers["x-hwid"])
        fetchRoomList(client({ seen = it }) { respond("x") }, url, "", device)
        assertNull(seen!!.headers["x-hwid"])
        fetchRoomList(client({ seen = it }) { respond("x") }, url, "id\nwith a break", device)
        assertNull(seen!!.headers["x-hwid"], "never one a header cannot carry")
    }

    @Test fun aRefusalIsReadFromTheHeadersNotTheBody() = runTest {
        val c = client {
            respond(
                "olcrtc://vkcalls?vp8channel@PLACEHOLDER#k",
                headers = headersOf("x-hwid-max-devices-reached" to listOf("true"), "announce" to listOf("Free a place"))
            )
        }
        assertEquals(
            RoomFetchOutcome.Refused(RoomListRefusal("x-hwid-max-devices-reached", "Free a place")),
            fetchRoomList(c, url, "hw", device)
        )
    }

    @Test fun aNon2xxIsAFailureWhateverItsHeadersSay() = runTest {
        val c = client {
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf("x-hwid-not-supported", "true"))
        }
        assertEquals(RoomFetchOutcome.Failed(503, null), fetchRoomList(c, url, "hw", device))
    }

    @Test fun noAnswerIsAFailureWithTheClassNameOnly() = runTest {
        val c = client { throw IllegalStateException("connect to lists.example failed") }
        val out = fetchRoomList(c, url, "hw", device)
        assertIs<RoomFetchOutcome.Failed>(out)
        assertEquals(0, out.status)
        val cause = out.cause.orEmpty()
        assertEquals(false, cause.contains("lists.example") || cause.contains("connect"), "only a class name, never the message: $cause")
    }
}
