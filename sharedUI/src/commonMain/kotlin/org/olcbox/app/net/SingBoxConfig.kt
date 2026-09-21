package org.olcbox.app.net

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a minimal sing-box config JSON: one SOCKS inbound + one outbound.
 * The existing tun→SOCKS bridge (hev-socks5-tunnel on Android, PAC on Desktop)
 * feeds this SOCKS inbound; the outbound is a native vless/hy2/xhttp outbound or
 * a socks outbound to the olcrtc engine.
 *
 * The JSON schema is tied to the pinned sing-box release [SINGBOX_VERSION].
 * Bumping sing-box is a deliberate change: re-verify this builder against the
 * new schema and device-smoke before shipping.
 *
 * Re-verified for 1.13.14: every shape this builds — socks+vless/reality,
 * tun+hysteria2, tun+socks, socks+socks, and tun+socks resolving over TCP —
 * passes `sing-box check` on the 1.13.14 binary. Most of it survives version
 * bumps by staying minimal, emitting none of the inbound fields 1.13 removed.
 *
 * The shapes that reach for newer schema are the resolve-over-TCP case and
 * everything [Routing.BypassRussia] adds: a `dns` section in the typed 1.12+
 * format with `rule_set` rules, `route.rule_set` entries of the local binary
 * kind, and `route` rules in the `action` form (`sniff`, `hijack-dns`). Those
 * are the parts to re-check first on the next bump; `SingBoxConfigDumpTest`
 * writes one of each for `sing-box check`.
 */
object SingBoxConfig {
    /** Pinned sing-box release whose config schema this builder targets. */
    const val SINGBOX_VERSION = "1.13.14"
    // NOT 10809: the desktop PAC server (PacServer.PAC_PORT) owns that port, and the
    // core binding it first made every desktop connect fail with "Address already in use".
    const val SINGBOX_SOCKS_PORT = 10810

    fun build(
        outbound: OutboundSpec,
        socksPort: Int = SINGBOX_SOCKS_PORT,
        routing: Routing = Routing.Global,
    ): String = render(socksPort, routing) { addOutbound(outbound) }

    /// iOS addressing. Fixed rather than negotiated: the extension applies these
    /// same values to the system when it hands the core its descriptor, so the two
    /// halves have to agree and one constant is easier to keep honest than two.
    const val TUN_ADDRESS = "172.19.0.1/30"
    const val TUN_MTU = 9000

    /**
     * Desktop addressing, and it is not the iOS one.
     *
     * iOS rejects an MTU of 9000 outright (`nesessionmanager: failed to set the
     * MTU to 9000`) while the config that asked for it carries on claiming it. A
     * utun on macOS is no place to find out whether the same is true, so the
     * desktop shape uses the 1500 the Linux and Windows controllers have always
     * used.
     */
    const val DESKTOP_TUN_ADDRESS = "172.19.0.1/30"

    /**
     * The tun needs an IPv6 address even though no IPv6 is carried, because
     * `auto_route` only claims the families the interface has one for.
     *
     * Without it the machine keeps its IPv6 default route on the physical
     * interface and every dual-stack site is reached over IPv6, outside the
     * tunnel, at the machine's real address. That is not a corner case: browsers
     * prefer IPv6, so on a dual-stack network *the browser* leaks while
     * `curl api.ipify.org` — an A record only — keeps reporting the tunnel and
     * looking fine.
     */
    const val DESKTOP_TUN_ADDRESS6 = "fdfe:dcba:9876::1/126"
    const val DESKTOP_TUN_MTU = 1500

    /**
     * Config for a core that owns the tun itself, as on iOS.
     *
     * The desktop and Android builds put the core behind a SOCKS port and bridge
     * packets into it separately. In a Network Extension there is no room for that
     * hop — the core is handed the tunnel descriptor and does the whole job.
     *
     * The gvisor stack is not a preference: the system stack needs raw-socket
     * privileges the extension sandbox withholds, and a tun built on it comes up
     * and forwards nothing.
     */
    fun buildTun(
        outbound: OutboundSpec,
        address: String = TUN_ADDRESS,
        mtu: Int = TUN_MTU,
        routing: Routing = Routing.Global,
        directDns: DirectDns = DirectDns.Placeholder,
    ): String = renderTun(address, mtu, resolveOverTcp = false, routing, directDns) { addOutbound(outbound) }

