package org.olcbox.app.data.importer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The page a phone uses to send a TV its server list, over a real socket: the token
 * is the only way in, a link arrives decoded and exactly once, and nothing the phone
 * sends comes back in the page.
 */
class PhoneImportServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = CopyOnWriteArrayList<String>()
    private val server = PhoneImportServer(
        InetAddress.getLoopbackAddress(),
        PhoneImportPage("en", false, "Send to <TV>", "Server list link", "Send", "Sent. Look at the TV.", "Paste a link first."),
        onLink = { links += it }
    ).also { it.start(scope) }
    private val http = HttpClient.newHttpClient()

    @AfterTest fun stop() {
        server.close()
        scope.cancel()
    }

    private fun get(url: String) = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun post(url: String, form: String) = http.send(
        HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
        HttpResponse.BodyHandlers.ofString()
    )

    @Test fun theFormIsOnlyAtTheToken() {
        assertTrue(server.url.startsWith("http://127.0.0.1:"))
        assertTrue(server.token.matches(Regex("[0-9a-f]{32}")))
        val page = get(server.url)
        assertEquals(200, page.statusCode())
        assertTrue("Server list link" in page.body())
        assertTrue("Send to &lt;TV&gt;" in page.body(), "the page's own words are escaped")
        assertFalse("<script" in page.body())
        assertEquals(404, get(server.url.substringBeforeLast('/') + "/" + "0".repeat(32)).statusCode())
        assertEquals(404, get(server.url.substringBeforeLast('/') + "/").statusCode())
    }

    @Test fun aSentLinkArrivesDecodedAndOnce() {
        val link = "https://sub.example.com/abc?x=1&y=два"
        val answer = post(server.url, "link=" + URLEncoder.encode(link, "UTF-8"))
        assertEquals(200, answer.statusCode())
        assertTrue("Sent. Look at the TV." in answer.body())
        assertFalse("sub.example.com" in answer.body(), "nothing sent comes back")
        assertEquals(listOf(link), links.toList())
    }

    @Test fun anEmptyFormAsksAgainAndImportsNothing() {
        val answer = post(server.url, "link=%20%20")
        assertTrue("Paste a link first." in answer.body())
        assertTrue(links.isEmpty())
    }

    @Test fun anOversizedOrMalformedRequestIsRefused() {
        assertEquals(413, post(server.url, "link=" + "a".repeat(17 * 1024)).statusCode())
        Socket("127.0.0.1", server.url.substringAfterLast(':').substringBefore('/').toInt()).use { raw ->
            raw.getOutputStream().write("garbage\r\n\r\n".toByteArray())
            val reply = raw.getInputStream().readBytes().decodeToString()
            assertTrue(reply.startsWith("HTTP/1.1 400"), reply)
        }
        assertTrue(links.isEmpty())
    }

    @Test fun formFieldsAreDecoded() {
        assertEquals("a b&c", PhoneImportServer.formField("x=1&link=a+b%26c", "link"))
        assertEquals(null, PhoneImportServer.formField("x=1", "link"))
    }
}
