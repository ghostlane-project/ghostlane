package org.olcbox.app.net

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreDns
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreRouting
import org.olcbox.app.net.core.CoreStream
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.lines.Reality

/**
 * The engine that runs a line in the iOS packet tunnel: D2 of the OpenSpec
 * change `ghostlane-rust-core`, chosen per transport by the app
 * (`IosVpnManager.IOS_ENGINES`) and followed by the extension through the
 * files it finds in the App Group: olcrtc.json, xray.json, core.json, then
 * config.json.
 */
enum class IosEngine {
    /** Phase 0's path: sing-box, Xray for XHTTP or the olcRTC engine, all Go, all behind hev. */
    GO,

    /** `ghostlane-core` (xray-rust) behind hev, started from `core.json` ([XrayConfig.buildIosCore]). */
    RUST,
}

/** What the per-transport choice ([IosEngine]) is made for. */
enum class IosTransport { Reality, RealityGrpc, WebTunnel, Hysteria2, Xhttp, Olcrtc }

/**
 * A line [XrayConfig.buildIosCore] writes `core.json` for. [slug] names its
 * reference configs (`ios-core-<slug>.json`, `bypass-ios-core-<slug>.json`).
 */
enum class IosCoreLine(val slug: String, val transport: IosTransport) {
    /** VLESS over Reality on raw TCP, with Vision or without a flow. */
    RealityVision("reality", IosTransport.Reality),
    RealityGrpc("grpc-reality", IosTransport.RealityGrpc),
    /** WebTunnel: VLESS over WebSocket with ordinary TLS. */
    WebTunnelWs("webtunnel-ws", IosTransport.WebTunnel),
    /** WebTunnel: VLESS over HTTP upgrade with ordinary TLS. */
    WebTunnelHttpUpgrade("webtunnel-httpupgrade", IosTransport.WebTunnel),
    /** Hysteria2, with or without Salamander. */
    Hysteria2("hysteria2", IosTransport.Hysteria2),
    /** VLESS over XHTTP with Reality: ProofKit's packet-up line. */
    XhttpReality("xhttp", IosTransport.Xhttp),
}

/**
 * What [IosCore.choose] decided for a line: the [engine], the core [line] when
 * the line is one, and, when a line the map gives to the Rust core goes to Go
 * after all, the [reason] (never a secret: words about the link's shape).
 */
data class IosEngineChoice(val engine: IosEngine, val line: IosCoreLine?, val reason: String? = null)

/**
 * What the iPhone starts for a location, decided in the app before the
 * extension is asked ([IosCore.start]).
 */
sealed interface IosStart {
    /** A line of the core: core.json from [spec], as [line]; [notes] are for the app's log. */
    data class Core(val spec: OutboundSpec, val line: IosCoreLine, val notes: List<String>) : IosStart

    /** An olcRTC room: olcrtc.json. */
    data object Room : IosStart

    /** Nothing is written and the extension is not asked; [sentence] says why. */
    data class Refused(val sentence: String) : IosStart
}

/**
 * What the Rust core runs on the iPhone, and the `core.json` it runs it from.
 *
 * The writer itself is `net/core`, shared by every platform: [CoreConfig]
 * builds the document, each kind of line under `net/core/lines` writes its
 * own outbound and names what it refuses ([CoreRefusal]). This object is the
 * iPhone's view of it: the six lines the extension ships ([IosCoreLine]), the
 * engine per transport ([ENGINES]), and the names the app and the Android
 * service already call.
 */
object IosCore {
    /**
     * The engine each transport runs on in the iPhone's packet tunnel
     * (`IosVpnManager.IOS_ENGINES` reads this). Since step C of
     * ghostlane-rust-core the extension carries no Go: every transport the
     * core has a line for runs from core.json, olcRTC on the Rust client
     * from olcrtc.json, and a line the core refuses ([refusal]) gets no
     * engine at all (the extension says so in stage.txt).
     */
    val ENGINES: Map<IosTransport, IosEngine> = mapOf(
        IosTransport.Reality to IosEngine.RUST,
        IosTransport.RealityGrpc to IosEngine.RUST,
        IosTransport.WebTunnel to IosEngine.RUST,
        IosTransport.Hysteria2 to IosEngine.RUST,
        IosTransport.Xhttp to IosEngine.RUST,
        IosTransport.Olcrtc to IosEngine.RUST,
    )

