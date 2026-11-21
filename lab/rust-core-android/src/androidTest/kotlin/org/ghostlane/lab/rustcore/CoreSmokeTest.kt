package org.ghostlane.lab.rustcore

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.ghostlane.olcrtc.OlcrtcRuntime
import org.ghostlane.olcrtc.memoryStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** xray-ffi's C ABI (crates/xray-ffi/include/xray_ffi.h), the part the smoke needs. */
interface XrayFfi : Library {
    fun xray_ffi_version_major(): Int
    fun xray_ffi_version_minor(): Int
    fun xray_core_new(error: PointerByReference): Pointer?
    fun xray_core_load_config_json(core: Pointer, json: String, error: PointerByReference): Int
    fun xray_core_start(core: Pointer, error: PointerByReference): Int
    fun xray_core_stop(core: Pointer, error: PointerByReference): Int
    fun xray_core_free(core: Pointer)
    fun xray_error_message(error: Pointer): String?
}

/**
 * Phase 1 of the Android move to the Rust core: libghostlane_core.so
 * (built by scripts/build-ghostlane-core-android.sh, with `boring`) loads on
 * the emulator, the xray core starts from a minimal config and serves a SOCKS
 * connection to a loopback echo server, stops cleanly, and the olcRTC client's
 * UniFFI object can be made, queried and closed. No carrier room.
 */
@RunWith(AndroidJUnit4::class)
class CoreSmokeTest {
    private fun say(line: String) {
        Log.i("RustCoreSmoke", line)
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun check(what: String, status: Int, err: PointerByReference, x: XrayFfi) {
        val msg = err.value?.let { x.xray_error_message(it) }
        assertEquals("$what: ${msg ?: ""}", 0, status)
    }

    @Test
    fun theCoreStartsServesSocksAndStops() {
        val x = Native.load("ghostlane_core", XrayFfi::class.java)
        say("RESULT xray-ffi ABI ${x.xray_ffi_version_major()}.${x.xray_ffi_version_minor()}")
        assertTrue(x.xray_ffi_version_major() >= 1)

        val echo = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            runCatching {
                echo.accept().use { c ->
                    val buf = ByteArray(5)
                    DataInputStream(c.getInputStream()).readFully(buf)
                    c.getOutputStream().write(buf)
                    c.getOutputStream().flush()
                }
            }
        }.also { it.isDaemon = true; it.start() }

        val port = freePort()
        val config = """{"inbounds":[{"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":$port,
            "settings":{"udp":false}}],"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
        val err = PointerByReference()
        val core = x.xray_core_new(err) ?: error("xray_core_new returned null")
        try {
            check("load", x.xray_core_load_config_json(core, config, err), err, x)
            val t0 = System.nanoTime()
            check("start", x.xray_core_start(core, err), err, x)
            // Ready = the SOCKS port accepts.
            var up = false
            val deadline = System.currentTimeMillis() + 10_000
            while (!up && System.currentTimeMillis() < deadline) {
                up = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess
                if (!up) Thread.sleep(50)
            }
            assertTrue("the SOCKS port $port never accepted", up)
            say("RESULT core ready in ${(System.nanoTime() - t0) / 1_000_000} ms")

            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 10_000
                val o = s.getOutputStream()
                val i = DataInputStream(s.getInputStream())
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                val greet = ByteArray(2); i.readFully(greet)
                assertEquals(listOf<Byte>(5, 0), greet.toList())
                val ip = InetAddress.getByName("127.0.0.1").address
                val p = echo.localPort
                o.write(byteArrayOf(5, 1, 0, 1) + ip + byteArrayOf((p shr 8).toByte(), p.toByte())); o.flush()
                val rep = ByteArray(10); i.readFully(rep)
                assertEquals("SOCKS reply", 0, rep[1].toInt())
                o.write("hello".toByteArray()); o.flush()
                val back = ByteArray(5); i.readFully(back)
                assertEquals("hello", String(back))
            }
            say("RESULT SOCKS fetch through the core: echoed")

            val s0 = System.nanoTime()
            check("stop", x.xray_core_stop(core, err), err, x)
            say("RESULT core stopped in ${(System.nanoTime() - s0) / 1_000_000} ms")
            val refused = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) } }
            assertTrue("the SOCKS port still accepts after stop", refused.isFailure)
        } finally {
            x.xray_core_free(core)
            echo.close()
            echoThread.join(1_000)
        }
    }

    @Test
    fun theOlcClientObjectLoadsAndCloses() {
        val rt = OlcrtcRuntime()
        try {
            val stats = JSONObject(memoryStats())
            say("RESULT olc memoryStats keys: ${stats.keys().asSequence().toList()}")
            assertTrue(stats.length() > 0)
        } finally {
            rt.close()
        }
    }
}
