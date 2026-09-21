package org.olcbox.app.net

import org.olcbox.app.data.model.LocationEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UdpBlockedFailoverTest {

    private fun entry(id: String, name: String, link: String, subscription: String? = "https://example.test/sub") =
        LocationEntry(storageId = id, name = name, subscriptionUrl = subscription, rawLink = link, kind = LocationKind.Vless)

    private fun hysteria2(id: String, name: String, subscription: String? = "https://example.test/sub") =
        LocationEntry(
            storageId = id,
            name = name,
            subscriptionUrl = subscription,
            rawLink = "hy2://pass@exit.example.test:443?sni=exit.example.test#$name",
            kind = LocationKind.Hysteria2
        )

    private val reality =
        "vless://11111111-2222-3333-4444-555555555555@exit.example.test:443" +
            "?type=tcp&security=reality&pbk=abc&sid=01&sni=www.microsoft.com&fp=chrome"

    private val xhttp =
        "vless://11111111-2222-3333-4444-555555555555@exit.example.test:443" +
            "?type=xhttp&security=reality&pbk=abc&sid=01&sni=www.microsoft.com&path=/x"

    @Test
    fun theSameExitOverTcpIsPreferredInTheAppsOwnOrder() {
        val failed = hysteria2("hy2", "Amsterdam · Hysteria2")
        val all = listOf(
            failed,
            entry("xhttp", "Amsterdam · XHTTP", xhttp),
            entry("reality", "Amsterdam", reality)
        )

        assertEquals("reality", UdpBlockedFailover.tcpAlternative(failed, all)?.storageId)
    }

    @Test
    fun xhttpIsTakenWhenItIsTheOnlyTcpSibling() {
        val failed = hysteria2("hy2", "Amsterdam · Hysteria2")
        val all = listOf(failed, entry("xhttp", "Amsterdam · XHTTP", xhttp))

        assertEquals("xhttp", UdpBlockedFailover.tcpAlternative(failed, all)?.storageId)
    }

    @Test
    fun anotherExitIsNotAnAlternative() {
        val failed = hysteria2("hy2", "Amsterdam · Hysteria2")
        val all = listOf(failed, entry("other", "Frankfurt", reality))

        assertNull(UdpBlockedFailover.tcpAlternative(failed, all))
    }

    @Test
    fun anotherSubscriptionIsNotAnAlternative() {
        val failed = hysteria2("hy2", "Amsterdam · Hysteria2")
        val all = listOf(
            failed,
            entry("elsewhere", "Amsterdam", reality, subscription = "https://other.test/sub")
        )

        assertNull(UdpBlockedFailover.tcpAlternative(failed, all))
    }

    @Test
    fun aLoneHysteria2LocationHasNowhereToGo() {
        val failed = hysteria2("hy2", "Amsterdam · Hysteria2")

        assertNull(UdpBlockedFailover.tcpAlternative(failed, listOf(failed)))
    }
}