    /** The fake addresses the core hands out for names bound for the tunnel ([CoreDns.FAKE_IP_POOL]). */
    const val FAKE_IP_POOL = CoreDns.FAKE_IP_POOL

    /** The stream window of XHTTP on HTTP/2 ([CoreStream.XHTTP_H2_STREAM_RECEIVE_WINDOW]). */
    const val XHTTP_H2_STREAM_RECEIVE_WINDOW = CoreStream.XHTTP_H2_STREAM_RECEIVE_WINDOW

    /**
     * Which of the iPhone's lines [spec] is, by its shape alone; null for
     * anything else, a shape another platform's kind writes included.
     */
    fun lineOf(spec: OutboundSpec): IosCoreLine? {
        val slug = CoreLines.of(spec)?.shape(spec) ?: return null
        return IosCoreLine.entries.firstOrNull { it.slug == slug }
    }

    /** Why the core would refuse [spec], or null when it takes it ([CoreRefusal.of]). */
    fun refusal(spec: OutboundSpec): String? = CoreRefusal.of(spec)

    /**
     * [spec] written with [address] for its server, for a line named by a hostname
     * that the caller resolved on the network underneath ([CoreLines.pinHost]).
     */
    fun pinHost(spec: OutboundSpec, address: String): OutboundSpec = CoreLines.pinHost(spec, address)

    /**
     * The engine for [spec] under [engines], the per-transport map: Rust only
     * for a core line whose transport the map gives to Rust and which the
     * core takes ([refusal]); Go for everything else.
     */
    fun choose(spec: OutboundSpec, engines: Map<IosTransport, IosEngine>): IosEngineChoice {
        val line = lineOf(spec) ?: return IosEngineChoice(IosEngine.GO, null)
        if (engines[line.transport] != IosEngine.RUST) return IosEngineChoice(IosEngine.GO, line)
        refusal(spec)?.let { return IosEngineChoice(IosEngine.GO, line, it) }
        return IosEngineChoice(IosEngine.RUST, line)
    }

    /** [choose] under [ENGINES]. */
    fun choose(spec: OutboundSpec): IosEngineChoice = choose(spec, ENGINES)

    /** What the iPhone starts for [location] ([IosStart]). */
    fun start(location: LocationConfig): IosStart {
        if (location.kind == LocationKind.Olcrtc) return IosStart.Room
        val spec = location.rawLink?.let { LinkParser.parse(it) } ?: return IosStart.Refused("the link cannot be read")
        val choice = choose(spec)
        val line = choice.line
        if (choice.engine != IosEngine.RUST || line == null) return IosStart.Refused(choice.reason ?: CoreRefusal.noLine(spec))
        return IosStart.Core(spec, line, emptyList())
    }

    /**
     * A link's `fp` as the core takes it ([CoreSupport.helloName]). Null for a
     * REALITY line whose name has no REALITY hello in the core ([Reality.hello]).
     */
    internal fun fingerprint(fp: String?, reality: Boolean): String? =
        if (reality) Reality.hello(fp) else CoreSupport.helloName(fp)

    /** A Hysteria2 `pinSHA256` as the core reads `pinnedPeerCertSha256` ([CoreSupport.pin]). */
    internal fun pin(raw: String): String? = CoreSupport.pin(raw)

    /** See [XrayConfig.buildIosCore]. The document is [CoreConfig.build]'s, for one of the iPhone's lines. */
    internal fun build(
        spec: OutboundSpec,
        login: SocksLogin,
        routing: Routing,
        geodata: XrayGeodata.Lists?,
        verboseLogs: Boolean,
        socksPort: Int,
    ): String {
        CoreInbound.requireLogin(login)
        requireNotNull(lineOf(spec)) {
            "the Rust core's config is written for Reality, Reality gRPC, WebTunnel, Hysteria2 and XHTTP Reality lines"
        }
        return CoreConfig.build(spec, login, routing, geodata, verboseLogs, socksPort)
    }

    /** Why the Rust core does not run the blocked-only mode yet ([CoreConfig.BLOCKED_ONLY_REFUSAL]). */
    const val BLOCKED_ONLY_REFUSAL = CoreConfig.BLOCKED_ONLY_REFUSAL

    /** [routing] as the core's config builds it ([CoreRouting.effective]). */
    fun effective(routing: Routing): Routing = CoreRouting.effective(routing)
}
