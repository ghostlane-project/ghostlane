package org.olcbox.app

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.rustcore.RustCoreEngine
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

// What RustKindsE2ETest, RustBlockedOnlyE2ETest and RustRetiredCarrierE2ETest share: the app's
// service driven as RustCoreVpnE2ETest drives it, and a line's far end on loopback for each kind
// the session writes from a link. No test here: the e2e script runs a file only when it holds one.

/**
 * The app's own service on the Rust core, driven from the test's process. Written for every API
 * level the app runs on (23 up): a start is a foreground start only where Android has one, the
 * notification grant is asked only where the permission exists, and nothing is fetched by the
 * shell (no `nc` on the oldest images): a fetch goes from here to the core's SOCKS port, with the
 * login the start gave the tun's session.
 */
internal class RustE2eService(private val instrumentation: Instrumentation) {
    val context: Context = instrumentation.targetContext
    private val pkg = context.packageName
    val user = "u${System.nanoTime() % 100000}"
    val pass = "p${System.nanoTime() % 100000}x"
    private var mark = ""

    fun say(line: String) {
        Log.i(TAG, line)
    }

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun consent() {
        shell("appops set $pkg ACTIVATE_VPN allow")
        android.net.VpnService.prepare(context)?.let { c ->
            context.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the VPN consent to settle", 15_000) { android.net.VpnService.prepare(context) == null }
        }
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
    }

    /** [location] as the one profile in the app's store, active, with [routing]. */
    fun save(location: LocationConfig, routing: RoutingSettings = RoutingSettings()) = runBlocking {
        LocationsDataSourceImpl(context).saveLocationBundle(
            LocationBundleV4(
                activeLocationId = "e2e",
                locations = listOf(LocationEntry.from("e2e", location)),
                routing = routing
            )
        )
    }

    /** Starts the service on the Rust core, in [mode], with this test's login; only the shell's traffic is sent into the tun. */
    fun start(mode: String = "tun") {
        mark = "e2e mark ${System.nanoTime()}"
        OlcboxVpnState.addLog(mark)
        val intent = Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME)
            .setAction(OlcboxVpnActions.ACTION_START_VPN)
            .putExtra(OlcboxVpnActions.EXTRA_RUST_CORE, true)
            .putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, mode)
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, user)
            .putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, pass)
            .putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, "proxy_selected")
            .putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS, arrayListOf("com.android.shell"))
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stop() {
        context.startService(
            Intent().setClassName(pkg, OlcboxVpnActions.SERVICE_CLASS_NAME).setAction(OlcboxVpnActions.ACTION_STOP_VPN)
        )
        waitFor("the status Disconnected", 30_000) { OlcboxVpnState.status.value is VpnStatus.Disconnected }
        waitFor("the Rust core to stop", 10_000) { !RustCoreEngine.isCoreRunning() }
        waitFor("the service to end", 20_000) { !serviceRunning() }
        waitFor("the tun fd to close", 10_000) { tunFds().isEmpty() }
    }

    fun connected(): Boolean = OlcboxVpnState.status.value is VpnStatus.Connected

    /** The status's sentence while it is an error, else null. */
    fun error(): String? = (OlcboxVpnState.status.value as? VpnStatus.Error)?.message

    /** What the session logged since the last [start] (all of the log once that line has rolled out of it). */
    fun logSinceStart(): List<String> {
        val all = OlcboxVpnState.logs.value
        val at = all.lastIndexOf(mark)
        return if (at < 0) all else all.subList(at + 1, all.size)
    }

    fun logged(needle: String): Boolean = logSinceStart().any { it.contains(needle) }

    /** The core's SOCKS port, read from the line the session logs once the core listens on it. */
    fun corePort(): Int {
        val line = logSinceStart().lastOrNull { it.contains(READY) }
            ?: throw AssertionError("the session logged no '$READY' line")
        return line.substringAfterLast(':').trim().toInt()
    }

    /**
     * Waits for the start to end, Connected or an error (a start from here is not retried, so an
     * error is its last word), and fails with the error and the log when it is not Connected.
     */
    fun awaitConnected(what: String, detail: () -> String = { "" }) {
        waitFor("$what to connect or fail", 90_000, detail) { connected() || error() != null }
        if (!connected()) {
            val tail = logSinceStart().takeLast(40).joinToString("\n")
            throw AssertionError("$what did not connect: ${OlcboxVpnState.status.value}; ${detail()}\nlog tail:\n$tail")
        }
    }

    fun waitFor(what: String, timeoutMs: Long, detail: () -> String = { "" }, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(200)
        }
        val tail = logSinceStart().takeLast(40).joinToString("\n")
        throw AssertionError("timed out waiting for $what; status=${OlcboxVpnState.status.value}; ${detail()}\nlog tail:\n$tail")
    }

    private fun serviceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(100).any { it.service.className == OlcboxVpnActions.SERVICE_CLASS_NAME }
    }

    private fun tunFds(): List<String> =
        File("/proc/self/fd").listFiles().orEmpty()
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .filter { it.contains("/dev/tun") || it.contains("/dev/net/tun") }

    companion object {
        /** The tag the e2e script collects RESULT lines by. */
        const val TAG = "RustCoreE2E"

        /** The session's line once the core listens: "Rust core ready on <host>:<port>". */
        const val READY = "Rust core ready on"
    }
}

