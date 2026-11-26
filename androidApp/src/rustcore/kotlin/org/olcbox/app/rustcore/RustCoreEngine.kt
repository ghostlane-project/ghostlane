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
import org.olcbox.app.vpn.olc.RustCarriers
import org.olcbox.app.vpn.service.RustCoreBackend
import org.olcbox.app.vpn.service.RustOlcParams
import org.olcbox.app.vpn.service.engine.RustGeodataBackend
import org.olcbox.app.vpn.service.engine.RustProbeBackend
import org.olcbox.app.vpn.service.engine.RustProbeCore
import java.util.concurrent.ConcurrentHashMap

/**
 * ghostlane-core on Android: xray-rust's core through xray-ffi's C ABI (JNA, as
 * CoreEngine.swift uses it through its header) and the olcRTC client through the
 * UniFFI bindings (as OlcrtcEngine.swift). Compiled only when the build was given
 * `-PghostlaneCoreDir` (androidApp/build.gradle.kts); the service finds it by name
 * (RustCore.backend).
 *
 * A core handle takes exactly one config, so every start makes a new handle and
 * every stop frees it (CoreEngine.swift). The session has one handle; each probe
 * (smart connect's, the failover's) has one of its own beside it, started and
 * freed by that probe ([startProbeCore]). A session's handle can be told the one directory its
 * core reads geodata files from ([startCoreWithGeodata]), before its config is loaded.
 */
object RustCoreEngine : RustCoreBackend, RustProbeBackend, RustGeodataBackend {

    /** xray-ffi's C ABI (crates/xray-ffi/include/xray_ffi.h), the part the engine uses. */
    interface XrayFfi : Library {
        fun xray_ffi_version_major(): Int
        fun xray_core_new(error: PointerByReference): Pointer?
        fun xray_core_set_socket_protect_callback(core: Pointer, cb: ProtectCallback, user: Pointer?, error: PointerByReference): Int
        fun xray_core_set_log_callback(core: Pointer, cb: LogCallback, release: LogRelease, user: Pointer?, error: PointerByReference): Int
        fun xray_core_set_geodata_search_dir_exclusive(core: Pointer, dir: String, error: PointerByReference): Int
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

    /**
     * Test hook: the carrier every olcRTC room is started on, in place of the location's.
     * `none` makes the room an engine's address dialled directly (`tcp://host:port/name`, the
     * interop pipe), so a test reaches no carrier. Never set outside tests.
     */
    @Volatile
    var testCarrier: String? = null

    /**
     * Test hook: the core.json a probe's handle is started from, made from the one the probe
     * wrote (same inbound, another way out). Never set outside tests.
     */
    @Volatile
    var testProbeConfig: ((String) -> String)? = null

    /** The probe handles that run now, beside the session's. */
    fun probeHandles(): Int = probes.size

