package org.olcbox.app.vpn

/**
 * The configuration hev-socks5-tunnel runs the Android tun with, in hev's YAML.
 *
 * The service writes it to a file and hands hev the path; everything that
 * decides what the file says is here, shared with the JVM and tested there,
 * so a line hev reads differently from how it was meant is caught by a test
 * rather than by a phone.
 */
object HevTunnelConfig {
    /**
     * The resolver the tun announces. hev answers it itself (mapdns) with an
     * address from [MAPDNS_NETWORK]/[MAPDNS_NETMASK] and remembers the name,
     * so a connection to that address reaches the SOCKS server as the name.
     */
    const val MAPDNS_ADDRESS = "1.1.1.1"
    const val MAPDNS_NETWORK = "100.64.0.0"
    const val MAPDNS_NETMASK = "255.192.0.0"

    /**
     * [socksPort] is the user-set SOCKS port olcRTC listens on; [corePort] is
     * the port of the sing-box or Xray core hev feeds instead, null when hev
     * talks to olcRTC directly. [socksAddress] is where either one is reached.
     */
    fun yaml(
        mtu: Int,
        ipv4: String,
        socksAddress: String,
        socksPort: Int,
        corePort: Int?,
        username: String,
        password: String
    ): String = buildList {
        add("tunnel:")
        add("  name: tun0")
        add("  mtu: $mtu")
        add("  multi-queue: false")
        add("  ipv4: $ipv4")
        add("")
        add("socks5:")
        add("  address: $socksAddress")
        add("  port: ${corePort ?: socksPort}")
        // Standard SOCKS5 UDP ASSOCIATE, as iOS has always asked (HevTunnel.swift).
        // hev's other mode, 'tcp', carries each flow inside a TCP connection
        // opened with its own FWD UDP command (0x05), which only hev's own server
        // speaks. sing-box, Xray and the olcRTC engine take CONNECT and UDP
        // ASSOCIATE and refuse anything else, so under 'tcp' no app's UDP crossed
        // the tun at all, whichever of them hev was feeding.
        add("  udp: 'udp'")
        add("  pipeline: false")
        // Only olcRTC's local proxy asks for a login; the cores listen open — the
        // same rule OlcboxVpnService.verifyTunnel() already follows, stated there
        // in as many words.
        //
        // Sending credentials to a core makes hev offer username/password as its
        // only SOCKS method, and a sing-box or Xray inbound is built with no `auth`
        // at all, so it answers "no matching auth method" and closes. Nothing about
        // that is visible from here: the core's SOCKS port opens before it has
        // touched the server, so the transport reports ready, the tunnel is
        // established, and not one packet crosses.
        if (corePort == null) {
            add("  username: '$username'")
            add("  password: '$password'")
        }
        add("")
        add("mapdns:")
        add("  address: $MAPDNS_ADDRESS")
        add("  port: 53")
        add("  network: $MAPDNS_NETWORK")
        add("  netmask: $MAPDNS_NETMASK")
        add("  cache-size: 10000")
        add("")
        add("misc:")
        add("  task-stack-size: 24576")
        add("  tcp-buffer-size: 4096")
        add("  max-session-count: 1200")
        add("  connect-timeout: 10000")
        add("  tcp-read-write-timeout: 300000")
        add("  udp-read-write-timeout: 60000")
        add("  log-file: stderr")
        add("  log-level: warn")
    }.joinToString("\n")
}
