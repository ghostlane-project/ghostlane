package org.olcbox.app.vpn.service

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.vpn.data.vpnPrefDataStore

/**
 * The seam between the VpnService and ghostlane-core (the Rust library: xray-rust's
 * core through its C ABI, the olcRTC client through UniFFI).
 *
 * The service lives in this library, the library's native code and its UniFFI
 * bindings in :androidApp (build with `-PghostlaneCoreDir=<build-ghostlane-core-android.sh output>`),
 * so the service sees only this interface. The implementation is
 * `org.olcbox.app.rustcore.RustCoreEngine`, found by name:
 * a build without the core has no such class and [backend] is null, which the
 * service reports as a start that failed with a reason.
 *
 * The arrangement is iOS's (iosApp/PacketTunnel/CoreEngine.swift, OlcrtcEngine.swift):
 * the engine speaks the transport on a loopback SOCKS port that demands a login and
 * hev-socks5-tunnel owns the tun in front of it. Every socket the engine opens is
 * handed to [protect] first (VpnService.protect, as iOS pins to the physical interface).
 */
interface RustCoreBackend {
    /** `BuildConfig` flag of the app build: whether the switch is on when nothing else says. */
    val defaultOn: Boolean

    /** Loads the library; throws with the reason when it cannot. */
    fun load()

    /** Starts the xray core on a complete core.json; stops a previous one first. Throws with the core's own message. */
    fun startCore(configJson: String, protect: (Int) -> Boolean, log: (level: Int, line: String) -> Unit)

    /** Stops and frees the xray core, if one runs. Never throws. */
    fun stopCore()
    fun isCoreRunning(): Boolean

    /** The network moved: the core closes what it holds on the old path. Returns the connections it closed. */
    fun resetNetwork(): Long

    /** Starts the olcRTC client and waits until it is ready. Throws with the engine's message. */
    fun startOlc(params: RustOlcParams, protect: (Long) -> Boolean, log: (String) -> Unit)

    /** Stops the olcRTC client, if one runs. Never throws. */
    fun stopOlc(timeoutMs: Long)
    fun isOlcRunning(): Boolean

    /** Drops the core's pools and asks the allocator for its free pages; the bytes given back. */
    fun trim(): Long

    /** Moves the core's byte budget (0 gives the formula back), as iOS's MemoryRelief does under pressure. */
    fun setByteBudget(bytes: Long)

    /** The olcRTC client's memory figures as one JSON line. */
    fun memoryStats(): String
}

/** What the olcRTC client is given, the fields OlcrtcEngine.swift's `launch` sets. */
data class RustOlcParams(
    val provider: String,
    val transport: String,
    val room: String,
    val keyHex: String,
    val deviceId: String,
    val socksHost: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val dns: String,
    val directRules: String,
    val vp8Fps: Long,
    val vp8Batch: Long,
    val udp: Boolean,
    val dtlsProfile: String,
) {
    companion object {
        /** OlcrtcEngine.swift's readyTimeoutMillis. */
        const val READY_TIMEOUT_MS = 35_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Whether the Rust core engine runs the tunnel's transport instead of the Go
 * cores. OFF unless asked, in this order:
 *  1. the intent extra [OlcboxVpnActions.EXTRA_RUST_CORE] (tests, `adb shell am startservice`);
 *  2. the hidden developer setting [KEY_DEV_RUST_CORE] in the VPN preferences (no screen writes it);
 *  3. the build flag, `-Polcbox.rustCoreDefault=true` (false in every build today).
 */
object RustCore {
    val KEY_DEV_RUST_CORE = booleanPreferencesKey("dev_rust_core")

    private const val ENGINE_CLASS = "org.olcbox.app.rustcore.RustCoreEngine"

    /** Test hook: a complete core.json for the given SOCKS port and login, in place of the profile's. Never set outside tests. */
    @Volatile
    var testConfig: ((port: Int, login: SocksLogin?) -> String)? = null

    /** The engine, or null in a build that does not carry ghostlane-core. */
    val backend: RustCoreBackend? by lazy {
        runCatching {
            Class.forName(ENGINE_CLASS).getField("INSTANCE").get(null) as RustCoreBackend
        }.getOrNull()
    }

    fun resolve(intent: Intent, context: Context): Boolean {
        if (intent.hasExtra(OlcboxVpnActions.EXTRA_RUST_CORE)) {
            return intent.getBooleanExtra(OlcboxVpnActions.EXTRA_RUST_CORE, false)
        }
        val stored = runCatching {
            runBlocking { context.vpnPrefDataStore.data.first()[KEY_DEV_RUST_CORE] }
        }.getOrNull()
        return stored ?: (backend?.defaultOn ?: false)
    }
}
