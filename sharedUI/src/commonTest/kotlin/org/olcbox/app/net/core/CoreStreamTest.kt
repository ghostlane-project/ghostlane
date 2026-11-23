package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.net.XrayRustSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `tlsSettings` as [putTls] writes it. Whether a link may waive certificate
 * checks is each kind's own rule (its refusal); what a kind hands on is
 * written, and nothing is waived that no kind handed on.
 */
class CoreStreamTest {
    private fun stream(insecure: Boolean): JsonObject = buildJsonObject {
        putTls(serverName = "a.example", fingerprint = "chrome", alpn = listOf("h2", "http/1.1"), pin = "ab12", insecure = insecure)
    }

    @Test fun waivedChecksAreWrittenWhenAKindHandsThemOn() {
        val tls = stream(insecure = true).getValue("tlsSettings").jsonObject
        assertEquals(JsonPrimitive(true), tls["allowInsecure"])
        // The field as the core spells it, among the fields its parser lists for the object.
        assertEquals(emptySet<String>(), tls.keys - XrayRustSurface.TLS)
        assertEquals(
            """{"security":"tls","tlsSettings":{"serverName":"a.example","allowInsecure":true,"fingerprint":"chrome","alpn":["h2","http/1.1"],"pinnedPeerCertSha256":"ab12"}}""",
            stream(insecure = true).toString(),
        )
    }

    @Test fun nothingIsWaivedThatNoKindHandedOn() {
        assertNull(stream(insecure = false).getValue("tlsSettings").jsonObject["allowInsecure"])
        // Not written as false either: the references of the kinds that waive nothing do not move.
        val expected =
            """{"security":"tls","tlsSettings":{"serverName":"a.example","fingerprint":"chrome","alpn":["h2","http/1.1"],"pinnedPeerCertSha256":"ab12"}}"""
        assertEquals(expected, stream(insecure = false).toString())
        assertEquals(
            expected,
            buildJsonObject { putTls(serverName = "a.example", fingerprint = "chrome", alpn = listOf("h2", "http/1.1"), pin = "ab12") }.toString(),
        )
    }

    @Test fun aServerWithNoNameOfItsOwnMayStillWaive() {
        assertEquals(
            """{"security":"tls","tlsSettings":{"allowInsecure":true}}""",
            buildJsonObject { putTls(serverName = "", insecure = true) }.toString(),
        )
    }
}