/** An HTTP/1.0 GET of `/e2e` from [host]:[port] through the core's SOCKS port, and what came back. */
internal object SocksFetch {
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * What came back, read until [until] is in it, the far side closes, or nothing more comes for
     * 20 s. [host] is sent as the core is handed it by hev on Android: a name as a name, an IPv4
     * literal as an address.
     */
    fun get(proxyPort: Int, user: String, pass: String, host: String, port: Int, until: String? = null): String =
        connect(proxyPort, user, pass, host, port).use { s ->
            s.getOutputStream().write("GET /e2e HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val got = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = s.getInputStream().read(buf)
                    if (n < 0) break
                    got.write(buf, 0, n)
                    if (until != null && got.toString("UTF-8").contains(until)) break
                }
            } catch (_: SocketTimeoutException) {
                // What came before the silence is the answer.
            }
            got.toString("UTF-8")
        }

    /** A SOCKS5 CONNECT with the login, or an IOException saying which step the proxy refused. */
    private fun connect(proxyPort: Int, user: String, pass: String, host: String, port: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", proxyPort), 5_000)
            s.soTimeout = 20_000
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 2))
            out.flush()
            if (inp.readUnsignedByte() != 5) throw IOException("not a SOCKS5 server")
            val method = inp.readUnsignedByte()
            if (method != 2) throw IOException("the proxy did not take a login (method $method)")
            val u = user.toByteArray()
            val p = pass.toByteArray()
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            out.flush()
            inp.readUnsignedByte()
            if (inp.readUnsignedByte() != 0) throw IOException("the proxy refused the login")
            val address = if (IPV4.matches(host)) {
                byteArrayOf(1) + InetAddress.getByName(host).address
            } else {
                byteArrayOf(3, host.length.toByte()) + host.toByteArray(Charsets.US_ASCII)
            }
            out.write(byteArrayOf(5, 1, 0) + address + byteArrayOf((port shr 8).toByte(), port.toByte()))
            out.flush()
            inp.readUnsignedByte()
            val reply = inp.readUnsignedByte()
            if (reply != 0) throw IOException("CONNECT to $host:$port refused, reply $reply")
            inp.readUnsignedByte()
            when (inp.readUnsignedByte()) {
                1 -> inp.readFully(ByteArray(4 + 2))
                4 -> inp.readFully(ByteArray(16 + 2))
                3 -> inp.readFully(ByteArray(inp.readUnsignedByte() + 2))
            }
            return s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }
}

/** Where the core asked a far end to go, and the stream both ways once the far end has read the request. */
internal class FarStream(val host: String, val port: Int, val input: InputStream, val output: OutputStream)

/** One kind's server side: reads the core's request off a connection and answers its handshake. */
internal fun interface FarProtocol {
    fun open(socket: Socket): FarStream
}