    /**
     * Config for a core that owns the tun and hands the traffic to another core
     * listening on a local SOCKS port.
     *
     * This is how xhttp works on iOS: sing-box cannot speak that transport, so
     * Xray runs beside it in the same extension and sing-box becomes the tun
     * front-end for it. Android reaches the same arrangement from the other
     * direction — there a separate tun2socks feeds whichever core is running.
     */
    fun buildTunSocks(
        socksPort: Int,
        username: String = "",
        password: String = "",
        upstreamUdpIsLossy: Boolean = false,
        address: String = TUN_ADDRESS,
        mtu: Int = TUN_MTU,
        routing: Routing = Routing.Global,
        directDns: DirectDns = DirectDns.Placeholder,
    ): String = renderTun(address, mtu, resolveOverTcp = upstreamUdpIsLossy, routing, directDns) {
        addSocksOutbound(socksPort, username, password)
    }

    /**
     * A SOCKS inbound in front of another core's SOCKS port — the Android shape
     * for olcRTC and xhttp under Bypass Russia, where sing-box has to sit between
     * hev-socks5-tunnel and a transport it does not implement so its rules can
     * decide what goes direct. Credentials only when the upstream demands them,
     * which is olcRTC and only olcRTC.
     */
    fun buildSocksChain(
        upstreamPort: Int,
        socksPort: Int = SINGBOX_SOCKS_PORT,
        username: String = "",
        password: String = "",
        routing: Routing = Routing.Global,
    ): String = render(socksPort, routing) { addSocksOutbound(upstreamPort, username, password) }

    private fun JsonArrayBuilder.addSocksOutbound(port: Int, username: String, password: String) {
        addJsonObject {
            put("type", "socks"); put("tag", "out")
            put("server", "127.0.0.1"); put("server_port", port)
            put("version", "5")
            // Sent only when the core on the other end asked for them, which is
            // olcRTC and only olcRTC: it refuses the connection outright when
            // started with a credential pair and offered none, and on iOS it
            // always is — the app generates one on first run. That produced a
            // tunnel that came up, carried its own media perfectly, and passed
            // not one user connection. Xray's inbound has no auth, so for xhttp
            // these stay absent exactly as before.
            if (username.isNotBlank()) put("username", username)
            if (password.isNotBlank()) put("password", password)
        }
    }

