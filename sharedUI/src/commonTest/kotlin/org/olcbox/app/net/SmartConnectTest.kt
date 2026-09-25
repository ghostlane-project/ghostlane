package org.olcbox.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smart connect's plan and its two checks. Names are the coordinator's own:
 * core lines `US via RU | price`, `… · Hysteria2`, `… · XHTTP`; olcRTC lines by
 * country, `US · olcRTC`, `US · olcRTC · WB`.
 */
class SmartConnectTest {
    private val sub = "https://proofkit.org/sub/aaa"

    private fun reality(name: String, id: String, url: String = sub) = entry(
        id, name, LocationKind.Vless, url,
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:443?type=tcp&security=reality&sni=www.zoom.us" +
            "&fp=chrome&pbk=abc&sid=14090023&flow=xtls-rprx-vision#$name"
    )

    private fun xhttp(name: String, id: String) = entry(
        id, name, LocationKind.Vless, sub,
        "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:40023?type=xhttp&security=reality&encryption=none" +
            "&pbk=abc&sid=14090023&fp=chrome&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#$name"
    )

    private fun hy2(name: String, id: String) = entry(
        id, name, LocationKind.Hysteria2, sub,
        "hysteria2://pass@1.2.3.4:30023?sni=www.zoom.us&obfs=salamander&obfs-password=secret&insecure=0#$name"
    )

