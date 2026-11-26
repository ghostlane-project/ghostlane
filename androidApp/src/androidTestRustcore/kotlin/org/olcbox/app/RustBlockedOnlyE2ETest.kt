package org.olcbox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.LocationKind
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.service.RustCore

/**
 * Only blocked sites, on the Rust core in the tun's session: the session puts Re:filter's list,
 * as the app bundles it, in the core's geodata directory and tells the core where it is, and the
 * core routes by the list. Asked through the core's SOCKS port, as hev hands it what the phone's
 * apps open: a listed name (under a listed domain) and a listed address go by the line, to a
 * VLESS far end on loopback that answers them itself and never dials them; an unlisted name goes
 * straight out, so its own server answers and the far end never hears of it. The app's own check
 * of the tunnel (1.1.1.1, unlisted) goes straight out too.
 */
@RunWith(AndroidJUnit4::class)
class RustBlockedOnlyE2ETest {
    private val e2e = RustE2eService(InstrumentationRegistry.getInstrumentation())
    private val id = UUID.fromString("6f1e0b8a-3c2d-4e5f-8a9b-0c1d2e3f4a5b")

    @Before
    fun clean() {
        RustCore.testConfig = null
        RustCoreEngine.testCarrier = null
    }

    @Test
    fun aListedNameGoesByTheLineAndAnUnlistedOneDirect() {
        val nonce = "LISTED-${System.nanoTime()}"
        val listedName = "www.rutracker.org"
        val listedAddress = "104.16.0.1"
        val unlisted = "one.one.one.one"
        FarEnd(VlessFar(id), nonce, answered = setOf("$listedName:80", "$listedAddress:80")).use { far ->
            e2e.consent()
            e2e.save(
                LocationConfig(
                    name = "e2e-blocked-only",
                    kind = LocationKind.Vless,
                    rawLink = "vless://$id@127.0.0.1:${far.port}?type=tcp&security=none&encryption=none#e2e-blocked-only"
                ),
                RoutingSettings(mode = RoutingMode.BlockedOnly)
            )
            try {
                e2e.start()
                e2e.awaitConnected("the blocked-only line") { far.report() }
                val port = e2e.corePort()

                val byName = SocksFetch.get(port, e2e.user, e2e.pass, listedName, 80, until = nonce)
                e2e.say("RESULT blocked-only, a listed name: ${byName.lineSequence().firstOrNull()}; ${far.report()}")
                assertTrue("a listed name goes by the line: '$byName'; ${far.report()}", byName.contains(nonce))
                assertTrue("the far end was asked for the name itself: ${far.report()}", "$listedName:80" in far.seen)

                val byAddress = SocksFetch.get(port, e2e.user, e2e.pass, listedAddress, 80, until = nonce)
                e2e.say("RESULT blocked-only, a listed address: ${byAddress.lineSequence().firstOrNull()}; ${far.report()}")
                assertTrue("a listed address goes by the line: '$byAddress'; ${far.report()}", byAddress.contains(nonce))
                assertTrue("the far end was asked for the address: ${far.report()}", "$listedAddress:80" in far.seen)

                val direct = SocksFetch.get(port, e2e.user, e2e.pass, unlisted, 80)
                e2e.say("RESULT blocked-only, an unlisted name: ${direct.lineSequence().firstOrNull()}; ${far.report()}")
                assertTrue("an unlisted name is answered by its own server: '$direct'", direct.startsWith("HTTP/"))
                assertFalse("and not by the far end: '$direct'", direct.contains(nonce))
                assertTrue(
                    "the far end never heard of the unlisted name: ${far.report()}",
                    far.seen.none { it.startsWith("$unlisted:") || it == "1.1.1.1:80" || it == "1.0.0.1:80" }
                )
            } finally {
                e2e.stop()
            }
        }
    }
}
