package org.olcbox.app.net.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.XrayGeodata

/**
 * What listens for the traffic a core carries, and who may use it.
 *
 * A core has one inbound of its own, tagged [TAG], from which the routing
 * sends the system's name queries to the core's DNS. It is one of two:
 *  - [Tunnel]: the tun's (hev's), on 127.0.0.1, demanding the login the app
 *    made for the session;
 *  - [Proxy]: proxy mode's, for the other apps of the device, on a loopback
 *    address, demanding the user's login or none. The Go cores listen so in
 *    proxy mode: on 127.0.0.1, at the port the settings name, with no login.
 *
 * Beside it, when the user shares the tunnel with the local network
 * (docs/lan-sharing.md), one more: [Lan], tagged [LAN_TAG], on one private
 * address of the machine, demanding the login the devices there are given.
 * It is in the same core, so its clients follow the same rules over the same
 * line, datagrams too: the core opens a client's UDP relay on the address
 * the client reached. Being in the same core, it is also proved by the own
 * inbound: a LAN port another process holds fails the whole core, as the Go
 * cores' second loopback listener made it fail theirs.
 *
 * xray-rust decides what it takes, and [refusal] says in a sentence why it
 * would refuse a [Plan]; nothing is written for one. Its rules, read in
 * `crates/xray-config` (`parser.rs` `parse_inbound`, `parser/socks.rs`,
 * `surface.rs` `SOCKS_SETTINGS` and `SOCKS_ACCOUNT`) and in the runtime's
 * start, which checks the bound address again:
 *  - a SOCKS listener off loopback is refused unless its settings say
 *    `allowUnauthenticatedLan`, accounts or not. The LAN inbound says it,
 *    beside `auth: password`, and the core warns that the inbound is exposed
 *    beyond loopback, which is what LAN sharing is;
 *  - `auth: password` takes 1 to 64 accounts and `noauth` none;
 *  - a user and a pass are at most 255 bytes of UTF-8 each;
 *  - the listen address is bound as it is written: a bracketed IPv6 address
 *    does not bind, and a name binds wherever the system resolves it.
 */
internal object CoreInbound {
    /** The tag the routing matches the core's own inbound by. */
    const val TAG = "in"

    /** The LAN inbound's tag, the name the Go cores' LAN listener has. */
    const val LAN_TAG = "lan-in"

    /** Where the tunnel's inbound listens, and proxy mode's unless it is told another loopback address. */
    const val LOOPBACK = "127.0.0.1"

    /** What a SOCKS5 login holds of a username and of a password (RFC 1929), and so what the core takes. */
    const val MAX_CREDENTIAL_BYTES = 255

    /** The ports a LAN listener may take: the Go cores' range, above the privileged ones. */
    val LAN_PORTS: IntRange = 1024..65535

    /** A port no listener can have. */
    const val PORT_REFUSAL = "a listener's port is from 1 to 65535"

    /** The tun's inbound with no login, in the words [requireLogin] has always used. */
    const val LOGIN_REFUSAL = "a loopback SOCKS port other apps can reach needs a login"

    /** A login with a blank half: a SOCKS5 login is both. */
    const val HALF_LOGIN_REFUSAL = "a login is a username and a password, neither of them blank"

    /** A login longer than SOCKS5 carries. */
    const val LONG_LOGIN_REFUSAL = "a SOCKS5 login holds at most 255 bytes of username and 255 of password"

    /** Proxy mode on an address other devices reach. */
    const val PROXY_LISTEN_REFUSAL =
        "proxy mode listens on a loopback address (127.0.0.1 or ::1): an address other devices reach is LAN sharing's, which demands a login"

    /** LAN sharing anywhere but one private IPv4 address. */
    const val LAN_ADDRESS_REFUSAL =
        "LAN sharing listens on one private IPv4 address of this machine (in 10/8, 172.16/12 or 192.168/16), not on every address, a public one or a name"