    private fun room(name: String, id: String, url: String = sub) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = name, id = "room-$id", key = "k".repeat(64)),
        subscriptionUrl = url
    )

    private fun entry(id: String, name: String, kind: LocationKind, url: String, link: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = name, id = "x", kind = kind, rawLink = link),
        subscriptionUrl = url
    )

    private val usReality = reality("US via RU | 0.1320TON/GB", "us-reality")
    private val usHy2 = hy2("US via RU | 0.1320TON/GB · Hysteria2", "us-hy2")
    private val usXhttp = xhttp("US via RU | 0.1320TON/GB · XHTTP", "us-xhttp")
    private val usRoom = room("US · olcRTC", "us-room")
    private val usWb = room("US · olcRTC · WB", "us-wb")
    private val deReality = reality("DE Direct | 0.0720TON/GB", "de-reality")
    private val deRoom = room("DE · olcRTC", "de-room")
    private val jpReality = reality("JP via RU | 0.1200TON/GB", "jp-reality")
    private val otherSubRoom = room("US · olcRTC", "us-room-other", url = "https://other.example/sub")

    private val all = listOf(usReality, usHy2, usXhttp, usRoom, usWb, deReality, deRoom, jpReality, otherSubRoom)

    private fun List<SmartConnect.Step>.shape() = map {
        (if (it is SmartConnect.Step.Probe) "probe " else "connect ") + it.entry.storageId
    }

    @Test fun theCountryIsTheNamesLeadingCode() {
        assertEquals("US", TransportGroup.countryOf("US via RU | 0.1320TON/GB"))
        assertEquals("DE", TransportGroup.countryOf("DE Direct | 0.0720TON/GB"))
        assertEquals("DE", TransportGroup.countryOf("DE · olcRTC · WB"))
        assertEquals("NL", TransportGroup.countryOf("NL|0.1TON/GB"))
        assertNull(TransportGroup.countryOf("Germany"))
        assertNull(TransportGroup.countryOf("us via RU"))
        assertNull(TransportGroup.countryOf(""))
    }

    // The same country, the same subscription, in the order it lists them.
    @Test fun olcrtcFallbacksAreTheSameCountrysRoomsOnly() {
        assertEquals(listOf("us-room", "us-wb"), TransportGroup.olcrtcFallbacks(usReality, all).map { it.storageId })
        assertEquals(listOf("de-room"), TransportGroup.olcrtcFallbacks(deReality, all).map { it.storageId })
        assertEquals(emptyList(), TransportGroup.olcrtcFallbacks(jpReality, all))
        assertEquals(emptyList(), TransportGroup.olcrtcFallbacks(usRoom, all))
    }

    @Test fun normallyTheCoresComeFirstAndTheSameCountrysOlcrtcLast() {
        assertEquals(
            listOf("probe us-reality", "probe us-hy2", "probe us-xhttp", "connect us-room", "connect us-wb"),
            SmartConnect.plan(usReality, all).shape()
        )
    }

    @Test fun theRowTheUserPickedIsTriedFirst() {
        assertEquals(
            listOf("probe us-xhttp", "probe us-reality", "probe us-hy2", "connect us-room", "connect us-wb"),
            SmartConnect.plan(usXhttp, all).shape()
        )
    }

    @Test fun theGroupsLastWinnerLeadsEvenTheUsersRow() {
        assertEquals(
            listOf("probe us-hy2", "probe us-reality", "probe us-xhttp", "connect us-room", "connect us-wb"),
            SmartConnect.plan(usReality, all, lastKnownGood = "us-hy2").shape()
        )
    }

    @Test fun inWhitelistModeOlcrtcLeadsAndTheCoresStillFollow() {
        assertEquals(
            listOf("connect us-room", "connect us-wb", "probe us-reality", "probe us-hy2", "probe us-xhttp"),
            SmartConnect.plan(usReality, all, whitelist = true).shape()
        )
        assertEquals(
            listOf("connect us-wb", "connect us-room", "probe us-reality", "probe us-hy2", "probe us-xhttp"),
            SmartConnect.plan(usReality, all, lastKnownGood = "us-wb", whitelist = true).shape()
        )
    }

    // One fallback to the slowest path must not keep the user there once the
    // cores get through again: outside whitelist mode an olcRTC winner waits.
    @Test fun anOlcrtcWinnerLeadsOnlyInWhitelistMode() {
        assertEquals(
            listOf("probe us-reality", "probe us-hy2", "probe us-xhttp", "connect us-wb", "connect us-room"),
            SmartConnect.plan(usReality, all, lastKnownGood = "us-wb").shape()
        )
    }

    @Test fun nothingToChooseBetweenIsNoPlan() {
        assertEquals(emptyList(), SmartConnect.plan(usRoom, all), "a room the user picked is connected as it is")
        assertEquals(emptyList(), SmartConnect.plan(jpReality, all), "one transport and no room for its country")
        assertEquals(listOf("probe de-reality", "connect de-room"), SmartConnect.plan(deReality, all).shape())
    }

    @Test fun theGroupKeyIsTheSubscriptionAndTheExit() {
        assertEquals(SmartConnect.groupKey(usReality), SmartConnect.groupKey(usXhttp))
        assertTrue(SmartConnect.groupKey(usReality) != SmartConnect.groupKey(jpReality))
    }

    // ---- the checks ------------------------------------------------------
    // In real time, as ChannelLatencyTest runs: under runTest's virtual clock the
    // checks' own deadlines would pass before MockEngine answers on its thread.

    private fun client(bulkBytes: Int = TransportCheck.BULK_BYTES, headStatus: HttpStatusCode = HttpStatusCode.NoContent) =
        HttpClient(MockEngine { request ->
            when (request.url.host) {
                "www.gstatic.com" -> respond("", headStatus)
                "speed.cloudflare.com" -> respond(ByteArray(bulkBytes), HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })

    @Test fun aTransportPassesWithA204AndSixtyFourKilobytesWhole() = runTest { withContext(Dispatchers.Default) {
        assertTrue(TransportCheck.passes(client()))
    } }

    // What the DPI freeze looks like from here: the small request is answered,
    // the transfer stops after the first kilobytes.
    @Test fun aTransferThatStopsShortFails() = runTest { withContext(Dispatchers.Default) {
        assertFalse(TransportCheck.passes(client(bulkBytes = 16 * 1024)))
    } }

    @Test fun anythingButA204Fails() = runTest { withContext(Dispatchers.Default) {
        assertFalse(TransportCheck.passes(client(headStatus = HttpStatusCode.OK)))
    } }

    @Test fun aFailingProxyFails() = runTest { withContext(Dispatchers.Default) {
        assertFalse(TransportCheck.passes(HttpClient(MockEngine { throw IllegalStateException("refused") })))
    } }

    @Test fun whitelistModeIsForeignSilentAndDomesticAnswering() = runTest { withContext(Dispatchers.Default) {
        fun direct(foreignUp: Boolean, domesticUp: Boolean) = HttpClient(MockEngine { request ->
            val up = if (request.url.host == "ya.ru") domesticUp else foreignUp
            if (up) respond("", HttpStatusCode.OK) else throw IllegalStateException("dropped")
        })
        assertTrue(WhitelistCheck.detect(direct(foreignUp = false, domesticUp = true)))
        assertFalse(WhitelistCheck.detect(direct(foreignUp = true, domesticUp = true)))
        assertFalse(WhitelistCheck.detect(direct(foreignUp = false, domesticUp = false)), "offline is not a whitelist")
        assertFalse(WhitelistCheck.detect(direct(foreignUp = true, domesticUp = false)))
    } }
}
