package org.ghostlane.lab.olcffi

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.ghostlane.olcrtc.LogWriter
import org.ghostlane.olcrtc.OlcrtcRuntime
import org.ghostlane.olcrtc.SessionListener
import org.ghostlane.olcrtc.SocketProtector
import org.ghostlane.olcrtc.memoryStats
import org.json.JSONObject

// The Android lab of the olcRTC port's M9: OlcrtcRuntime (the Rust client
// through UniFFI and JNA, in libghostlane_core.so with the xray core and one
// Tokio runtime) driven as OlcboxVpnService drives the Go engine today,
// against the T1 hub and the pinned Go server on the runner
// (zz_t1_host_test.go), which the emulator reaches as 10.0.2.2. RESULT and
// MEM lines go to logcat under TAG and from there to the job summary.

const val TAG = "OlcFfiLab"

fun say(line: String) {
    Log.i(TAG, line)
}

/** What zz_t1_host_test.go writes, handed in as instrumentation arguments
 * (run-emulator-tests.sh: the room's host is 10.0.2.2 here). */
data class T1(
    val room: String,
    val key: String,
    val transport: String,
    val dns: String,
    val origin: String,
    val big: String,
    val bigBytes: Long,
    val echoPort: Int,
    val echoIp: String,
) {
    companion object {
        fun load(): T1 {
            val a = InstrumentationRegistry.getArguments()
            fun s(k: String): String = a.getString(k) ?: error("instrumentation argument $k is missing")
            return T1(
                room = s("room"), key = s("key"), transport = s("transport"), dns = s("dns"),
                origin = s("origin"), big = s("big"), bigBytes = s("big_bytes").toLong(),
                // "_" is run-emulator-tests.sh's empty value (adb shell drops "").
                echoPort = s("echo_port").toInt(), echoIp = a.getString("echo_ip").orEmpty().removePrefix("_"),
            )
        }

        fun arg(name: String, default: String): String =
            InstrumentationRegistry.getArguments().getString(name) ?: default
    }
}

const val USER = "lab"
val PASS = "lab-${(1..Int.MAX_VALUE).random()}"

/**
 * OlcboxVpnService's order (startMobile): the callbacks first
 * (installMobileCallbacks: the caller's), then configureMobileTransport (the
 * transport, the resolver, the listen host, VP8, the direct rules, UDP, the
 * DTLS profile), then the provider, the one room (setRoom only: Android adds
 * no failover room), the device, the key, the SOCKS port and its login. The
 * interop pipe's engine is the lab's one call more.
 */
fun configure(rt: OlcrtcRuntime, t1: T1, port: Int) {
    rt.setTransport(t1.transport)
    rt.setDns(t1.dns)
    rt.setSocksListenHost("127.0.0.1")
    rt.setVp8Options(60, 64)
    rt.setDirectRules("")
    rt.setUdp(true)
    rt.setDtlsProfile("off")
    rt.setProvider("none")
    rt.setEngine("interop-pipe", "", "")
    rt.setRoom(t1.room)
    rt.setDeviceId("lab-emulator")
    rt.setKey(t1.key)
    rt.setSocksPort(port.toLong())
    rt.setSocksCredentials(USER, PASS)
}

// MARK: the callbacks, in the service's two signatures

/** Mobile.setLogWriter's writer: every line, as the service gets it. */
class Lines : LogWriter {
    private val lines = Collections.synchronizedList(mutableListOf<String>())

    override fun writeLog(msg: String) {
        lines.add(msg)
        Log.v("olcrtc", msg.trimEnd())
    }

    fun all(): List<String> = synchronized(lines) { lines.toList() }

    /** As handleRtcLine matches: the line trimmed and lowercased. */
    fun count(s: String): Int = all().count { it.trimEnd().lowercase().contains(s) }
}

/** RoomKeeper's part on iOS (the Android service sets no listener). */
class Keeper : SessionListener {
    val sessions: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    override fun onSessionOpened(room: String, sessionId: String) {
        sessions.add(room to sessionId)
    }
}

/**
 * VpnService.protect's place. The service's protector answers
 * `this.protect(fd.toInt())`; here each descriptor is checked to be a socket
 * (getsockname) that is not connected yet (getpeername says ENOTCONN), which
 * is when protect must come, and counted.
 */
class Protector : SocketProtector {
    val calls = AtomicInteger()
    val sockets = AtomicInteger()
    val unconnected = AtomicInteger()
    val bad: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Each protected socket's `socket:[inode]`, which outlives its fd number. */
    val inodes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override fun protect(fd: Long): Boolean {
        calls.incrementAndGet()
        runCatching { Os.readlink("/proc/self/fd/$fd") }.getOrNull()?.let { inodes.add(it) }
        try {
            ParcelFileDescriptor.fromFd(fd.toInt()).use { pfd ->
                Os.getsockname(pfd.fileDescriptor)
                sockets.incrementAndGet()
                try {
                    Os.getpeername(pfd.fileDescriptor)
                    bad.add("fd $fd is already connected")
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.ENOTCONN) unconnected.incrementAndGet() else bad.add("fd $fd: $e")
                }
            }
        } catch (e: Exception) {
            bad.add("fd $fd: $e")
        }
        return fd >= 0
    }
}

