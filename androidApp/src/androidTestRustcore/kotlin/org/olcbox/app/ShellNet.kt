package org.olcbox.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * A client the shell runs, so that its traffic is the shell's (uid 2000): the
 * one uid besides this app's that the end-to-end tests can send through the
 * tun or keep out of it with the app lists. It is started with app_process,
 * from the test APK as it is installed, on every API level the app supports
 * (toybox has no `nc` on Android 6 and 7, and the instrumentation's two-way
 * shell needs Android 12):
 *
 *     app_process -Djava.class.path=<test apk>:<app apk> /system/bin org.olcbox.app.ShellNet <mode> ...
 *
 *   fetch <host> <port> <path> <timeout ms> <tries> <needle>
 *       an HTTP/1.0 GET, tried until an answer holds <needle> or <tries> ran out;
 *       one line per try, then `RESULT found` (exit 0) or `RESULT missing` (exit 1)
 *   load <host> <port> <path> <timeout ms> <count>
 *       <count> GETs one after another; `RESULT ok=<n> fail=<n> bytes=<n>`, exit 0
 *       when every one was answered
 *   udp <host> <port> <payload> <timeout ms>
 *       one datagram out, one back; `UDP ok <answer>` (exit 0) or `UDP fail ...`
 *
 * Written against the Java library alone: a process started this way has no
 * application around it, and the Kotlin library is only on the class path
 * because the app's APK is.
 */
object ShellNet {
    private const val KEEP = 65_536

    @JvmStatic
    fun main(args: Array<String>?) {
        if (args == null || args.size < 1) {
            usage()
            return
        }
        val mode = args[0]
        var code = 2
        if ("fetch".equals(mode) && args.size >= 7) {
            code = fetch(args[1], Integer.parseInt(args[2]), args[3], Integer.parseInt(args[4]), Integer.parseInt(args[5]), args[6])
        } else if ("load".equals(mode) && args.size >= 6) {
            code = load(args[1], Integer.parseInt(args[2]), args[3], Integer.parseInt(args[4]), Integer.parseInt(args[5]))
        } else if ("udp".equals(mode) && args.size >= 5) {
            code = udp(args[1], Integer.parseInt(args[2]), args[3], Integer.parseInt(args[4]))
        } else {
            usage()
            return
        }
        System.out.flush()
        System.exit(code)
    }

    private fun usage() {
        System.err.println("usage: ShellNet fetch <host> <port> <path> <timeout ms> <tries> <needle>")
        System.err.println("       ShellNet load <host> <port> <path> <timeout ms> <count>")
        System.err.println("       ShellNet udp <host> <port> <payload> <timeout ms>")
        System.exit(2)
    }

    private fun fetch(host: String, port: Int, path: String, timeoutMs: Int, tries: Int, needle: String): Int {
        val wanted = needle.toByteArray(StandardCharsets.UTF_8)
        var i = 1
        while (i <= tries) {
            val got = get(host, port, path, timeoutMs)
            val found = got.error == null && indexOf(got.head, got.headSize, wanted) >= 0
            if (got.error != null) {
                System.out.println("FETCH " + i + " fail ms=" + got.millis + " " + got.error)
            } else {
                System.out.println("FETCH " + i + " ok ms=" + got.millis + " bytes=" + got.bytes + " found=" + (if (found) 1 else 0) + " head=" + printable(got.head, got.headSize, 64))
            }
            if (found) {
                System.out.println("RESULT found")
                return 0
            }
            i++
            if (i <= tries) Thread.sleep(1_000)
        }
        System.out.println("RESULT missing")
        return 1
    }

    private fun load(host: String, port: Int, path: String, timeoutMs: Int, count: Int): Int {
        var ok = 0
        var failed = 0
        var bytes = 0L
        var i = 1
        while (i <= count) {
            val got = get(host, port, path, timeoutMs)
            if (got.error == null && got.bytes > 0) {
                ok++
                bytes += got.bytes
            } else {
                failed++
                System.out.println("LOAD " + i + " fail ms=" + got.millis + " " + got.error)
            }
            i++
        }
        System.out.println("RESULT ok=" + ok + " fail=" + failed + " bytes=" + bytes)
        return if (failed == 0) 0 else 1
    }

    private fun udp(host: String, port: Int, payload: String, timeoutMs: Int): Int {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            val out = payload.toByteArray(StandardCharsets.UTF_8)
            socket.send(DatagramPacket(out, out.size, InetAddress.getByName(host), port))
            val buf = ByteArray(2048)
            val back = DatagramPacket(buf, buf.size)
            socket.receive(back)
            System.out.println("UDP ok " + printable(buf, back.length, 256))
            return 0
        } catch (e: Exception) {
            System.out.println("UDP fail " + e.toString())
            return 1
        } finally {
            if (socket != null) socket.close()
        }
    }

    private class Answer(val bytes: Long, val head: ByteArray, val headSize: Int, val millis: Long, val error: String?)

    /** One GET; the first [KEEP] bytes of the answer are kept for the needle. */
    private fun get(host: String, port: Int, path: String, timeoutMs: Int): Answer {
        val started = System.currentTimeMillis()
        val head = ByteArray(KEEP)
        var kept = 0
        var total = 0L
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val request = "GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(request.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val input = socket.getInputStream()
            val buf = ByteArray(16_384)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                var j = 0
                while (j < n && kept < KEEP) {
                    head[kept] = buf[j]
                    kept++
                    j++
                }
                total += n
            }
            return Answer(total, head, kept, System.currentTimeMillis() - started, if (total == 0L) "empty answer" else null)
        } catch (e: Exception) {
            return Answer(total, head, kept, System.currentTimeMillis() - started, e.toString())
        } finally {
            if (socket != null) {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // nothing to do with a socket that will not close
                }
            }
        }
    }

    private fun indexOf(hay: ByteArray, size: Int, needle: ByteArray): Int {
        if (needle.size == 0) return 0
        var i = 0
        while (i + needle.size <= size) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    /** At most [max] bytes as ASCII, a dot for anything else, so one answer stays one line. */
    private fun printable(bytes: ByteArray, size: Int, max: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < size && i < max) {
            val c = bytes[i].toInt() and 0xff
            sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            i++
        }
        return sb.toString()
    }
}
