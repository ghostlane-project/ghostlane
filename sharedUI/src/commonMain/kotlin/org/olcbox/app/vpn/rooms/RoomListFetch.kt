package org.olcbox.app.vpn.rooms

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.device.DeviceDescription
import org.olcbox.app.data.device.asciiHeader
import org.olcbox.app.data.device.deviceDescription

/** What one room-list fetch came back with. */
sealed interface RoomFetchOutcome {
    /** The host refused this device: nothing is applied, the rooms are kept. */
    data class Refused(val refusal: RoomListRefusal) : RoomFetchOutcome

    /**
     * No list: [status] 0 when no HTTP answer came, else the answer's. [cause] is the
     * exception's class name only: its message can carry the host.
     */
    data class Failed(val status: Int, val cause: String?) : RoomFetchOutcome

    data class Body(val text: String) : RoomFetchOutcome
}

/**
 * What the list fetch asks for, the app's own (LocationsDatasource's list fetch) and the
 * iPhone tunnel's (DeviceHeaders.listAccept).
 */
internal const val ROOM_LIST_ACCEPT = "text/plain, text/markdown, application/octet-stream, */*"

/**
 * One GET of a subscription for its room list, with the headers the app's own list fetch
 * sends in its identity mode and the iPhone's RoomKeeper sends: Accept, the app's user
 * agent, `x-hwid` when the device id reads, and the three device headers cut by
 * [asciiHeader]. A refusal is read from the headers and its body never is.
 *
 * [client] is the caller's: it decides the path (the engine's SOCKS, as the app's refresh
 * while connected) and the timeouts, and must not throw on a non-2xx (`expectSuccess = false`).
 */
internal suspend fun fetchRoomList(
    client: HttpClient,
    url: String,
    hwid: String?,
    device: DeviceDescription = deviceDescription()
): RoomFetchOutcome {
    val response = try {
        client.get(url) {
            headers {
                append(HttpHeaders.Accept, ROOM_LIST_ACCEPT)
                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                // Never an empty one, nor one a header cannot carry.
                if (!hwid.isNullOrEmpty() && hwid.all { it in ' '..'~' }) append("x-hwid", hwid)
                for ((name, value) in listOf(
                    "x-device-os" to device.os,
                    "x-ver-os" to device.osVersion,
                    "x-device-model" to device.model
                )) {
                    val kept = asciiHeader(value)
                    if (kept.isNotEmpty()) append(name, kept)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return RoomFetchOutcome.Failed(0, e::class.simpleName)
    }
    val status = response.status.value
    RoomListRefusal.of(status) { response.headers[it] }?.let { return RoomFetchOutcome.Refused(it) }
    if (status !in 200..299) return RoomFetchOutcome.Failed(status, null)
    return try {
        RoomFetchOutcome.Body(response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RoomFetchOutcome.Failed(status, e::class.simpleName)
    }
}