/**
 * A line's far end on loopback, speaking one kind ([protocol]). A destination in [answered] (an
 * address nothing reaches, or a name the test must not reach) it answers itself, with an HTTP page
 * holding [body]; any other it relays, so the app's own check of the tunnel gets its answer.
 * [seen] is every destination the core asked for, as `host:port`; [served] counts the pages
 * answered here; [errors] says what a request it could not read was.
 */
internal class FarEnd(
    private val protocol: FarProtocol,
    private val body: String,
    private val answered: Set<String> = setOf("192.0.2.1:80"),
    tls: Boolean = false
) : AutoCloseable {
    private val socket: ServerSocket =
        if (tls) E2eTls.serverSocket() else ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val served = AtomicInteger()
    val seen: MutableList<String> = CopyOnWriteArrayList()
    val errors: MutableList<String> = CopyOnWriteArrayList()

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { runCatching { client.use { serve(it) } } }.also { it.isDaemon = true }.start()
            }
        }.also { it.isDaemon = true }.start()
    }

    /** For a failure message: what was asked for and what could not be read. */
    fun report(): String = "far end: seen=$seen served=${served.get()} errors=$errors"

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val far = try {
            protocol.open(client)
        } catch (e: Exception) {
            if (!socket.isClosed) errors += "${e::class.java.simpleName}: ${e.message}"
            return
        }
        val target = "${far.host}:${far.port}"
        seen += target
        if (target in answered) {
            readHead(far.input)
            served.incrementAndGet()
            far.output.write(
                "HTTP/1.0 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()
            )
            far.output.flush()
            far.output.close()
        } else {
            relay(client, far)
        }
    }

    /** Reads an HTTP request's head, up to its empty line. */
    private fun readHead(input: InputStream) {
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) return
        }
    }

    private fun relay(client: Socket, far: FarStream) {
        Socket().use { remote ->
            remote.connect(InetSocketAddress(far.host, far.port), 8_000)
            val back = Thread {
                runCatching { pipe(remote.getInputStream(), far.output) }
                runCatching { far.output.close() }
                runCatching { client.close() }
            }.also { it.isDaemon = true }
            back.start()
            runCatching { pipe(far.input, remote.getOutputStream()) }
            runCatching { remote.shutdownOutput() }
            back.join(30_000)
        }
    }

    /** Copies in writes of at most 4 KiB: each is one record of the kind's, well inside its limit. */
    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            output.write(buf, 0, n)
            output.flush()
        }
    }

    override fun close() = socket.close()
}

/** A host or an address as each kind writes one: [v4], [domain] and [v6] are the kind's type bytes. */
internal fun readAddress(input: DataInputStream, v4: Int, domain: Int, v6: Int): String =
    when (val type = input.readUnsignedByte()) {
        v4 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!!
        domain -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
        v6 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
        else -> throw IOException("an address of type $type")
    }

internal fun uuidBytes(id: UUID): ByteArray =
    ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

/** VLESS (no flow), over the socket it is given: plain, or the TLS one of [FarEnd] with `tls`. */
internal class VlessFar(private val id: UUID) : FarProtocol {
    override fun open(socket: Socket): FarStream {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        if (input.readUnsignedByte() != 0) throw IOException("VLESS: not version 0")
        val user = ByteArray(16).also { input.readFully(it) }
        if (!user.contentEquals(uuidBytes(id))) throw IOException("VLESS: another user's id")
        input.readFully(ByteArray(input.readUnsignedByte()))
        val command = input.readUnsignedByte()
        if (command != 1) throw IOException("VLESS: command $command, not a stream")
        val port = input.readUnsignedShort()
        val host = readAddress(input, v4 = 1, domain = 2, v6 = 3)
        output.write(byteArrayOf(0, 0))
        output.flush()
        return FarStream(host, port, input, output)
    }
}

