package org.olcbox.app.ios

data class IosOlcRtcStartRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPass: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int,
    /**
     * The engine's direct rules under Bypass Russia ([OlcrtcDirectRules]),
     * empty under Global. The engine dials a matching destination itself and
     * resolves a matching name on the network's resolver; there is no router
     * in front of it on this platform since hev took the tun (1.0.428).
     */
    val directRules: String = "",
    /**
     * The other rooms of the location's failover group (`##rooms`), the
     * primary excluded; the engine hops to them when the room it is in ends.
     */
    val failoverRooms: List<String> = emptyList(),
    /**
     * Where the room list came from, when it came from a subscription. The
     * extension re-reads it through the tunnel after every room handover,
     * because the app that owns the list is suspended for most of the
     * tunnel's life. A server-list URL is a credential: it goes into the App
     * Group beside the key, and nowhere else.
     */
    val subscriptionUrl: String? = null
)

data class IosOlcRtcCheckRequest(
    val carrierName: String,
    val transportName: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val timeoutMillis: Long,
    val pingUrl: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int
)

data class IosBridgeResult(
    val success: Boolean,
    val message: String?
)

data class IosLongResult(
    val success: Boolean,
    val valueMillis: Long,
    val message: String?
)

interface IosLogWriter {
    fun writeLog(message: String)
}

interface IosTextCallback {
    fun onSuccess(text: String)
    fun onError(message: String)
}

interface IosMessageCallback {
    fun onSuccess(message: String)
    fun onError(message: String)
}

interface IosOlcRtcBridge {
    fun setLogWriter(writer: IosLogWriter?)
    fun start(request: IosOlcRtcStartRequest): IosBridgeResult
    fun stop()
    fun isRunning(): Boolean
    fun ping(request: IosOlcRtcCheckRequest): IosLongResult
    fun check(request: IosOlcRtcCheckRequest): IosLongResult
}

/** Delivers the answer to an asynchronous bridge call, exactly once. */
interface IosBridgeCallback {
    fun onResult(result: IosBridgeResult)
}

/**
 * Everything the extension needs to bring one location up.
 *
 * [config] is a complete sing-box config with a tun inbound. At most one of the
 * other two is ever set, and for reality and hysteria2 neither is: sing-box
 * borrows a second core only for the transports it does not implement itself.
 *
 * [xrayConfig] is an Xray config for xhttp, and [olcrtc] the parameters for our
 * own engine. In both cases the borrowed core listens on a loopback SOCKS port
 * and sing-box, which owns the tun, reaches it there.
 *
 * [ruleSets] are the rule-set files [config] refers to under Bypass Russia,
 * file name to base64 of the bytes, and empty when it refers to none.
 */
data class IosPacketTunnelStartRequest(
    val config: String,
    val xrayConfig: String?,
    val olcrtc: IosOlcRtcStartRequest?,
    /**
     * The bridge writes these where libbox resolves `rules/<name>` — its
     * working directory in the App Group — and removes the directory when
     * there is nothing to write, so a stale list cannot outlive the routing
     * choice that put it there. Base64 because a Kotlin ByteArray crosses to
     * Swift as an object with a getter per byte.
     */
    val ruleSets: Map<String, String> = emptyMap()
)

/**
 * The room list of the running olcRTC location as the app knows it now, handed
 * to a live extension when a subscription refresh changed it, so the engine
 * learns the next room before the one it is in is retired.
 */
data class IosOlcRtcRoomsUpdate(
    val primaryRoom: String,
    val failoverRooms: List<String>
)

/**
 * Starting and stopping the packet tunnel extension, which only the app can ask
 * the system to launch. Every transport goes through it, olcRTC included.
 */
interface IosPacketTunnelBridge {
    /**
     * Answers through [callback] once the tunnel is up or has failed.
     *
     * Asynchronous because it cannot honestly be otherwise: the extension takes
     * seconds to settle, and the synchronous shape this replaces held a
     * coroutine thread on a semaphore for all of them — which the Swift runtime
     * reports as `unsafeForcedSync called from Swift Concurrent context`, and
     * which can deadlock the cooperative pool outright.
     */
    fun start(request: IosPacketTunnelStartRequest, callback: IosBridgeCallback)
    fun stop()
    fun isRunning(): Boolean

    /**
     * Hands a running olcRTC tunnel the current room list. Best effort: with
     * nothing running, or a tunnel on another transport, the message goes
     * nowhere, and the extension re-reads the list itself in any case.
     */
    fun updateOlcRtcRooms(update: IosOlcRtcRoomsUpdate)

    /**
     * Epoch milliseconds at which the system says the running tunnel was
     * established, or 0 when nothing is up.
     *
     * Read from the system rather than stamped when this process noticed, so it
     * survives the app being killed and relaunched over a live tunnel — which
     * on iOS is the ordinary case, not the exception.
     */
    fun connectedSinceEpochMs(): Long

    /**
     * Bytes the tunnel interface has carried, in and out.
     *
     * From the interface's own kernel counters rather than from the engine: the
     * tunnel is another process and libbox's command server is never started, so
     * there is nothing to ask. Both are 0 when no tunnel is up.
     */
    fun tunnelBytesIn(): Long
    fun tunnelBytesOut(): Long

    /**
     * Everything the tunnel's engines wrote about the last attempt, or empty.
     *
     * The failure shown on screen carries only a few lines — it sits under a
     * status pill on a phone. This is the whole of it, for the log the user can
     * share: when a connect fails, what came *before* the last line is usually
     * where the answer is.
     */
    fun engineLog(includeDetailedLogs: Boolean): String

    /**
     * Round trip to [host] by ICMP echo, in milliseconds, or -1.
     *
     * The only latency figure obtainable for a location the app is not
     * connected to: its core lives in the extension, which runs one location at
     * a time. This measures the path rather than the protocol, which is what
     * "ping" has always meant.
     */
    fun icmpLatencyMs(host: String, timeoutMillis: Long): Long
}

interface IosPlatformBridge {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun pickConfigText(callback: IosTextCallback)

    /**
     * Reads one QR code, or reports why it could not. Subscriptions are handed
     * out as QR codes, so this is the path a phone user expects — the others
     * (paste, file) assume the link already reached the device somehow.
     */
    fun scanQrCode(callback: IosTextCallback)
    fun shareText(title: String, text: String)
    fun saveLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun shareLogs(defaultName: String, content: String, callback: IosMessageCallback)
    fun showMessage(message: String)

    /** Opens an https link in the system browser. */
    fun openUrl(url: String)
}
