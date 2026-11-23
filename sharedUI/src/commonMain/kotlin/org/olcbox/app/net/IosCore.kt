package org.olcbox.app.net

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.core.CoreConfig
import org.olcbox.app.net.core.CoreDns
import org.olcbox.app.net.core.CoreInbound
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLines
import org.olcbox.app.net.core.CoreRefusal
import org.olcbox.app.net.core.CoreRouting
import org.olcbox.app.net.core.CoreStream
import org.olcbox.app.net.core.CoreSupport
import org.olcbox.app.net.core.lines.Reality
import org.olcbox.app.net.core.lines.WebTunnel
import org.olcbox.app.net.core.lines.Hysteria2 as Hysteria2Kind
import org.olcbox.app.vpn.olc.OlcSession

/**
 * What runs a line in the iOS packet tunnel. Since step C of the OpenSpec
 * change `ghostlane-rust-core` the extension carries no Go: one Rust library,
 * ghostlane-core, runs every line, the core's from core.json and an olcRTC
 * room from olcrtc.json, and the app writes one of the two for each start
 * ([IosCore.start]).
 */
enum class IosEngine {
    /** `ghostlane-core` (xray-rust) behind hev, started from `core.json` ([XrayConfig.buildIosCore]). */
    RUST,

    /**
     * None: the core refuses the line ([IosCore.refusal]), and the extension
     * has no other engine, so the app refuses the start with the reason
     * before the extension is asked.
     */
    NONE,
}

/**
 * A line [XrayConfig.buildIosCore] writes `core.json` for: one shape of one
 * kind of line under `net/core/lines` ([CoreLine.shapes]). Every shape of
 * every kind is one ([entries]), so a kind added there runs on the iPhone
 * with no edit here. [slug] names its reference configs
 * (`ios-core-<slug>.json`, `bypass-ios-core-<slug>.json`) and the case of its
 * kind's fixture they are written from.
 */
class IosCoreLine private constructor(
    val slug: String,
    /** The kind of line this is a shape of. */
    val kind: CoreLine,
) {
    override fun equals(other: Any?): Boolean = other is IosCoreLine && other.slug == slug

    override fun hashCode(): Int = slug.hashCode()

    override fun toString(): String = slug

    companion object {
        /** Every shape of every kind, in the order of the kinds and of their shapes. */
        val entries: List<IosCoreLine> by lazy {
            CoreLines.all.flatMap { kind -> kind.shapes.map { IosCoreLine(it, kind) } }
        }

        /** The line [slug] names, or null when no kind writes that shape. */
        fun of(slug: String): IosCoreLine? = entries.firstOrNull { it.slug == slug }

        private fun named(slug: String): IosCoreLine = requireNotNull(of(slug)) { "no kind of line writes the shape $slug" }

        // The six lines the extension shipped first, by the names the tests
        // of them use.

        /** VLESS over Reality on raw TCP, with Vision or without a flow. */
        val RealityVision: IosCoreLine get() = named(Reality.RAW)

        val RealityGrpc: IosCoreLine get() = named(Reality.GRPC)

        /** WebTunnel: VLESS over WebSocket with ordinary TLS. */
        val WebTunnelWs: IosCoreLine get() = named(WebTunnel.WS)

        /** WebTunnel: VLESS over HTTP upgrade with ordinary TLS. */
        val WebTunnelHttpUpgrade: IosCoreLine get() = named(WebTunnel.HTTP_UPGRADE)

        /** Hysteria2, with or without Salamander. */
        val Hysteria2: IosCoreLine get() = named(Hysteria2Kind.SLUG)

        /** VLESS over XHTTP with Reality: ProofKit's packet-up line. */
        val XhttpReality: IosCoreLine get() = named(Reality.XHTTP)
    }
}

/**
 * What [IosCore.choose] decided for a line: the [engine], the core [line]
 * when the link's shape is one, and, when no engine runs it, the [reason]
 * (never a secret: words about the link's shape).
 */
data class IosEngineChoice(val engine: IosEngine, val line: IosCoreLine?, val reason: String? = null)

/**
 * What the iPhone starts for a location, decided in the app before the
 * extension is asked ([IosCore.start]): the one file the extension starts
 * from, `core.json` or `olcrtc.json`, or a refusal, and then no file is
 * written and no tunnel is asked for.
 */
sealed interface IosStart {
    /**
     * A line of the core: `core.json` is written from [spec], one of [line]'s
     * links. [notes] are for the app's log: what the core does other than
     * the link says (the Reality hello it has no form of, [Reality.helloNote]).
     */
    data class Core(val spec: OutboundSpec, val line: IosCoreLine, val notes: List<String>) : IosStart

    /** An olcRTC room on a carrier the Rust client carries: `olcrtc.json`. */
    data object Room : IosStart

    /** The start is refused: [sentence] names the line and says why ([IosCore.refused]). */
    data class Refused(val sentence: String) : IosStart
}

/**
 * What the Rust core runs on the iPhone, and the `core.json` it runs it from.
 *
 * The writer itself is `net/core`, shared by every platform: [CoreConfig]
 * builds the document, each kind of line under `net/core/lines` writes its
 * own outbound and names what it refuses ([CoreRefusal]). This object is the
 * iPhone's view of it: the lines ([IosCoreLine], every shape of every kind),
 * what the app starts for a location ([start]), and the names the app and
 * the Android service already call.
 */
