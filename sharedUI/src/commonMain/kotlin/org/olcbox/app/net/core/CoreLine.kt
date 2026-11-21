package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonObjectBuilder
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.core.index.coreLineIndex

/**
 * A kind of line the Rust core runs. Each kind is one file under
 * `net/core/lines`, and everything that is the kind's own is in that file:
 * which links are its, why the core would refuse one of them, how a server
 * named by a hostname is pinned to an address, and the outbound it writes.
 * The shared files of `net/core` know no kind; they reach one through this
 * interface and [CoreLines].
 */
interface CoreLine {
    /**
     * The shapes this kind writes, each by its slug: the name its fixture and
     * its reference configs go by.
     */
    val shapes: List<String>

    /**
     * The slug of [spec]'s shape when the link is this kind's, null when it
     * is not. By the link's shape alone: a link the kind refuses
     * ([refusal]) still has its shape.
     */
    fun shape(spec: OutboundSpec): String?

    /**
     * Why the core would refuse [spec], a link of this kind, or null when it
     * takes it. Never a secret: words about the link's shape.
     */
    fun refusal(spec: OutboundSpec): String?

    /**
     * [spec], a link of this kind whose server is named by a hostname, written
     * with [address] for the server. Each place the name was also the wire's
     * (a TLS server name, a Host header) is given the name, so the address
     * replaces only where the connection goes.
     */
    fun pinHost(spec: OutboundSpec, address: String): OutboundSpec

    /**
     * The outbound for [spec], a link of this kind that it does not refuse:
     * `protocol`, `settings` and `streamSettings`. The tag is the caller's.
     */
    fun JsonObjectBuilder.putOutbound(spec: OutboundSpec)
}

/** The kinds of line the Rust core runs, and which of them a link belongs to. */
object CoreLines {
    /** Every kind under `net/core/lines`. */
    val all: List<CoreLine> get() = coreLineIndex

    /** The kind [spec] belongs to among [lines], by its shape alone; null when it is no kind's. */
    fun of(spec: OutboundSpec, lines: List<CoreLine> = all): CoreLine? = lines.firstOrNull { it.shape(spec) != null }

    /**
     * [spec] written with [address] for its server, for a line named by a hostname
     * that the caller resolved on the network underneath ([CoreLine.pinHost]). A spec
     * whose host is already an address, or that is no kind's, comes back as it is.
     */
    fun pinHost(spec: OutboundSpec, address: String): OutboundSpec {
        if (XrayConfig.isIpLiteral(spec.host)) return spec
        return of(spec)?.pinHost(spec, address) ?: spec
    }
}
