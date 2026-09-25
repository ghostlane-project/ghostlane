package org.olcbox.app.data.importer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * A one-page web form on the home network, for a device with no camera and no
 * clipboard worth the name — a television. A phone on the same network scans the
 * code the TV shows, opens this page, pastes a server-list link and sends it; the
 * TV imports it as a paste would ([onLink]).
 *
 * Open only while the TV shows its code ([close] when the sheet goes). The path is
 * a 128-bit random [token] and anything else is a 404: the token is the secret, and
 * it is only in the code and on the screen. One connection at a time, read with a
 * timeout and a size limit, answered and closed. The page has no script and says
 * back nothing it was sent.
 */
class PhoneImportServer(
    address: InetAddress,
    private val page: PhoneImportPage,
    private val onLink: (String) -> Unit,
) : Closeable {
    val token: String = randomToken()
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(address, 0), BACKLOG)
    }

    /** What the code holds: `http://<address>:<port>/<token>`. */
    val url: String = "http://${address.hostAddress}:${socket.localPort}/$token"

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                client.use { runCatching { serve(it) } }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        job?.cancel()
    }

    private fun serve(client: Socket) {
        client.soTimeout = TIMEOUT_MS
        val input = client.getInputStream().buffered()
        val head = readHead(input) ?: return respond(client, 400, "Bad Request", "")
        val parts = head.lineSequence().first().split(' ')
        if (parts.size < 3) return respond(client, 400, "Bad Request", "")
        val (method, target) = parts
        if (target.substringBefore('?') != "/$token") return respond(client, 404, "Not Found", "")
        when (method) {
            "GET" -> respond(client, 200, "OK", page.form(showEmpty = false))
            "POST" -> {
                val length = header(head, "content-length")?.toIntOrNull()
                    ?: return respond(client, 411, "Length Required", "")
                if (length !in 0..MAX_BODY) return respond(client, 413, "Payload Too Large", "")
                val body = readExactly(input, length) ?: return respond(client, 400, "Bad Request", "")
                val link = formField(body.decodeToString(), "link")?.trim().orEmpty()
                if (link.isEmpty()) return respond(client, 200, "OK", page.form(showEmpty = true))
                onLink(link)
                respond(client, 200, "OK", page.sent())
            }
            else -> respond(client, 405, "Method Not Allowed", "")
        }
    }

    private fun respond(client: Socket, status: Int, reason: String, html: String) {
        val body = html.encodeToByteArray()
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.encodeToByteArray())
            write(body)
            flush()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val BACKLOG = 4
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 16 * 1024

        private val random = SecureRandom()

        private fun randomToken(): String =
            ByteArray(16).also(random::nextBytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        /** The request line and headers, up to the blank line; null past [MAX_HEAD] or on a short read. */
        private fun readHead(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf(13, 10, 13, 10)
            while (bytes.size() < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                bytes.write(b)
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
                if (matched == end.size) return bytes.toByteArray().decodeToString()
            }
            return null
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray? {
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            return body
        }

        private fun header(head: String, name: String): String? =
            head.lineSequence().drop(1)
                .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
                ?.substringAfter(':')?.trim()

        internal fun formField(body: String, name: String): String? =
            body.split('&').firstOrNull { it.substringBefore('=') == name }
                ?.substringAfter('=', "")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

        /**
         * Where a phone on the same network can reach this device: a site-local IPv4
         * address of an interface that is up and is not a tunnel. Null when there is
         * none — the TV is not on a home network, or only on its own VPN.
         */
        fun homeNetworkAddress(): InetAddress? =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
                .filterNot { it.name.startsWith("tun") || it.name.startsWith("ppp") || it.name.startsWith("wg") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }
}

/**
 * The page's words, in the device's language, and the two pages made of them. Every
 * value is escaped: the words are the app's own, but a page is no place to trust that.
 */
class PhoneImportPage(
    private val language: String,
    private val rightToLeft: Boolean,
    private val title: String,
    private val label: String,
    private val send: String,
    private val sent: String,
    private val empty: String,
) {
    fun form(showEmpty: Boolean): String = document(
        """<h1>${escape(title)}</h1>
<form method="post">
<label for="link">${escape(label)}</label>
<textarea id="link" name="link" rows="5" autocomplete="off" autocapitalize="off" spellcheck="false" autofocus></textarea>
${if (showEmpty) """<p class="error">${escape(empty)}</p>""" else ""}
<button type="submit">${escape(send)}</button>
</form>"""
    )

    fun sent(): String = document("""<h1>${escape(title)}</h1>
<p class="done">${escape(sent)}</p>""")

    private fun document(main: String): String = """<!doctype html>
<html lang="${escape(language)}" dir="${if (rightToLeft) "rtl" else "ltr"}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<style>
body{margin:0;padding:24px;background:#0b0d12;color:#e8eaf0;font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:480px;margin:0 auto}
h1{font-size:22px;margin:0 0 20px}
label{display:block;margin-bottom:8px;color:#a9b0bf}
textarea{width:100%;box-sizing:border-box;padding:12px;font:15px/1.4 ui-monospace,monospace;color:inherit;background:#141821;border:1px solid #2a2f3a;border-radius:12px}
button{margin-top:16px;width:100%;padding:14px;font-size:17px;font-weight:600;color:#0b0d12;background:#b4f03c;border:0;border-radius:14px}
.error{color:#ff8a8a}
.done{font-size:18px}
</style>
</head>
<body><main>
$main
</main></body>
</html>"""

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