object IosCore {
    /** The fake addresses the core hands out for names bound for the tunnel ([CoreDns.FAKE_IP_POOL]). */
    const val FAKE_IP_POOL = CoreDns.FAKE_IP_POOL

    /** The stream window of XHTTP on HTTP/2 ([CoreStream.XHTTP_H2_STREAM_RECEIVE_WINDOW]). */
    const val XHTTP_H2_STREAM_RECEIVE_WINDOW = CoreStream.XHTTP_H2_STREAM_RECEIVE_WINDOW

    /**
     * Which of the iPhone's lines [spec] is, by its shape alone; null for a
     * link of no kind, or of a shape its kind refuses whole (VLESS over
     * HTTP/2).
     */
    fun lineOf(spec: OutboundSpec): IosCoreLine? {
        val slug = CoreLines.of(spec)?.shape(spec) ?: return null
        return IosCoreLine.of(slug)
    }

    /** Why the core would refuse [spec], or null when it takes it ([CoreRefusal.of]). */
    fun refusal(spec: OutboundSpec): String? = CoreRefusal.of(spec)

    /**
     * [spec] written with [address] for its server, for a line named by a hostname
     * that the caller resolved on the network underneath ([CoreLines.pinHost]).
     */
    fun pinHost(spec: OutboundSpec, address: String): OutboundSpec = CoreLines.pinHost(spec, address)

    /**
     * The engine for [spec]: the core for a line it takes, none for one it
     * refuses ([refusal]), with the reason. The extension carries no other
     * engine, so there is nothing to choose between.
     */
    fun choose(spec: OutboundSpec): IosEngineChoice {
        val line = lineOf(spec)
        val reason = refusal(spec) ?: if (line == null) CoreRefusal.noLine(spec) else null
        if (reason != null || line == null) return IosEngineChoice(IosEngine.NONE, line, reason)
        return IosEngineChoice(IosEngine.RUST, line)
    }

    /**
     * What the iPhone starts for [location] (normalized), decided before the
     * extension is asked: a room on a carrier the Rust client carries
     * ([OlcSession.refusal]), a line the core takes ([choose]), or a refusal
     * that names the line.
     */
    fun start(location: LocationConfig): IosStart {
        if (location.kind == LocationKind.Olcrtc) {
            return OlcSession.refusal(location)?.let { IosStart.Refused(refused(location, it)) } ?: IosStart.Room
        }
        val spec = location.rawLink?.let { LinkParser.parse(it) }
            ?: return IosStart.Refused(refused(location, NO_LINK))
        val choice = choose(spec)
        val line = choice.line
        if (choice.engine != IosEngine.RUST || line == null) {
            return IosStart.Refused(refused(location, choice.reason ?: CoreRefusal.noLine(spec)))
        }
        return IosStart.Core(spec, line, listOfNotNull(Reality.helloNote(spec)))
    }

    /** Why a location whose link the app does not read is refused. */
    const val NO_LINK = "the app reads no link in it"

    /**
     * The sentence of a refused start: the line by the name the list shows,
     * and the [reason]. A location with no name of its own is named by what
     * it is, never by its id: a room's is the address of a meeting.
     */
    fun refused(location: LocationConfig, reason: String): String {
        val name = location.name.trim().ifBlank { if (location.kind == LocationKind.Olcrtc) "this room" else "this line" }
        return "Cannot start $name: $reason"
    }

    /**
     * A link's `fp` as the core takes it ([CoreSupport.helloName]). Null for a
     * REALITY line whose name has no REALITY hello in the core ([Reality.hello]).
     */
    internal fun fingerprint(fp: String?, reality: Boolean): String? =
        if (reality) Reality.hello(fp) else CoreSupport.helloName(fp)

    /** A Hysteria2 `pinSHA256` as the core reads `pinnedPeerCertSha256` ([CoreSupport.pin]). */
    internal fun pin(raw: String): String? = CoreSupport.pin(raw)

    /**
     * See [XrayConfig.buildIosCore]. The document is [CoreConfig.build]'s,
     * for any line the core takes ([refusal] says why it would not).
     */
    internal fun build(
        spec: OutboundSpec,
        login: SocksLogin,
        routing: Routing,
        geodata: XrayGeodata.Lists?,
        verboseLogs: Boolean,
        socksPort: Int,
    ): String {
        CoreInbound.requireLogin(login)
        refusal(spec)?.let { throw IllegalArgumentException("the Rust core cannot run this line: $it") }
        requireNotNull(lineOf(spec)) { "the Rust core writes no shape of this line" }
        return CoreConfig.build(spec, login, routing, geodata, verboseLogs, socksPort)
    }

    /** Why the Rust core does not run the blocked-only mode yet ([CoreConfig.BLOCKED_ONLY_REFUSAL]). */
    const val BLOCKED_ONLY_REFUSAL = CoreConfig.BLOCKED_ONLY_REFUSAL

    /** [routing] as the core's config builds it ([CoreRouting.effective]). */
    fun effective(routing: Routing): Routing = CoreRouting.effective(routing)
}