/** Trojan, inside the TLS of [FarEnd] with `tls`: the password's SHA-224 in hex, then a SOCKS-style address. */
internal class TrojanFar(password: String) : FarProtocol {
    private val key = MessageDigest.getInstance("SHA-224").digest(password.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun open(socket: Socket): FarStream {
        val input = DataInputStream(socket.getInputStream())
        val hash = String(ByteArray(56).also { input.readFully(it) }, Charsets.US_ASCII)
        if (hash != key) throw IOException("Trojan: another password")
        crlf(input)
        val command = input.readUnsignedByte()
        if (command != 1) throw IOException("Trojan: command $command, not a stream")
        val host = readAddress(input, v4 = 1, domain = 3, v6 = 4)
        val port = input.readUnsignedShort()
        crlf(input)
        return FarStream(host, port, input, socket.getOutputStream())
    }

    private fun crlf(input: DataInputStream) {
        if (input.readUnsignedByte() != 0x0d || input.readUnsignedByte() != 0x0a) throw IOException("Trojan: no CRLF")
    }
}

internal fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray?, data: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    if (aad != null) c.updateAAD(aad)
    return c.doFinal(data)
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }.doFinal(data)

/** [buf] filled from [input]: false when the stream ended before its first byte, an EOFException when it ended inside. */
internal fun readFullyOrEnd(input: InputStream, buf: ByteArray): Boolean {
    var got = 0
    while (got < buf.size) {
        val n = input.read(buf, got, buf.size - got)
        if (n < 0) {
            if (got == 0) return false
            throw EOFException("the stream ended inside a record")
        }
        got += n
    }
    return true
}

/**
 * Shadowsocks AEAD (SIP004) with aes-128-gcm: the key from the password as OpenSSL's
 * EVP_BytesToKey makes it, a subkey per direction from that direction's salt (HKDF-SHA1,
 * "ss-subkey"), records of a sealed length and a sealed payload, the nonce a little-endian count.
 */
internal class ShadowsocksFar(password: String) : FarProtocol {
    private val key = bytesToKey(password.toByteArray(), 16)

    override fun open(socket: Socket): FarStream {
        val raw = DataInputStream(socket.getInputStream())
        val salt = ByteArray(SALT).also { raw.readFully(it) }
        val input = DataInputStream(Reader(raw, Aead(subkey(salt))))
        val host = readAddress(input, v4 = 1, domain = 3, v6 = 4)
        val port = input.readUnsignedShort()
        val out = socket.getOutputStream()
        val ours = ByteArray(SALT).also { SecureRandom().nextBytes(it) }
        out.write(ours)
        out.flush()
        return FarStream(host, port, input, Writer(out, Aead(subkey(ours))))
    }

    private fun subkey(salt: ByteArray): ByteArray {
        val prk = hmac("HmacSHA1", salt, key)
        return hmac("HmacSHA1", prk, "ss-subkey".toByteArray() + byteArrayOf(1)).copyOf(16)
    }

    private class Aead(private val key: ByteArray) {
        private val nonce = ByteArray(12)

        fun seal(data: ByteArray): ByteArray = next(Cipher.ENCRYPT_MODE, data)

        fun open(data: ByteArray): ByteArray = next(Cipher.DECRYPT_MODE, data)

        private fun next(mode: Int, data: ByteArray): ByteArray {
            val out = gcm(mode, key, nonce.copyOf(), null, data)
            for (i in nonce.indices) {
                nonce[i] = (nonce[i] + 1).toByte()
                if (nonce[i] != 0.toByte()) break
            }
            return out
        }
    }

    private class Reader(private val raw: DataInputStream, private val aead: Aead) : InputStream() {
        private var chunk = ByteArray(0)
        private var at = 0

        private fun fill(): Boolean {
            while (at >= chunk.size) {
                val head = ByteArray(2 + TAG)
                if (!readFullyOrEnd(raw, head)) return false
                val plain = aead.open(head)
                val size = (((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)) and MAX
                val body = ByteArray(size + TAG).also { raw.readFully(it) }
                chunk = aead.open(body)
                at = 0
            }
            return true
        }

        override fun read(): Int = if (!fill()) -1 else chunk[at++].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }
    }

