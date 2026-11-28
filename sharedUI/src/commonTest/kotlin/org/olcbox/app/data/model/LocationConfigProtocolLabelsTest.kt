package org.olcbox.app.data.model

import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.LocationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Imported xray-side links keep the olcRTC carrier defaults (wbstream/vp8channel)
 * because those fields do not apply to them. The location list must not surface
 * those defaults — it used to render every vless/hy2 row as "WB Stream · VP8".
 *
 * Link shapes mirror what the coordinator actually serves at /sub/{token}/unified.
 */
class LocationConfigProtocolLabelsTest {

    private fun imported(kind: LocationKind, link: String) = LocationConfig(
        name = "US via RU",
        id = "1.2.3.4:443",
        kind = kind,
        rawLink = link
    )

    @Test
    fun vlessRealityReportsReality() {
        val cfg = imported(
            LocationKind.Vless,
            "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:443" +
                "?type=tcp&security=reality&sni=www.zoom.us&fp=chrome&pbk=abc&sid=14090023" +
                "&flow=xtls-rprx-vision#US via RU"
        )
        assertEquals(listOf("VLESS", "Reality"), cfg.protocolLabels())
    }

    @Test
    fun vlessXhttpReportsXhttp() {
        val cfg = imported(
            LocationKind.Vless,
            "vless://d67b1637-4fee-4e0d-bc96-000000000000@1.2.3.4:40023" +
                "?type=xhttp&security=reality&encryption=none&pbk=abc&sid=14090023&fp=chrome" +
                "&sni=www.zoom.us&path=%2Fxhttp&host=www.zoom.us&mode=packet-up#US via RU"
        )
        assertEquals(listOf("VLESS", "XHTTP"), cfg.protocolLabels())
    }

    @Test
    fun hysteria2WithObfsReportsSalamander() {
        val cfg = imported(
            LocationKind.Hysteria2,
            "hysteria2://pass@1.2.3.4:30023?sni=www.zoom.us&obfs=salamander" +
                "&obfs-password=secret&insecure=0#US via RU"
        )
        assertEquals(listOf("Hysteria2", "Salamander"), cfg.protocolLabels())
    }

    @Test
    fun hysteria2WithoutObfsReportsPlainHysteria2() {
        val cfg = imported(
            LocationKind.Hysteria2,
            "hysteria2://pass@1.2.3.4:30023?sni=www.zoom.us&insecure=0#US via RU"
        )
        assertEquals(listOf("Hysteria2"), cfg.protocolLabels())
    }

    @Test
    fun olcrtcStillReportsItsCarrier() {
        val cfg = LocationConfig(name = "room", id = "room-id", key = "key")
        assertEquals(listOf("SaluteJazz", "VP8"), cfg.protocolLabels())
    }

    @Test
    fun unparseableLinkFallsBackToProtocolOnly() {
        val cfg = imported(LocationKind.Vless, "vless://not-a-real-link")
        assertEquals(listOf("VLESS"), cfg.protocolLabels())
    }
}