/**
 * The process's IP sockets connected off loopback, `socket:[inode]` to the
 * peer: the engine's own (the test dials only 127.0.0.1), each of which
 * must have gone through the protector.
 */
fun outboundSockets(): Map<String, String> {
    val out = mutableMapOf<String, String>()
    File("/proc/self/fd").listFiles()?.forEach { f ->
        val link = runCatching { Os.readlink(f.path) }.getOrNull() ?: return@forEach
        val fd = f.name.toIntOrNull() ?: return@forEach
        if (!link.startsWith("socket:[")) return@forEach
        runCatching {
            ParcelFileDescriptor.fromFd(fd).use { pfd ->
                val peer = Os.getpeername(pfd.fileDescriptor)
                if (peer is InetSocketAddress && !peer.address.isLoopbackAddress) out[link] = "$peer"
            }
        }
    }
    return out
}

// MARK: sockets

fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

private fun skipAddress(i: DataInputStream, atyp: Int) {
    when (atyp) {
        1 -> i.skipBytes(4 + 2)
        4 -> i.skipBytes(16 + 2)
        3 -> i.skipBytes(i.readUnsignedByte() + 2)
        else -> throw IOException("reply address type $atyp")
    }
}

/** RFC 1928's greeting with RFC 1929's login, as hev sends it. */
private fun greet(s: Socket, user: String, pass: String): DataInputStream {
    val o = s.getOutputStream()
    val i = DataInputStream(s.getInputStream())
    o.write(byteArrayOf(5, 1, 2))
    o.flush()
    val method = ByteArray(2).also { i.readFully(it) }
    if (method[0] != 5.toByte() || method[1] != 2.toByte()) throw IOException("method ${method.toList()}")
    val u = user.toByteArray()
    val p = pass.toByteArray()
    o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
    o.flush()
    val auth = ByteArray(2).also { i.readFully(it) }
    if (auth[1] != 0.toByte()) throw IOException("login refused: ${auth.toList()}")
    return i
}

private fun open(port: Int): Socket = Socket().apply {
    soTimeout = 20_000
    connect(InetSocketAddress("127.0.0.1", port), 5_000)
}

/** A CONNECT by name through the SOCKS port with the login. */
fun socksConnect(port: Int, user: String, pass: String, host: String, dstPort: Int): Socket {
    val s = open(port)
    try {
        val i = greet(s, user, pass)
        val h = host.toByteArray()
        s.getOutputStream().apply {
            write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((dstPort shr 8).toByte(), dstPort.toByte()))
            flush()
        }
        val head = ByteArray(4).also { i.readFully(it) }
        if (head[1] != 0.toByte()) throw IOException("CONNECT refused: ${head[1]}")
        skipAddress(i, head[3].toInt())
        return s
    } catch (e: Throwable) {
        s.close()
        throw e
    }
}

/** GET `url` (plain HTTP/1.0) through the SOCKS port: the status line and
 * the body's length; the body is read and dropped. */
fun fetch(url: String, port: Int, user: String = USER, pass: String = PASS): Pair<String, Long> {
    val u = URI(url)
    socksConnect(port, user, pass, u.host, u.port).use { s ->
        s.getOutputStream().apply {
            write("GET ${u.path} HTTP/1.0\r\nHost: ${u.host}:${u.port}\r\n\r\n".toByteArray())
            flush()
        }
        return readResponse(s.getInputStream())
    }
}

private fun readResponse(input: InputStream): Pair<String, Long> {
    val head = StringBuilder()
    var last4 = 0
    while (true) {
        val b = input.read()
        if (b < 0) throw IOException("closed in the response head: $head")
        head.append(b.toChar())
        last4 = (last4 shl 8) or b
        if (last4 == 0x0d0a0d0a) break
        if (head.length > 16 shl 10) throw IOException("no end of the response head")
    }
    val buf = ByteArray(16 shl 10)
    var body = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        body += n
    }
    return head.lineSequence().first() to body
}

/**
 * UDP ASSOCIATE, as hev's `udp: 'udp'` (HevTunnelConfig, the service's
 * setting) asks: the association on a TCP connection with the login, then
 * `payload` to `dst`:`dport` through the relay, both ways; the milliseconds
 * the first echo took.
 */