    private class Writer(private val raw: OutputStream, private val aead: Aead) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            while (at < off + len) {
                val n = minOf(off + len - at, MAX)
                raw.write(aead.seal(byteArrayOf((n shr 8).toByte(), n.toByte())))
                raw.write(aead.seal(b.copyOfRange(at, at + n)))
                at += n
            }
            raw.flush()
        }

        override fun flush() = raw.flush()

        override fun close() = raw.close()
    }

    private companion object {
        const val SALT = 16
        const val TAG = 16
        const val MAX = 0x3fff

        fun bytesToKey(password: ByteArray, size: Int): ByteArray {
            var key = ByteArray(0)
            var prev = ByteArray(0)
            while (key.size < size) {
                prev = MessageDigest.getInstance("MD5").digest(prev + password)
                key += prev
            }
            return key.copyOf(size)
        }
    }
}

/**
 * VMess AEAD as the core's client sends it, with aes-128-gcm for the body: the header sealed under
 * keys the command key derives (the nested-HMAC KDF), records with the length masked and padding
 * drawn from SHAKE128 of the direction's IV (options 0x0d: chunk stream, masking, padding), the
 * answer's keys the SHA-256 of the request's. The id is not checked against the auth id's clock
 * (a real server's replay guard): the header would not open under another id's key.
 */
internal class VmessFar(id: UUID) : FarProtocol {
    private val commandKey = MessageDigest.getInstance("MD5")
        .digest(uuidBytes(id) + "c48619fe-8f02-49e0-b9e9-edf763e17e21".toByteArray())

    override fun open(socket: Socket): FarStream {
        val raw = DataInputStream(socket.getInputStream())
        val authId = ByteArray(16).also { raw.readFully(it) }
        val sealedLength = ByteArray(2 + 16).also { raw.readFully(it) }
        val nonce = ByteArray(8).also { raw.readFully(it) }
        val length = gcm(
            Cipher.DECRYPT_MODE,
            kdf(commandKey, "VMess Header AEAD Key_Length".toByteArray(), authId, nonce).copyOf(16),
            kdf(commandKey, "VMess Header AEAD Nonce_Length".toByteArray(), authId, nonce).copyOf(12),
            authId,
            sealedLength
        ).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
        val header = gcm(
            Cipher.DECRYPT_MODE,
            kdf(commandKey, "VMess Header AEAD Key".toByteArray(), authId, nonce).copyOf(16),
            kdf(commandKey, "VMess Header AEAD Nonce".toByteArray(), authId, nonce).copyOf(12),
            authId,
            ByteArray(length + 16).also { raw.readFully(it) }
        )
        val h = DataInputStream(ByteArrayInputStream(header))
        if (h.readUnsignedByte() != 1) throw IOException("VMess: not version 1")
        val iv = ByteArray(16).also { h.readFully(it) }
        val key = ByteArray(16).also { h.readFully(it) }
        val marker = h.readUnsignedByte()
        val options = h.readUnsignedByte()
        val paddingAndCipher = h.readUnsignedByte()
        h.readUnsignedByte()
        val command = h.readUnsignedByte()
        if (options != 0x0d) throw IOException("VMess: options $options, not 0x0d (chunk stream, masking, padding)")
        if ((paddingAndCipher and 0x0f) != 3) throw IOException("VMess: body cipher ${paddingAndCipher and 0x0f}, not aes-128-gcm")
        if (command != 1) throw IOException("VMess: command $command, not a stream")
        val port = h.readUnsignedShort()
        val host = readAddress(h, v4 = 1, domain = 2, v6 = 3)
        h.readFully(ByteArray(paddingAndCipher shr 4))
        var check = 0x811c9dc5L.toInt()
        for (i in 0 until header.size - 4) check = (check xor (header[i].toInt() and 0xff)) * 0x01000193
        if (h.readInt() != check) throw IOException("VMess: the header's check does not match")

        val out = socket.getOutputStream()
        val answerKey = sha256(key).copyOf(16)
        val answerIv = sha256(iv).copyOf(16)
        out.write(
            gcm(
                Cipher.ENCRYPT_MODE,
                kdf(answerKey, "AEAD Resp Header Len Key".toByteArray()).copyOf(16),
                kdf(answerIv, "AEAD Resp Header Len IV".toByteArray()).copyOf(12),
                null,
                byteArrayOf(0, 4)
            )
        )
        out.write(
            gcm(
                Cipher.ENCRYPT_MODE,
                kdf(answerKey, "AEAD Resp Header Key".toByteArray()).copyOf(16),
                kdf(answerIv, "AEAD Resp Header IV".toByteArray()).copyOf(12),
                null,
                byteArrayOf(marker.toByte(), 0, 0, 0)
            )
        )
        out.flush()
        return FarStream(host, port, Reader(raw, key, iv), Writer(out, answerKey, answerIv))
    }

