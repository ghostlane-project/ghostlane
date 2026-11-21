package org.olcbox.app.rustcore

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import org.ghostlane.olcrtc.LogWriter
import org.ghostlane.olcrtc.OlcrtcRuntime
import org.ghostlane.olcrtc.SessionListener
import org.ghostlane.olcrtc.SocketProtector
import org.ghostlane.olcrtc.memoryStats as coreMemoryStats
import org.ghostlane.olcrtc.setByteBudget as coreSetByteBudget
import org.ghostlane.olcrtc.setLogWriter
import org.ghostlane.olcrtc.trim as coreTrim
import org.olcbox.app.BuildConfig
import org.olcbox.app.vpn.service.RustCoreBackend
import org.olcbox.app.vpn.service.RustOlcParams

/**
 * ghostlane-core on Android: xray-rust's core through xray-ffi's C ABI (JNA, as
 * CoreEngine.swift uses it through its header) and the olcRTC client through the
 * UniFFI bindings (as OlcrtcEngine.swift). Compiled only when the build was given
 * `-PghostlaneCoreDir` (androidApp/build.gradle.kts); the service finds it by name
 * (RustCore.backend).
 *
 * A core handle takes exactly one config, so every start makes a new handle and
 * every stop frees it (CoreEngine.swift).
 */
object RustCoreEngine : RustCoreBackend {

    /** xray-ffi's C ABI (crates/xray-ffi/include/xray_ffi.h), the part the engine uses. */
    interface XrayFfi : Library {
        fun xray_ffi_version_major(): Int
        fun xray_core_new(error: PointerByReference): Pointer?
        fun xray_core_set_socket_protect_callback(core: Pointer, cb: ProtectCallback, user: Pointer?, error: PointerByReference): Int
        fun xray_core_set_log_callback(core: Pointer, cb: LogCallback, release: LogRelease, user: Pointer?, error: PointerByReference): Int
        fun xray_core_load_config_json(core: Pointer, json: String, error: PointerByReference): Int
        fun xray_core_start(core: Pointer, error: PointerByReference): Int
        fun xray_core_stop(core: Pointer, error: PointerByReference): Int
        fun xray_core_free(core: Pointer)
        fun xray_core_reset_network(core: Pointer, closed: LongByReference, error: PointerByReference): Int
        fun xray_error_message(error: Pointer): String?
        fun xray_error_free(error: Pointer)
    }

    /** `int32_t (*)(int32_t fd, void *user_data)`: nonzero is "protected". */
    interface ProtectCallback : Callback {
        fun invoke(fd: Int, user: Pointer?): Int
    }

    /** `void (*)(int32_t level, const char *line, size_t len, void *user_data)`. */
    interface LogCallback : Callback {
        fun invoke(level: Int, line: Pointer?, len: NativeLong, user: Pointer?)
    }

    interface LogRelease : Callback {
        fun invoke(user: Pointer?)
    }

    override val defaultOn: Boolean get() = BuildConfig.RUST_CORE_DEFAULT

