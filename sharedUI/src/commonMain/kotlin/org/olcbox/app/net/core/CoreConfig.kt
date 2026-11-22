package org.olcbox.app.net.core

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.XrayGeodata

/**
 * `core.json`: the config the Rust core (xray-rust) is started from, written
 * by the app for every platform that runs it.
 *
 * The core reads the Xray JSON its parser knows and refuses the whole
 * document over one field it does not (`reject_unknown_fields` against its
 * `surface.rs`). So what is written here is only what that parser accepts,
 * and a link it would refuse is not written at all: [CoreRefusal] names why.
 *
 * The shape, with the files of `net/core` that write each part:
 *  - one inbound, SOCKS5 on loopback demanding a login, UDP on (CoreInbound);
 *  - the line's outbound, written by its kind (CoreLine, `net/core/lines`,
 *    with what kinds share in CoreStream and CoreSupport);
 *  - DNS answered by the core: a fake address for a name bound for the
 *    tunnel, the network's own resolver for a name bound direct (CoreDns);
 *  - the user's own direct and tunnel rules ahead of a region's lists
 *    (CoreRouting).
 */
object CoreConfig {
    /** The line's outbound: first, so what no rule claims rides it. */
    internal const val LINE = "out"

    /** Why the Rust core does not run the blocked-only mode yet: the Go cores route it from sing-box's lists. */
    const val BLOCKED_ONLY_REFUSAL =
        "the Rust core does not run the blocked-only mode yet (it needs Re:filter's lists in Xray's form): use another mode or the Go cores"

    /**
     * The config for [spec]: one SOCKS5 inbound on 127.0.0.1:[socksPort]
     * demanding [login], and the line.
     *
     * [spec] must be a link the core takes: [CoreRefusal.of] says why one is
     * not, and this throws IllegalArgumentException for it. [routing] is
     * Global or a bypass, either with the user's rules ([CoreRouting.effective]),
     * not the blocked-only mode ([BLOCKED_ONLY_REFUSAL]); under a bypass
     * [geodata] carries the region's lists inline. `log` is at warning (debug
     * with [verboseLogs]) with no access log.
     */
    fun build(
        spec: OutboundSpec,
        login: SocksLogin,
        routing: Routing = Routing.Global,
        geodata: XrayGeodata.Lists? = null,
        verboseLogs: Boolean = false,
        socksPort: Int = XrayConfig.XRAY_SOCKS_PORT,
    ): String {
        CoreInbound.requireLogin(login)
        CoreRefusal.of(spec)?.let { throw IllegalArgumentException("the Rust core cannot run this line: $it") }
        val line = requireNotNull(CoreLines.of(spec))
        val rules = CoreRouting.effective(routing) as? Routing.RuleBased
        require(rules == null || rules.policy != Routing.Policy.BlockedOnly) { BLOCKED_ONLY_REFUSAL }
        require(rules == null || rules.policy !is Routing.Policy.Bypass || geodata != null) {
            "Bypass Russia on the Rust core carries its lists inline: pass XrayGeodata.lists()"
        }
        val plan = rules?.let { RulePlan(it, geodata.takeIf { _ -> it.policy is Routing.Policy.Bypass }) }
        val directResolver = rules?.let { CoreDns.directResolverAddress(it.directDns) }

        return buildJsonObject {
            // xray-rust reads no `log`: the embedding decides where its lines
            // go. Written for whoever reads the file, as Xray reads it: no
            // access log, which would name every connection the user makes.
            putJsonObject("log") {
                put("loglevel", if (verboseLogs) "debug" else "warning")
                put("access", "none")
            }
            putDns(directResolver)
            putJsonArray("inbounds") { addSocksInbound(socksPort, login) }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("tag", LINE)
                    with(line) { putOutbound(spec) }
                }
                // No settings: a name handed here is resolved through
                // `dns.servers`, which under the rules is dns-direct alone.
                if (plan != null) addJsonObject { put("tag", "direct"); put("protocol", "freedom") }
                addDnsOutbound(directResolver, plan)
            }
            putRouting(plan)
        }.toString()
    }
}