    /** One direction's record nonces: the count big-endian in the first two bytes, then the IV's bytes 2 to 11. */
    private class Nonces(private val iv: ByteArray) {
        private var count = 0

        fun next(): ByteArray {
            val nonce = iv.copyOf(12)
            nonce[0] = (count shr 8).toByte()
            nonce[1] = count.toByte()
            count++
            return nonce
        }
    }

    private class Reader(private val raw: DataInputStream, private val key: ByteArray, iv: ByteArray) : InputStream() {
        private val mask = Shake128(iv)
        private val nonces = Nonces(iv)
        private var chunk = ByteArray(0)
        private var at = 0
        private var ended = false

        private fun fill(): Boolean {
            while (!ended && at >= chunk.size) {
                val head = ByteArray(2)
                if (!readFullyOrEnd(raw, head)) {
                    ended = true
                    break
                }
                val padding = mask.next() % 64
                val size = (((head[0].toInt() and 0xff) shl 8) or (head[1].toInt() and 0xff)) xor mask.next()
                if (size < 16 + padding) throw IOException("VMess: a record of $size bytes with $padding of padding")
                val body = ByteArray(size).also { raw.readFully(it) }
                chunk = gcm(Cipher.DECRYPT_MODE, key, nonces.next(), null, body.copyOf(size - padding))
                at = 0
                if (chunk.isEmpty()) ended = true
            }
            return at < chunk.size
        }

        override fun read(): Int = if (!fill()) -1 else chunk[at++].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }
    }

    private class Writer(private val raw: OutputStream, private val key: ByteArray, iv: ByteArray) : OutputStream() {
        private val mask = Shake128(iv)
        private val nonces = Nonces(iv)
        private var ended = false

        private fun record(payload: ByteArray) {
            val padding = mask.next() % 64
            val length = (payload.size + 16 + padding) xor mask.next()
            raw.write(byteArrayOf((length shr 8).toByte(), length.toByte()))
            raw.write(gcm(Cipher.ENCRYPT_MODE, key, nonces.next(), null, payload))
            raw.write(ByteArray(padding))
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            while (at < off + len) {
                val n = minOf(off + len - at, 4096)
                record(b.copyOfRange(at, at + n))
                at += n
            }
            raw.flush()
        }

        override fun flush() = raw.flush()

        /** The empty record that ends the stream, then the socket's own close. */
        override fun close() {
            if (!ended) {
                ended = true
                runCatching {
                    record(ByteArray(0))
                    raw.flush()
                }
            }
            raw.close()
        }
    }

    private companion object {
        /** VMess's KDF: HMAC-SHA256 nested once per element of the path, rooted at "VMess AEAD KDF". */
        fun kdf(key: ByteArray, vararg path: ByteArray): ByteArray =
            nested(listOf("VMess AEAD KDF".toByteArray()) + path.toList(), key)

        private fun nested(path: List<ByteArray>, data: ByteArray): ByteArray {
            if (path.isEmpty()) return sha256(data)
            val prefix = path.subList(0, path.size - 1)
            val secret = path[path.size - 1]
            val block = ByteArray(64)
            val k = if (secret.size > 64) nested(prefix, secret) else secret
            System.arraycopy(k, 0, block, 0, k.size)
            val inner = nested(prefix, ByteArray(64) { (block[it].toInt() xor 0x36).toByte() } + data)
            return nested(prefix, ByteArray(64) { (block[it].toInt() xor 0x5c).toByte() } + inner)
        }
    }
}

/** SHAKE128 of a seed shorter than one block, read two bytes at a time as VMess's masking reads it. */
internal class Shake128(seed: ByteArray) {
    private val lanes = LongArray(25)
    private val block = ByteArray(RATE)
    private var at = RATE