    /** LAN sharing on a privileged port, or on the core's own. */
    const val LAN_PORT_REFUSAL = "LAN sharing's port is from 1024 to 65535 and is not the core's own"

    /** LAN sharing with a blank login. */
    const val LAN_LOGIN_REFUSAL =
        "LAN sharing demands a login: the username and the password the devices on the network are given"

    /** The core's own inbound, tagged [TAG]: the one the session's traffic comes in by. */
    sealed interface Own {
        val port: Int
    }

    /** The tun's: SOCKS5 on 127.0.0.1:[port], demanding [login], the pair hev is given. */
    class Tunnel(override val port: Int, val login: SocksLogin) : Own

    /**
     * Proxy mode's: SOCKS5 (and HTTP CONNECT, which the core's SOCKS inbound
     * also answers) on [listen], a loopback address, at [port]; demanding
     * [login] when there is one, open to the device's apps when there is none.
     */
    class Proxy(override val port: Int, val login: SocksLogin? = null, val listen: String = LOOPBACK) : Own

    /** LAN sharing's: SOCKS5 on [address], one private IPv4 address of this machine, at [port], demanding [login]. */
    class Lan(val address: String, val port: Int, val login: SocksLogin)

    /** What a core listens on: its own inbound and, while the tunnel is shared, the LAN's. */
    class Plan(val own: Own, val lan: Lan? = null)

    /** The tun's login must be one: a loopback port is open to every app on the device, and without a login any of them rides the line. */
    fun requireLogin(login: SocksLogin) {
        loginRefusal(login, LOGIN_REFUSAL)?.let { throw IllegalArgumentException(it) }
    }

    /** Why the core would refuse to listen as [plan] says, or null when it takes it. */
    fun refusal(plan: Plan): String? = ownRefusal(plan.own) ?: plan.lan?.let { lanRefusal(it, plan.own.port) }

    /**
     * core.json for [spec] listening as [plan] says: what [CoreConfig.build]
     * writes for the line, its routing and its names, with [plan]'s inbounds
     * in the place of the tunnel's one. A plan of the tunnel alone gives the
     * config [CoreConfig.build] writes for its port and login, byte for byte.
     *
     * Written over and not passed through, because [CoreConfig.build] takes
     * the tunnel's port and login and nothing else of an inbound. Throws
     * IllegalArgumentException for a plan [refusal] has a word for, and for
     * whatever [CoreConfig.build] refuses.
     */
    fun build(
        spec: OutboundSpec,
        plan: Plan,
        routing: Routing = Routing.Global,
        geodata: XrayGeodata.Lists? = null,
        verboseLogs: Boolean = false,
    ): String {
        refusal(plan)?.let { throw IllegalArgumentException("the Rust core cannot listen so: $it") }
        val written = CoreConfig.build(spec, WRITTEN_OVER, routing, geodata, verboseLogs, socksPort = plan.own.port)
        val config = Json.parseToJsonElement(written).jsonObject
        return JsonObject(config + ("inbounds" to buildJsonArray { addInbounds(plan) })).toString()
    }

    /** The login [build] hands [CoreConfig.build] for the inbound it writes over: never in a config [build] returns. */
    private val WRITTEN_OVER = SocksLogin("written-over-user", "written-over-password")

    /** [listen] as the core is to be given it when it is a loopback address (127/8 or ::1, brackets taken off), else null. */
    fun loopbackAddress(listen: String): String? {
        val bare = listen.trim().removeSurrounding("[", "]")
        return bare.takeIf { it == "::1" || ipv4(it)?.first() == 127 }
    }

    /** Whether [address] is an IPv4 address in 10/8, 172.16/12 or 192.168/16, the ranges the Go cores share on. */
    fun isPrivateIpv4(address: String): Boolean {
        val (first, second) = ipv4(address) ?: return false
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }

    private fun ownRefusal(own: Own): String? = when (own) {
        is Tunnel -> portRefusal(own.port) ?: loginRefusal(own.login, LOGIN_REFUSAL)
        is Proxy -> portRefusal(own.port)
            ?: PROXY_LISTEN_REFUSAL.takeIf { loopbackAddress(own.listen) == null }
            ?: own.login?.let { loginRefusal(it, HALF_LOGIN_REFUSAL) }
    }

    private fun lanRefusal(lan: Lan, ownPort: Int): String? = when {
        !isPrivateIpv4(lan.address) -> LAN_ADDRESS_REFUSAL
        lan.port !in LAN_PORTS || lan.port == ownPort -> LAN_PORT_REFUSAL
        else -> loginRefusal(lan.login, LAN_LOGIN_REFUSAL)
    }

    private fun portRefusal(port: Int): String? = PORT_REFUSAL.takeIf { port !in 1..65535 }

    private fun loginRefusal(login: SocksLogin, blank: String): String? = when {
        login.username.isBlank() || login.password.isBlank() -> blank
        login.username.encodeToByteArray().size > MAX_CREDENTIAL_BYTES ||
            login.password.encodeToByteArray().size > MAX_CREDENTIAL_BYTES -> LONG_LOGIN_REFUSAL
        else -> null
    }

    /** Four decimal octets as the core's parser reads an IPv4 address: no leading zeros, none above 255. */
    private val IPV4 = Regex("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})")

    private fun ipv4(text: String): List<Int>? =
        IPV4.matchEntire(text)?.groupValues?.drop(1)?.map { it.toInt() }?.takeIf { octets -> octets.all { it <= 255 } }
}

/**
 * [plan]'s inbounds: the core's own, then the LAN's. Each is SOCKS5 with UDP
 * on (hev forwards it over TCP; a LAN client asks for its relay). The sniff
 * names a connection for the rules only (`routeOnly`): a fake address is
 * turned back into its name before any of this, and a real address stays
 * the address it was dialled at, as with sing-box's `sniff`.
 *
 * Throws IllegalArgumentException for a plan [CoreInbound.refusal] has a word for.
 */
internal fun JsonArrayBuilder.addInbounds(plan: CoreInbound.Plan) {
    CoreInbound.refusal(plan)?.let { throw IllegalArgumentException(it) }
    when (val own = plan.own) {
        is CoreInbound.Tunnel -> addSocks(CoreInbound.TAG, CoreInbound.LOOPBACK, own.port, own.login, lan = false)
        is CoreInbound.Proxy ->
            addSocks(CoreInbound.TAG, requireNotNull(CoreInbound.loopbackAddress(own.listen)), own.port, own.login, lan = false)
    }
    plan.lan?.let { addSocks(CoreInbound.LAN_TAG, it.address, it.port, it.login, lan = true) }
}

/** The tun's inbound alone: SOCKS5 on 127.0.0.1:[port] with the login hev is given. */
internal fun JsonArrayBuilder.addSocksInbound(port: Int, login: SocksLogin) {
    addInbounds(CoreInbound.Plan(CoreInbound.Tunnel(port, login)))
}

private fun JsonArrayBuilder.addSocks(tag: String, listen: String, port: Int, login: SocksLogin?, lan: Boolean) {
    addJsonObject {
        put("tag", tag); put("protocol", "socks")
        put("listen", listen); put("port", port)
        putJsonObject("settings") {
            if (login != null) {
                put("auth", "password")
                putJsonArray("accounts") {
                    addJsonObject {
                        put("user", login.username)
                        put("pass", login.password)
                    }
                }
            } else {
                put("auth", "noauth")
            }
            put("udp", true)
            // The parser's name for a listener off loopback, which it refuses
            // without this whether or not it demands a login. This one does.
            if (lan) put("allowUnauthenticatedLan", true)
        }
        putJsonObject("sniffing") {
            put("enabled", true)
            putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
            put("routeOnly", true)
        }
    }
}