fun udpAssociateEcho(port: Int, dst: String, dport: Int, payload: ByteArray): Long {
    return open(port).use { s ->
        val i = greet(s, USER, PASS)
        s.getOutputStream().apply {
            write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            flush()
        }
        val head = ByteArray(4).also { i.readFully(it) }
        if (head[1] != 0.toByte()) throw IOException("UDP ASSOCIATE refused: ${head[1]}")
        val relayIp = when (head[3].toInt()) {
            1 -> ByteArray(4).also { i.readFully(it) }
            4 -> ByteArray(16).also { i.readFully(it) }
            else -> throw IOException("relay address type ${head[3]}")
        }
        val relayPort = i.readUnsignedShort()
        val relayAddr = InetAddress.getByAddress(relayIp).let { if (it.isAnyLocalAddress) InetAddress.getByName("127.0.0.1") else it }
        val dstIp = InetAddress.getByName(dst).address
        val packet = byteArrayOf(0, 0, 0, 1) + dstIp + byteArrayOf((dport shr 8).toByte(), dport.toByte()) + payload
        DatagramSocket().use { d ->
            d.soTimeout = 1_000
            val t0 = System.nanoTime()
            repeat(15) {
                d.send(DatagramPacket(packet, packet.size, InetSocketAddress(relayAddr, relayPort)))
                val buf = ByteArray(4096)
                val r = DatagramPacket(buf, buf.size)
                try {
                    d.receive(r)
                } catch (e: SocketTimeoutException) {
                    return@repeat
                }
                val at = when (buf[3].toInt()) {
                    1 -> 4 + 4 + 2
                    4 -> 4 + 16 + 2
                    3 -> 4 + 1 + (buf[4].toInt() and 0xff) + 2
                    else -> return@repeat
                }
                if (buf.copyOfRange(at, r.length).contentEquals(payload)) {
                    return (System.nanoTime() - t0) / 1_000_000
                }
            }
            throw IOException("no echo within 15 s")
        }
    }
}

// MARK: memory

fun stats(): JSONObject {
    val line = memoryStats()
    check(!line.contains('\n')) { line }
    return JSONObject(line)
}

/** bionic's mallopt, for the measurement (JNA, libc). */
interface LibC : Library {
    fun mallopt(param: Int, value: Int): Int
}

val libc: LibC by lazy { Native.load("c", LibC::class.java) }

/** `<malloc.h>`: M_PURGE (API 28), M_PURGE_ALL (API 34). */
const val M_PURGE = -101
const val M_PURGE_ALL = -104

/** The process's memory in kB: smaps_rollup's Rss, Pss and Pss_Anon, and
 * the Rss and Pss of the native allocator's own mappings (Scudo's are
 * named `[anon:scudo:...]`), with mallinfo's view (Debug's native heap). */
data class Mem(
    val rss: Long,
    val pss: Long,
    val pssAnon: Long,
    val allocRss: Long,
    val allocPss: Long,
    val allocMaps: Int,
    val nativeAllocated: Long,
    val nativeSize: Long,
)

fun readMem(): Mem {
    val rollup = mutableMapOf<String, Long>()
    File("/proc/self/smaps_rollup").forEachLine { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 2 && parts[0].endsWith(":")) parts[1].toLongOrNull()?.let { rollup[parts[0].dropLast(1)] = it }
    }
    var allocRss = 0L
    var allocPss = 0L
    var maps = 0
    var inAlloc = false
    val header = Regex("^[0-9a-f]+-[0-9a-f]+ .*")
    File("/proc/self/smaps").forEachLine { line ->
        if (header.matches(line)) {
            inAlloc = line.contains("[anon:scudo:") || line.contains("[anon:libc_malloc]")
            if (inAlloc) maps++
        } else if (inAlloc) {
            val parts = line.trim().split(Regex("\\s+"))
            when (parts[0]) {
                "Rss:" -> allocRss += parts[1].toLong()
                "Pss:" -> allocPss += parts[1].toLong()
            }
        }
    }
    return Mem(
        rss = rollup["Rss"] ?: -1, pss = rollup["Pss"] ?: -1, pssAnon = rollup["Pss_Anon"] ?: -1,
        allocRss = allocRss, allocPss = allocPss, allocMaps = maps,
        nativeAllocated = Debug.getNativeHeapAllocatedSize() / 1024, nativeSize = Debug.getNativeHeapSize() / 1024,
    )
}

fun mem(label: String): Mem {
    val m = readMem()
    val s = runCatching { stats() }.getOrNull()
    val heap = s?.optJSONObject("heap")
    val rt = s?.optJSONObject("rt")
    say(
        "MEM $label pss=${m.pss}kB rss=${m.rss}kB pss_anon=${m.pssAnon}kB alloc_pss=${m.allocPss}kB " +
            "alloc_rss=${m.allocRss}kB alloc_maps=${m.allocMaps} mallinfo_alloc=${m.nativeAllocated}kB " +
            "native_heap=${m.nativeSize}kB heap_live=${heap?.opt("live")} heap_peak=${heap?.opt("peak")} " +
            "rt_threads=${rt?.opt("threads")} rt_tasks=${rt?.opt("tasks")}"
    )
    return m
}
