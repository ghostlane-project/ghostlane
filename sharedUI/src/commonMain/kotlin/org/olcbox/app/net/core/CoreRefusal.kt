package org.olcbox.app.net.core

import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.XrayConfig

/**
 * Why the Rust core would refuse a link. The core is xray-rust: it reads the
 * Xray JSON its parser knows and refuses the whole document over one field it
 * does not, so a line it would refuse is not written for it at all, and the
 * caller is told why before any tunnel is made.
 *
 * What is refused for every kind is here; what a kind refuses of its own
 * links is in that kind's file ([CoreLine.refusal]).
 */
object CoreRefusal {
    /**
     * A server named by a hostname: the core resolves an outbound's server
     * with `dns.hosts` and the platform resolver, which behind a tun is the
     * tun itself, and the config cannot point it at the network underneath
     * (`sockopt.domainStrategy` is refused on VLESS and Hysteria). The caller
     * resolves the name there and pins the address ([CoreLines.pinHost]).
     */
    const val HOSTNAME = "the server is named by a hostname, which the core would resolve through the tunnel's own resolver"

    /**
     * `allowInsecure` on ordinary TLS, in the words every kind that refuses it
     * uses: the core verifies every certificate (a pin is the way to trust a
     * self-signed one). Whether a kind refuses it is that kind's rule.
     */
    const val WAIVED_CHECKS = "the link waives certificate checks (allowInsecure), which the core does not do"

    /** Why the core would refuse [spec], or null when it takes it. */
    fun of(spec: OutboundSpec, lines: List<CoreLine> = CoreLines.all): String? {
        val line = CoreLines.of(spec, lines) ?: return noLine(spec)
        if (!XrayConfig.isIpLiteral(spec.host)) return HOSTNAME
        return line.refusal(spec)
    }

    /** A link no kind writes a line for. */
    fun noLine(spec: OutboundSpec): String = "the Rust core has no ${spec::class.simpleName} line of this shape"
}