    /**
     * Config for the sing-box the macOS root daemon runs: a tun in front of the
     * core the app already started on localhost.
     *
     * Two things here exist only because the core is a *separate process*. On iOS
     * the outbound lives inside the same binary and neither is needed:
     *
     * [excludeAddresses] keeps the core's own packets to the VPN server out of the
     * tun. `auto_route` points the default route at the tunnel, and without an
     * exclusion the core's upstream connection is routed into the tunnel it is
     * trying to build. That does not degrade — it deadlocks, and it reads as a
     * broken server rather than a missing route.
     *
     * [directDnsDomains] does the same for name resolution. The core redials by
     * hostname, that query enters the tun like any other, and answering it needs
     * the tunnel being redialled; those names go to the system resolver instead.
     *
     * [verifyPort] is a second socks inbound for `TunnelVerifier`; desktop callers
     * can protect it with the verification credentials. Verifying
     * through the core's own port would prove the core works and say nothing
     * about the tun in front of it — which is the half that is new here, so it is
     * the half a green light has to be about.
     *
     * This is not [renderTun]: that one emits neither an exclusion nor a second
     * inbound, and bending it to would leave the iOS shape carrying desktop
     * concerns it has no use for.
     */
    fun buildDesktopTun(
        corePort: Int,
        verifyPort: Int?,
        verifyUsername: String = "",
        verifyPassword: String = "",
        username: String = "",
        password: String = "",
        excludeAddresses: List<String> = emptyList(),
        directDnsDomains: List<String> = emptyList(),
        upstreamUdpIsLossy: Boolean = false,
        address: String = DESKTOP_TUN_ADDRESS,
        address6: String = DESKTOP_TUN_ADDRESS6,
        mtu: Int = DESKTOP_TUN_MTU,
        routing: Routing = Routing.Global,
        /**
         * The physical interface the `direct` outbound binds to under a bypass.
         * This sing-box owns the tun, so its own direct sockets would otherwise
         * enter it; that does not degrade, it loops. Required with a bypass.
         */
        bindInterface: String? = null,
        /** Where the fake-address mapping persists; the daemon's own directory. */
        cacheFilePath: String? = null,
        /** Desktop core processes must escape the TUN when they redial their carrier. */
        bypassProcessPaths: List<String> = emptyList(),
        /** A per-start name avoids colliding with a Wintun adapter still closing. */
        interfaceName: String? = null,
    ): String {
        val bypass = routing as? Routing.BypassRussia
        require(verifyUsername.isBlank() == verifyPassword.isBlank()) {
            "verification proxy credentials must be supplied as a pair"
        }
        require(bypass == null || !bindInterface.isNullOrBlank()) {
            "a direct outbound inside the tun's own process needs an interface to bind to"
        }
        // The daemon answers names itself whenever it hijacks them: for
        // olcRTC's lossy carrier, as before, and under any bypass, which needs
        // the Russian names resolved here. Then it fakes the tunnel's names
        // too, exactly as iOS does and for the same twenty seconds a lookup.
        val answersDns = upstreamUdpIsLossy || bypass != null
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "warn") }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    // Order is the default. The first server answers anything no
                    // rule claims, so `dns-remote` has to come first whenever
                    // queries are hijacked here at all — put the local one first
                    // and every hijacked lookup leaves the machine unprotected.
                    if (answersDns) addRemoteDnsServer(overTcp = upstreamUdpIsLossy)
                    // The system's resolver. The official sing-box build reads
                    // the interface's DHCP resolvers when a tun is present, so
                    // this escapes the tun without help.
                    addJsonObject { put("type", "local"); put("tag", "dns-direct") }
                    if (answersDns) addFakeIpServer()
                }
                if (directDnsDomains.isNotEmpty() || answersDns) {
                    putJsonArray("rules") {
                        // The server's own name first: the core redials it while
                        // the tun is up, and answering that through the tunnel
                        // needs the tunnel being redialled.
                        if (directDnsDomains.isNotEmpty()) {
                            addJsonObject {
                                putJsonArray("domain") { directDnsDomains.forEach { add(it) } }
                                put("server", "dns-direct")
                            }
                        }
                        if (bypass != null) addRussianNamesRule()
                        if (answersDns) addFakeIpRules()
                    }
                }
                if (answersDns) {
                    put("final", "dns-remote")
                    if (bypass != null) put("reverse_mapping", true)
                }
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    if (!interfaceName.isNullOrBlank()) put("interface_name", interfaceName)
                    putJsonArray("address") { add(address); add(address6) }
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("stack", "gvisor")
                    if (excludeAddresses.isNotEmpty()) {
                        putJsonArray("route_exclude_address") {
                            excludeAddresses.forEach { add(it) }
                        }
                    }
                }
                if (verifyPort != null) addJsonObject {
                    put("type", "socks"); put("tag", "verify-in")
                    put("listen", "127.0.0.1"); put("listen_port", verifyPort)
                    if (verifyUsername.isNotBlank()) {
                        putJsonArray("users") {
                            addJsonObject {
                                put("username", verifyUsername)
                                put("password", verifyPassword)
                            }
                        }
                    }
                }
            }
            putJsonArray("outbounds") {
                addJsonObject {
                    put("type", "socks"); put("tag", "out")
                    put("server", "127.0.0.1"); put("server_port", corePort)
                    put("version", "5")
                    // Only when the core on the other end asked for them, which is
                    // olcRTC and only olcRTC: it refuses a connection offered none
                    // when it was started with a pair, and the app generates one on
                    // first run. Xray's inbound has no auth.
                    if (username.isNotBlank()) put("username", username)
                    if (password.isNotBlank()) put("password", password)
                }
                addJsonObject {
                    put("type", "direct"); put("tag", "direct")
                    if (!bindInterface.isNullOrBlank()) put("bind_interface", bindInterface)
                }
            }
            putJsonObject("route") {
                if (bypassProcessPaths.isNotEmpty()) put("auto_detect_interface", true)
                if (bypass != null) putRuleSetDeclarations(bypass)
                // Required since 1.12 as soon as a `dns` section exists: without
                // it sing-box refuses to start, naming a deprecation and an
                // environment variable rather than the config. It resolves domain
                // names in *dial* fields only — nothing this instance dials is a
                // name, since its outbound is 127.0.0.1 — so the local resolver is
                // both correct and inert here.
                put("default_domain_resolver", "dns-direct")
                putJsonArray("rules") {
                    if (bypassProcessPaths.isNotEmpty()) addJsonObject {
                        putJsonArray("inbound") { add("tun-in") }
                        putJsonArray("process_path") { bypassProcessPaths.forEach { add(it) } }
                        put("outbound", "direct")
                    }
                    if (answersDns) {
                        addJsonObject { put("action", "sniff") }
                        // Without this the `dns` block above is dead weight for the
                        // system's queries: they would be forwarded as the
                        // datagrams they arrived as, which is the path being
                        // avoided. Before the IPv6 reject, so a query to a v6
                        // resolver is still caught and answered rather than refused.
                        addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                    }
                    if (bypass != null) addBypassRouteRules()
                    // IPv6 is claimed and refused, not carried.
                    //
                    // Claimed because auto_route only takes the families the
                    // interface has an address for, and an unclaimed IPv6 default
                    // route means every dual-stack site is reached outside the
                    // tunnel at the machine's real address.
                    //
                    // Refused rather than forwarded because whether the far end
                    // has working IPv6 is a property of each operator's node, not
                    // of this config. A reject is answered immediately, so Happy
                    // Eyeballs falls back to IPv4 in milliseconds; forwarding into
                    // a node without IPv6 would hang instead, which is the same
                    // outcome bought with a timeout.
                    addJsonObject { put("action", "reject"); put("ip_version", 6) }
                }
                if (bypass != null) put("final", "out")
            }
            if (answersDns) {
                putJsonObject("experimental") {
                    putJsonObject("cache_file") {
                        put("enabled", true)
                        put("store_fakeip", true)
                        if (!cacheFilePath.isNullOrBlank()) put("path", cacheFilePath)
                    }
                }
            }
        }
        return obj.toString()
    }

    /** The tunnel-side resolver, reached through `out`; over TCP when the upstream's UDP is lossy. */
    private const val REMOTE_DNS_SERVER = "1.1.1.1"

    /**
     * What the iOS builder writes where the direct resolver goes; the extension
     * replaces it with the network's own before starting the core. TEST-NET-2,
     * so an unreplaced placeholder can only fail loudly, never resolve.
     */
    const val DIRECT_DNS_PLACEHOLDER = "198.51.100.53"

    /**
     * Yandex DNS. What "direct" resolution uses when the platform offered no
     * resolver at all. Russian because the mode is: it is the resolver most
     * likely to answer on a Russian mobile network that meets 1.1.1.1 with
     * silence, and it answers everywhere else too.
     */
    const val DIRECT_DNS_FALLBACK = "77.88.8.8"

    /**
     * The fake-address range for names bound for the tunnel. RFC 2544's
     * benchmarking block, which nothing on the internet answers from, so a
     * leaked fake can only fail; IPv4 only, because the tun on iOS claims no
     * IPv6 and a fake IPv6 would leave through the physical interface.
     */
    private const val FAKE_IP_RANGE = "198.18.0.0/15"

    /**
     * The iOS shape. sing-box answers every name itself here, on every
     * transport, for three reasons that turned up one at a time on a phone:
     *
     * - The tun delivers IPs, so a connection has no name unless the DNS that
     *   produced the IP went through this process.
     * - The system was told its resolver is 1.1.1.1, which iOS knows as an
     *   encrypted-DNS provider and quietly upgraded to DoT/DoH — through the
     *   tunnel, past the hijack, twenty seconds a lookup on a lossy relay. The
     *   extension now names a resolver only this config answers.
     * - Over olcRTC every lookup was a TCP connection through the relay, and
     *   took 20–25 s. Names bound for the tunnel now get a fake address at once
     *   and the connection leaves by name; the exit resolves it, as it did in
     *   the old in-app SOCKS mode that was fast. The mapping lives in libbox's
     *   cache file so an address an app remembered across a reconnect still
     *   means something. HTTPS-type queries, the other slow half, are refused,
     *   which a client treats as "no such record" and carries on.
     *
     * Under Bypass Russia the Russian lists are resolved for real, on the
     * network underneath, before any of that; those connections then match
     * by name or by address and go direct.
     */
    private fun renderTun(
        address: String,
        mtu: Int,
        resolveOverTcp: Boolean,
        routing: Routing,
        directDns: DirectDns,
        outbounds: JsonArrayBuilder.() -> Unit,
    ): String {
        val bypass = routing as? Routing.BypassRussia
        val direct = bypass?.directDns ?: directDns
        val obj = buildJsonObject {
            putJsonObject("log") { put("level", "warn") }
            putIosDns(remoteOverTcp = resolveOverTcp, direct = direct, bypass = bypass)
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    putJsonArray("address") { add(address) }
                    put("mtu", mtu)
                    put("auto_route", true)
                    put("stack", "gvisor")
                }
            }
            putJsonArray("outbounds") {
                outbounds()
                if (bypass != null) addDirectOutbound()
            }
            putIosRoute(bypass)
            putJsonObject("experimental") {
                putJsonObject("cache_file") {
                    put("enabled", true)
                    put("store_fakeip", true)
                }
            }
        }
        return obj.toString()
    }

    private fun JsonObjectBuilder.putIosDns(remoteOverTcp: Boolean, direct: DirectDns, bypass: Routing.BypassRussia?) {
        putJsonObject("dns") {
            putJsonArray("servers") {
                addRemoteDnsServer(overTcp = remoteOverTcp)
                addDirectDnsServer(direct)
                addFakeIpServer()
            }
            putJsonArray("rules") {
                if (bypass != null) addRussianNamesRule()
                addFakeIpRules()
            }
            put("final", "dns-remote")
            if (bypass != null) put("reverse_mapping", true)
        }
    }

    private fun JsonObjectBuilder.putIosRoute(bypass: Routing.BypassRussia?) {
        putJsonObject("route") {
            if (bypass != null) putRuleSetDeclarations(bypass)
            putJsonArray("rules") {
                addJsonObject { put("action", "sniff") }
                addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                if (bypass != null) addBypassRouteRules()
            }
            put("final", "out")
            put("default_domain_resolver", "dns-direct")
        }
    }

    fun buildOlcrtcSocks(olcrtcPort: Int, socksPort: Int = SINGBOX_SOCKS_PORT): String =
        buildSocksChain(olcrtcPort, socksPort)

    private fun render(socksPort: Int, routing: Routing, outbounds: JsonArrayBuilder.() -> Unit): String {
        val bypass = routing as? Routing.BypassRussia
        val obj = buildJsonObject {
            // Without this sing-box applies its own default, which is "info" — and
            // that names every connection the user makes, in a log we invite them to
            // export. This renderer is behind the plain socks path, so it is the one
            // most users are actually on.
            putJsonObject("log") { put("level", "warn") }
            if (bypass != null) putBypassDns(bypass, remoteOverTcp = false)
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "socks"); put("tag", "in")
                    put("listen", "127.0.0.1"); put("listen_port", socksPort)
                }
            }
            putJsonArray("outbounds") {
                outbounds()
                if (bypass != null) addDirectOutbound()
            }
            if (bypass != null) putBypassRoute(bypass)
        }
        return obj.toString()
    }

    private fun JsonArrayBuilder.addDirectOutbound() {
        addJsonObject { put("type", "direct"); put("tag", "direct") }
    }

    /** The tunnel-side resolver, reached through `out`; TCP when the upstream's UDP is lossy. */
    private fun JsonArrayBuilder.addRemoteDnsServer(overTcp: Boolean) {
        addJsonObject {
            put("type", if (overTcp) "tcp" else "udp"); put("tag", "dns-remote")
            put("server", REMOTE_DNS_SERVER); put("detour", "out")
        }
    }

    /**
     * The resolver for names that go direct — and for the outbound's own server
     * name. No `detour`: the default dialer already goes straight out, pinned to
     * the physical interface on iOS, outside the VPN on Android, and sing-box
     * refuses, at start rather than at check, a detour to a direct outbound
     * with no options: "detour to an empty direct outbound makes no sense".
     * That line is what the first Bypass Russia tunnel on a phone died of.
     */
    private fun JsonArrayBuilder.addDirectDnsServer(direct: DirectDns) {
        addJsonObject {
            put("tag", "dns-direct")
            when (direct) {
                DirectDns.System -> put("type", "local")
                is DirectDns.Servers -> {
                    put("type", "udp"); put("server", direct.pick())
                }
                DirectDns.Placeholder -> {
                    put("type", "udp"); put("server", DIRECT_DNS_PLACEHOLDER)
                }
            }
        }
    }

    /** Fake addresses for the tunnel's names; IPv4 only, see [FAKE_IP_RANGE]. */
    private fun JsonArrayBuilder.addFakeIpServer() {
        addJsonObject {
            put("type", "fakeip"); put("tag", "dns-fakeip")
            put("inet4_range", FAKE_IP_RANGE)
        }
    }

    /**
     * A and AAAA get a fake address at once; HTTPS-type queries, which a
     * client sends beside them and would otherwise wait on the tunnel for,
     * are refused, which a client treats as "no such record" and carries on.
     * Anything else still goes wherever `final` points.
     */
    private fun JsonArrayBuilder.addFakeIpRules() {
        addJsonObject {
            putJsonArray("query_type") { add("A"); add("AAAA") }
            put("server", "dns-fakeip")
        }
        addJsonObject {
            putJsonArray("query_type") { add("HTTPS") }
            put("action", "reject")
        }
    }

    /** Names on the Russian lists resolve on the network underneath. */
    private fun JsonArrayBuilder.addRussianNamesRule() {
        addJsonObject {
            putJsonArray("rule_set") { RuleSets.domains.forEach { add(it.tag) } }
            put("server", "dns-direct")
        }
    }

    private fun JsonObjectBuilder.putRuleSetDeclarations(bypass: Routing.BypassRussia) {
        putJsonArray("rule_set") {
            RuleSets.all.forEach { file ->
                addJsonObject {
                    put("type", "local"); put("tag", file.tag)
                    put("format", "binary"); put("path", "${bypass.ruleSetDir}/${file.name}")
                }
            }
        }
    }

    /**
     * After `sniff` and `hijack-dns`, in an order that matters: the local
     * network direct first only because it is cheaper to match, then the
     * Russian lists. Domain matches come from the sniff, the reverse mapping or
     * the fake-address store; the IP list matches raw-address dials. sing-box
     * skips IP rules for an unresolved name, so nothing here resolves a
     * foreign name on the network underneath.
     */
    private fun JsonArrayBuilder.addBypassRouteRules() {
        addJsonObject { put("ip_is_private", true); put("outbound", "direct") }
        addJsonObject {
            putJsonArray("rule_set") { RuleSets.all.forEach { add(it.tag) } }
            put("outbound", "direct")
        }
    }

    /**
     * The split for the socks shapes (Android, where hev-socks5-tunnel hands
     * sing-box a hostname per connection): names on the Russian lists resolve
     * on the network underneath, everything else through the tunnel.
     * `dns-remote` first because the first server answers what no rule claims,
     * and `final` says so explicitly as well. `reverse_mapping` keeps the name
     * of every address handed out, so a connection to it is matched by the
     * domain lists even when nothing in it can be sniffed.
     */
    private fun JsonObjectBuilder.putBypassDns(bypass: Routing.BypassRussia, remoteOverTcp: Boolean) {
        putJsonObject("dns") {
            putJsonArray("servers") {
                addRemoteDnsServer(overTcp = remoteOverTcp)
                addDirectDnsServer(bypass.directDns)
            }
            putJsonArray("rules") { addRussianNamesRule() }
            put("final", "dns-remote")
            put("reverse_mapping", true)
        }
    }

    /**
     * `sniff` first so a TLS or HTTP connection carries its domain; then
     * `hijack-dns`, so the system's queries are answered here rather than
     * forwarded as datagrams; then the bypass rules. `default_domain_resolver`
     * is what an outbound uses to dial a *name*: the server's own hostname in
     * `out`, and any Russian name `direct` is handed — both belong on the
     * network underneath. Tunnel-bound names never reach it; sing-box sends
     * those to the server unresolved.
     */
    private fun JsonObjectBuilder.putBypassRoute(bypass: Routing.BypassRussia) {
        putJsonObject("route") {
            putRuleSetDeclarations(bypass)
            putJsonArray("rules") {
                addJsonObject { put("action", "sniff") }
                addJsonObject { put("action", "hijack-dns"); put("port", 53) }
                addBypassRouteRules()
            }
            put("final", "out")
            put("default_domain_resolver", "dns-direct")
        }
    }

    private fun JsonArrayBuilder.addOutbound(spec: OutboundSpec) {
        when (spec) {
            is OutboundSpec.Vless -> addJsonObject {
                put("type", "vless"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("uuid", spec.uuid); put("packet_encoding", "xudp")
                if (spec.flow != null) put("flow", spec.flow)
                if (spec.transport is TransportSpec.Grpc) putJsonObject("transport") {
                    put("type", "grpc")
                    put("service_name", spec.transport.serviceName)
                }
                putJsonObject("tls") {
                    put("enabled", true); put("server_name", spec.sni)
                    putJsonObject("utls") { put("enabled", true); put("fingerprint", spec.fingerprint) }
                    // Same rule as XrayConfig: a link without a public key is
                    // VLESS over ordinary TLS, and `TransportKind.Tls` exists to
                    // name exactly that case. A reality block with an empty key
                    // is not a weaker handshake, it is a rejected config.
                    if (spec.publicKey.isNotBlank()) {
                        putJsonObject("reality") {
                            put("enabled", true)
                            put("public_key", spec.publicKey)
                            put("short_id", spec.shortId)
                        }
                    }
                }
                // Deliberately loud rather than best-effort. sing-box has no
                // xhttp transport (see XrayConfig), and emitting one anyway
                // produced a config the core silently refused — on iOS that
                // looked like a tunnel that connected and carried nothing.
                // xhttp belongs to Xray; callers route it there.
                require(spec.transport !is TransportSpec.Xhttp) {
                    "sing-box cannot speak xhttp — build this location with XrayConfig.buildXhttp"
                }
            }
            is OutboundSpec.Hysteria2 -> addJsonObject {
                put("type", "hysteria2"); put("tag", "out")
                put("server", spec.host); put("server_port", spec.port)
                put("password", spec.password)
                if (spec.obfsPassword != null) {
                    putJsonObject("obfs") {
                        put("type", "salamander"); put("password", spec.obfsPassword)
                    }
                }
                putJsonObject("tls") {
                    put("enabled", true); put("server_name", spec.sni)
                    // A published pin means the server presents a self-signed
                    // certificate that no CA store can validate. sing-box has no
                    // pinning option, so verifying against the system store would
                    // reject every connection — which is exactly what it did. Skip
                    // verification in that case; the Salamander obfuscation and the
                    // auth password still gate the connection, but note that the
                    // fingerprint the operator published is NOT being checked.
                    put("insecure", spec.insecure || spec.certPinSha256 != null)
                }
            }
        }
    }
}