    private val ffi: XrayFfi by lazy {
        Native.load("ghostlane_core", XrayFfi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }

    private val lock = Any()
    private var handle: Pointer? = null

    // The native side holds these for the handle's life: kept here, never collected.
    private var protectCb: ProtectCallback? = null
    private var logCb: LogCallback? = null
    private var logRelease: LogRelease? = null

    // Built on the first olcRTC start: a process that never runs olcRTC builds none.
    @Volatile
    private var olcRuntime: OlcrtcRuntime? = null

    private fun runtime(): OlcrtcRuntime = olcRuntime ?: synchronized(lock) {
        olcRuntime ?: OlcrtcRuntime().also { olcRuntime = it }
    }

    override fun load() {
        ffi.xray_ffi_version_major()
    }

    private fun message(error: PointerByReference): String {
        val e = error.value ?: return "no reason given"
        return ffi.xray_error_message(e) ?: "no reason given"
    }

    private fun freeError(error: PointerByReference) {
        error.value?.let { ffi.xray_error_free(it) }
        error.value = null
    }

    private fun failure(what: String, error: PointerByReference): IllegalStateException {
        val text = "core $what: ${message(error)}"
        freeError(error)
        return IllegalStateException(text)
    }

    override fun startCore(configJson: String, protect: (Int) -> Boolean, log: (Int, String) -> Unit) = synchronized(lock) {
        stopCore()
        val error = PointerByReference()
        val core = ffi.xray_core_new(error) ?: throw failure("xray_core_new", error)
        // Before the config is loaded, as iOS: every socket the core opens is protected
        // before it connects, or it would loop into our own tun.
        val pcb = object : ProtectCallback {
            override fun invoke(fd: Int, user: Pointer?): Int = if (protect(fd)) 1 else 0
        }
        if (ffi.xray_core_set_socket_protect_callback(core, pcb, null, error) != 0) {
            val f = failure("protect callback", error)
            ffi.xray_core_free(core)
            throw f
        }
        val lcb = object : LogCallback {
            override fun invoke(level: Int, line: Pointer?, len: NativeLong, user: Pointer?) {
                if (line == null) return
                val n = len.toInt().coerceIn(0, 65_536)
                log(level, String(line.getByteArray(0, n), Charsets.UTF_8))
            }
        }
        val rel = object : LogRelease {
            override fun invoke(user: Pointer?) = Unit
        }
        if (ffi.xray_core_set_log_callback(core, lcb, rel, null, error) != 0) {
            // Not fatal: a core without its log still carries the line.
            freeError(error)
        }
        protectCb = pcb
        logCb = lcb
        logRelease = rel
        if (ffi.xray_core_load_config_json(core, configJson, error) != 0) {
            val f = failure("core.json", error)
            ffi.xray_core_free(core)
            throw f
        }
        if (ffi.xray_core_start(core, error) != 0) {
            val f = failure("start", error)
            ffi.xray_core_free(core)
            throw f
        }
        handle = core
    }

    override fun stopCore() = synchronized(lock) {
        val core = handle ?: return@synchronized
        handle = null
        val error = PointerByReference()
        if (ffi.xray_core_stop(core, error) != 0) freeError(error)
        ffi.xray_core_free(core)
    }

    override fun isCoreRunning(): Boolean = synchronized(lock) { handle != null }

    override fun resetNetwork(): Long = synchronized(lock) {
        val core = handle ?: return@synchronized 0L
        val closed = LongByReference()
        val error = PointerByReference()
        if (ffi.xray_core_reset_network(core, closed, error) != 0) throw failure("reset", error)
        closed.value
    }

    override fun startOlc(
        params: RustOlcParams,
        protect: (Long) -> Boolean,
        log: (String) -> Unit,
        onSessionOpened: (room: String) -> Unit
    ) {
        val rt = runtime()
        val protectFd = protect
        val sessionOpened = onSessionOpened
        setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) = log(msg)
        })
        rt.setDebug(false)
        // Before anything dials: an unprotected socket would loop into our own tun.
        rt.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean = protectFd(fd)
        })
        rt.setTransport(params.transport)
        rt.setDns(params.dns)
        rt.setDirectRules(params.directRules)
        rt.setVp8Options(params.vp8Fps, params.vp8Batch)
        rt.setUdp(params.udp)
        // Set on every start, "off" included: the runtime is the process's and keeps a profile.
        rt.setDtlsProfile(params.dtlsProfile)
        rt.setSocksListenHost(params.socksHost)
        rt.setProvider(params.provider)
        // Each session the engine opens (the first, every hop, every reconnect) is reported; the
        // room's name is not logged, only that it happened. The room keeper is told the room.
        rt.setSessionListener(object : SessionListener {
            override fun onSessionOpened(room: String, sessionId: String) {
                log("olcRTC session opened")
                sessionOpened(room)
            }
        })
        setRooms(params.room, params.failoverRooms)
        rt.setDeviceId(params.deviceId)
        rt.setKey(params.keyHex)
        rt.setSocksPort(params.socksPort.toLong())
        rt.setSocksCredentials(params.socksUser, params.socksPass)
        // A previous tunnel that died without tearing down would hold the port.
        if (rt.isRunning()) runCatching { rt.stop(RustOlcParams.STOP_TIMEOUT_MS) }
        try {
            rt.start()
            rt.waitReady(RustOlcParams.READY_TIMEOUT_MS)
        } catch (e: Exception) {
            // A half-started engine would hold the SOCKS port against the next attempt.
            runCatching { rt.stop(RustOlcParams.STOP_TIMEOUT_MS) }
            throw IllegalStateException(e.message ?: "olcRTC did not become ready")
        }
    }

    override fun setRooms(primary: String, extras: List<String>) {
        val rt = runtime()
        rt.setRoom(primary)
        rt.clearFailoverRooms()
        for (room in extras) rt.addFailoverRoom(room)
    }

    override fun stopOlc(timeoutMs: Long) {
        val rt = olcRuntime ?: return
        if (rt.isRunning()) runCatching { rt.stop(timeoutMs) }
    }

    override fun isOlcRunning(): Boolean = olcRuntime?.isRunning() == true

    override fun trim(): Long = coreTrim().toLong()

    override fun setByteBudget(bytes: Long) = coreSetByteBudget(bytes.toULong())

    override fun memoryStats(): String = coreMemoryStats()
}