    init {
        require(seed.size < RATE)
        val padded = ByteArray(RATE)
        System.arraycopy(seed, 0, padded, 0, seed.size)
        padded[seed.size] = 0x1f
        padded[RATE - 1] = (padded[RATE - 1].toInt() or 0x80).toByte()
        for (i in 0 until RATE / 8) {
            var v = 0L
            for (k in 7 downTo 0) v = (v shl 8) or (padded[8 * i + k].toLong() and 0xff)
            lanes[i] = lanes[i] xor v
        }
    }

    /** The next two bytes, big-endian. */
    fun next(): Int {
        val hi = byte()
        val lo = byte()
        return (hi shl 8) or lo
    }

    private fun byte(): Int {
        if (at == RATE) {
            permute()
            for (i in 0 until RATE / 8) {
                for (b in 0 until 8) block[8 * i + b] = (lanes[i] ushr (8 * b)).toByte()
            }
            at = 0
        }
        return block[at++].toInt() and 0xff
    }

    private fun permute() {
        val a = lanes
        val c = LongArray(5)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
            }
            var current = a[1]
            for (t in 0 until 24) {
                val j = PI[t]
                val saved = a[j]
                a[j] = java.lang.Long.rotateLeft(current, RHO[t])
                current = saved
            }
            for (y in 0 until 25 step 5) {
                val row = LongArray(5) { a[y + it] }
                for (x in 0 until 5) a[y + x] = row[x] xor (row[(x + 1) % 5].inv() and row[(x + 2) % 5])
            }
            a[0] = a[0] xor RC[round]
        }
    }

    private companion object {
        const val RATE = 168
        val RHO = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
        val PI = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)
        val RC = longArrayOf(
            0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(), 0x800000000000808AuL.toLong(), 0x8000000080008000uL.toLong(),
            0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
            0x000000000000008AuL.toLong(), 0x0000000000000088uL.toLong(), 0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
            0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(), 0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
            0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(), 0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(),
            0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
        )
    }
}

/**
 * A TLS server socket on loopback whose certificate is made at run time, self-signed for [NAME]
 * (and 127.0.0.1): no key is kept in the tree. The links that lead to it waive the certificate
 * check (`allowInsecure=1`), as a link to such a server does.
 */
internal object E2eTls {
    const val NAME = "e2e.test"

    fun serverSocket(): ServerSocket {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val password = "e2e".toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("e2e", pair.private, password, arrayOf<Certificate>(selfSigned(pair)))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        return tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    }

    /** An X.509 v3 certificate for [pair]'s public key, signed by its private key: SHA-256 with RSA, a SAN for [NAME] and 127.0.0.1. */
    private fun selfSigned(pair: KeyPair): X509Certificate {
        val algorithm = der(0x30, der(0x06, bytes(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b)), bytes(0x05, 0x00))
        val name = der(0x30, der(0x31, der(0x30, der(0x06, bytes(0x55, 0x04, 0x03)), der(0x0c, NAME.toByteArray()))))
        val validity = der(0x30, der(0x17, "250101000000Z".toByteArray()), der(0x17, "491231235959Z".toByteArray()))
        val san = der(
            0x30,
            der(0x06, bytes(0x55, 0x1d, 0x11)),
            der(0x04, der(0x30, der(0x82, NAME.toByteArray()), der(0x87, bytes(127, 0, 0, 1))))
        )
        val tbs = der(
            0x30,
            der(0xa0, der(0x02, bytes(2))),
            der(0x02, BigInteger(64, SecureRandom()).add(BigInteger.ONE).toByteArray()),
            algorithm,
            name,
            validity,
            name,
            pair.public.encoded,
            der(0xa3, der(0x30, san))
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(tbs)
        }.sign()
        val cert = der(0x30, tbs, algorithm, der(0x03, bytes(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** One DER element: [tag], its length (short or long form, to 64 KiB), then [parts] one after another. */
    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val n = body.size
        val length = when {
            n < 0x80 -> bytes(n)
            n < 0x100 -> bytes(0x81, n)
            else -> bytes(0x82, n shr 8, n and 0xff)
        }
        return bytes(tag) + length + body
    }
}
