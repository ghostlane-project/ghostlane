package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class TransportParsingRegressionTest {
    private val base = "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:2053" +
        "?security=reality&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sni=rutube.ru"

    @Test fun grpcKeepsItsServiceAndDoesNotBecomeTcp() {
        val spec = assertIs<OutboundSpec.Vless>(
            LinkParser.parse("$base&type=grpc&serviceName=media%2Fsync&flow=xtls-rprx-vision")
        )
        assertEquals("media/sync", assertIs<TransportSpec.Grpc>(spec.transport).serviceName)
        assertNull(spec.flow)
        assertEquals(TransportKind.Grpc, org.olcbox.app.data.model.LocationConfig(
            kind = LocationKind.Vless,
            rawLink = "$base&type=grpc&servicename=media%2Fsync"
        ).transportKind())

        val outbound = Json.parseToJsonElement(SingBoxConfig.build(spec))
            .jsonObject["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("grpc", outbound["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("media/sync", outbound["transport"]!!.jsonObject["service_name"]!!.jsonPrimitive.content)
        assertFalse("flow" in outbound)
    }

    @Test fun percentEscapesAreDecodedOnce() {
        val spec = assertIs<OutboundSpec.Vless>(
            LinkParser.parse("$base&type=xhttp&path=%2Fencoded%252Fpart")
        )
        assertEquals("/encoded%2Fpart", assertIs<TransportSpec.Xhttp>(spec.transport).path)
    }
}