    private val ffi: XrayFfi by lazy {
        Native.load("ghostlane_core", XrayFfi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }

    private val lock = Any()

    /** The session's handle. */
    private var handle: Pointer? = null

    // The native side holds the session handle's callbacks for the handle's life: kept here, never collected.
    private var callbacks: Callbacks? = null

    // The probe handles that run, each with its callbacks: reachable from here until it is freed.
    private val probes: MutableSet<ProbeCore> = ConcurrentHashMap.newKeySet()

    /** The callbacks one handle is given (a protector only when there is one to call). */
    private class Callbacks(onProtect: ((Int) -> Boolean)?, onLog: (Int, String) -> Unit) {
        val protect: ProtectCallback? = onProtect?.let { p ->
            object : ProtectCallback {
                override fun invoke(fd: Int, user: Pointer?): Int = if (p(fd)) 1 else 0
            }
        }
        val log = object : LogCallback {
            override fun invoke(level: Int, line: Pointer?, len: NativeLong, user: Pointer?) {
                if (line == null) return
                val n = len.toInt().coerceIn(0, 65_536)
                onLog(level, String(line.getByteArray(0, n), Charsets.UTF_8))
            }
        }
        val release = object : LogRelease {
            override fun invoke(user: Pointer?) = Unit
        }
    }

    /** One probe's handle: freed by its probe, never by the session's start or stop. */
    private class ProbeCore(val callbacks: Callbacks) : RustProbeCore {
        @Volatile
        var core: Pointer? = null

        override fun isRunning(): Boolean = core != null

        override fun stop() {
            val c = synchronized(this) { core.also { core = null } } ?: return
            runCatching { RustCoreEngine.close(c) }
            // Only now: the native side may call back until the handle is freed.
            RustCoreEngine.probes -= this
        }
    }

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

    /**
     * A new core handle running [configJson], with [cbs] installed first and, with [geodataDir],
     * that directory as the only one the core reads geodata files from; freed again when any step
     * fails. Whoever calls keeps [cbs] reachable for the handle's life.
     */
    private fun open(configJson: String, cbs: Callbacks, geodataDir: String? = null): Pointer {
        val error = PointerByReference()
        val core = ffi.xray_core_new(error) ?: throw failure("xray_core_new", error)
        // Before the config is loaded, as iOS: every socket the core opens is protected
        // before it connects, or it would loop into our own tun.
        val pcb = cbs.protect
        if (pcb != null && ffi.xray_core_set_socket_protect_callback(core, pcb, null, error) != 0) {
            val f = failure("protect callback", error)
            ffi.xray_core_free(core)
            throw f
        }
        if (ffi.xray_core_set_log_callback(core, cbs.log, cbs.release, null, error) != 0) {
            // Not fatal: a core without its log still carries the line.
            freeError(error)
        }
        // Before the config too: the core reads an `ext:` list while it loads the config, from
        // here and nowhere else, so a list that is not here fails the load.
        if (geodataDir != null && ffi.xray_core_set_geodata_search_dir_exclusive(core, geodataDir, error) != 0) {
            val f = failure("geodata directory", error)
            ffi.xray_core_free(core)
            throw f
        }
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
        return core
    }

    /** Stops and frees a handle [open] made. */
    private fun close(core: Pointer) {
        val error = PointerByReference()
        if (ffi.xray_core_stop(core, error) != 0) freeError(error)
        ffi.xray_core_free(core)
    }

    override fun startCore(configJson: String, protect: (Int) -> Boolean, log: (Int, String) -> Unit) =
        startSession(configJson, null, protect, log)

    override fun startCoreWithGeodata(
        configJson: String,
        geodataDir: String,
        protect: (Int) -> Boolean,
        log: (level: Int, line: String) -> Unit
    ) = startSession(configJson, geodataDir, protect, log)

    private fun startSession(configJson: String, geodataDir: String?, protect: (Int) -> Boolean, log: (Int, String) -> Unit) =
        synchronized(lock) {
            stopCore()
            val cbs = Callbacks(protect, log)
            callbacks = cbs
            handle = open(configJson, cbs, geodataDir)
        }

    override fun stopCore() = synchronized(lock) {
        val core = handle ?: return@synchronized
        handle = null
        close(core)
    }

    /**
     * A probe's core on a handle of its own: the session's handle and its lock are not
     * touched, so a probe neither waits for nor stops a session's start, and a session's
     * stop leaves the probe to end by itself. The handle is counted ([probeHandles]) from
     * before it is made until it is freed.
     */
    override fun startProbeCore(
        configJson: String,
        protect: ((fd: Int) -> Boolean)?,
        log: (level: Int, line: String) -> Unit
    ): RustProbeCore {
        val probe = ProbeCore(Callbacks(protect, log))
        probes += probe
        try {
            probe.core = open(testProbeConfig?.invoke(configJson) ?: configJson, probe.callbacks)
        } catch (e: Throwable) {
            probes -= probe
            throw e
        }
        return probe
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
        // A carrier or a lane the Rust client does not carry is refused before the runtime is told
        // anything, in the sentence the session and the iPhone give (OlcSession.refusal's rule).
        RustCarriers.refusal(params.provider, params.transport)?.let { throw IllegalStateException(it) }
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
        rt.setProvider(testCarrier ?: params.provider)
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
