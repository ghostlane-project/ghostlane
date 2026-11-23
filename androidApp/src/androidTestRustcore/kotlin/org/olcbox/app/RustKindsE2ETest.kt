package org.olcbox.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.service.RustCore

/**
 * Every kind of line the session writes from a link, carried on the Rust core in the tun's
 * session: Trojan and VLESS over TLS, Shadowsocks and VMess, each to a far end of its own kind on
 * loopback (RustLineE2eKit). No test config stands in: the session builds the core's config from
 * the stored link, the app's own check of the tunnel goes through the far end (which relays it),
 * and a fetch of 192.0.2.1:80 (TEST-NET-1, reachable nowhere) through the core's SOCKS port can
 * only have been answered by the far end, at the other end of the line.
 *
 * And a Reality link whose `fp` names a hello the core has no REALITY form of is carried with
 * Chrome's, and the session's log says so.
 */
@RunWith(AndroidJUnit4::class)
class RustKindsE2ETest {
    private val e2e = RustE2eService(InstrumentationRegistry.getInstrumentation())
    private val id = UUID.fromString("2d6d1c55-7a43-4d1e-9a37-5b8e2f3c9a10")

    @Before
    fun clean() {
        RustCore.testConfig = null
        RustCoreEngine.testCarrier = null
    }

    private fun carries(kind: String, locationKind: LocationKind, far: FarEnd, nonce: String, link: String) {
        far.use {
            e2e.consent()
            e2e.save(LocationConfig(name = "e2e-$kind", kind = locationKind, rawLink = link))
            try {
                e2e.start()
                e2e.awaitConnected("the $kind line") { far.report() }
                val reply = SocksFetch.get(e2e.corePort(), e2e.user, e2e.pass, "192.0.2.1", 80, until = nonce)
                e2e.say("RESULT $kind: a fetch of 192.0.2.1:80 through the core: ${reply.lineSequence().firstOrNull()}; ${far.report()}")
                assertTrue("the far end's page came back over the $kind line: '$reply'; ${far.report()}", reply.contains(nonce))
                assertEquals("the far end answered the one fetch: ${far.report()}", 1, far.served.get())
            } finally {
                e2e.stop()
            }
        }
    }

    @Test
    fun aTrojanLineCarriesAFetch() {
        val nonce = "TROJAN-${System.nanoTime()}"
        val password = "e2e-trojan-${System.nanoTime()}"
        val far = FarEnd(TrojanFar(password), nonce, tls = true)
        carries(
            "Trojan", LocationKind.Trojan, far, nonce,
            "trojan://$password@127.0.0.1:${far.port}?security=tls&sni=${E2eTls.NAME}&allowInsecure=1&type=tcp#e2e-trojan"
        )
    }

    @Test
    fun aVlessLineOverTlsCarriesAFetch() {
        val nonce = "VLESSTLS-${System.nanoTime()}"
        val far = FarEnd(VlessFar(id), nonce, tls = true)
        carries(
            "VLESS over TLS", LocationKind.Vless, far, nonce,
            "vless://$id@127.0.0.1:${far.port}?security=tls&encryption=none&sni=${E2eTls.NAME}&allowInsecure=1&type=tcp#e2e-vless-tls"
        )
    }

    @Test
    fun aShadowsocksLineCarriesAFetch() {
        val nonce = "SS-${System.nanoTime()}"
        val password = "e2e-ss-${System.nanoTime()}"
        val far = FarEnd(ShadowsocksFar(password), nonce)
        carries(
            "Shadowsocks", LocationKind.Shadowsocks, far, nonce,
            "ss://aes-128-gcm:$password@127.0.0.1:${far.port}#e2e-ss"
        )
    }

    /**
     * VMess with no TLS: a VMess link (v2rayN's JSON) has no way to waive the certificate check,
     * so a far end with a certificate made here could not be reached over TLS.
     */
    @Test
    fun aVmessLineCarriesAFetch() {
        val nonce = "VMESS-${System.nanoTime()}"
        val far = FarEnd(VmessFar(id), nonce)
        val json = """{"v":"2","ps":"e2e-vmess","add":"127.0.0.1","port":"${far.port}","id":"$id",""" +
            """"aid":"0","scy":"aes-128-gcm","net":"tcp","type":"none","tls":""}"""
        carries(
            "VMess", LocationKind.Vmess, far, nonce,
            "vmess://" + Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
        )
    }

    /**
     * fp=android: the core has no REALITY form of that hello and sends Chrome's. Only the start is
     * asserted (nothing answers at 192.0.2.9): the session says which hello it sends.
     */
    @Test
    fun aRealityLineWithTheAndroidHelloSaysWhichHelloIsSent() {
        e2e.consent()
        val link = "vless://$id@192.0.2.9:443" +
            "?security=reality&encryption=none&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=ab12cd34&fp=android&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#e2e-android-hello"
        e2e.save(LocationConfig(name = "e2e-reality", kind = LocationKind.Vless, rawLink = link))
        try {
            e2e.start()
            e2e.waitFor("the core to start on the Reality line", 60_000) { e2e.logged(RustE2eService.READY) }
            val note = e2e.logSinceStart().firstOrNull { it.contains("asks for the android hello") }
            e2e.say("RESULT Reality with fp=android: ${note ?: "no note in the log"}")
            assertNotNull("the session says which hello the core sends: ${e2e.logSinceStart().takeLast(20)}", note)
            assertTrue("the note names the hello sent: '$note'", note!!.contains("the Chrome hello is used instead"))
        } finally {
            e2e.stop()
        }
    }
}
