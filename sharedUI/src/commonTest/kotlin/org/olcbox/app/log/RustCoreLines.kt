package org.olcbox.app.log

/**
 * Lines as the Rust core and the olcRTC client write them, with documentation
 * addresses and made-up names in place of real ones. Each shape is a format string of
 * the core at the pinned commit; the comment on a line names where it comes from.
 */
object RustCoreLines {
    /** The parser's remark for allowInsecure (xray-config, stream.rs), as the FFI joins it: path, colon, message. */
    const val WAIVED_CHECK_HOST =
        "\$.outbounds[0].streamSettings.tlsSettings.allowInsecure: outbound `out` does not check the " +
            "certificate of vpn.example-host.net:443 (allowInsecure), so anyone on the path can pose as that server"
    const val WAIVED_CHECK_V4 =
        "\$.outbounds[1].streamSettings.tlsSettings.allowInsecure: outbound `backup` does not check the " +
            "certificate of 203.0.113.77:8443 (allowInsecure), so anyone on the path can pose as that server"
    const val WAIVED_CHECK_V6 =
        "\$.outbounds[2].streamSettings.tlsSettings.allowInsecure: outbound `v6` does not check the " +
            "certificate of [2001:db8:77::1]:443 (allowInsecure), so anyone on the path can pose as that server"

    /** The failover walk (olc-client supervisor.rs): the room is named by its label. */
    const val ROOM_VIDEO_LINK = "failover cycle=2 starting room=meet.example-call.ru/<hidden>"
    const val ROOM_SALUTE_CODE = "failover cycle=1 room=kx7-pq2-mn9 ended with error: no peer in room"
    const val ROOM_NUMERIC = "failover cycle=3 starting room=84120937"
    /** The give-up error (supervisor.rs, `profile {room:?}`), as the app logs it after a failed start. */
    const val GIVE_UP_CODE =
        "Rust core start failed: supervisor: max failover cycles exceeded after 1 cycle(s): " +
            "profile \"kx7-pq2-mn9\": no peer in room"
    const val GIVE_UP_HOST =
        "Rust core start failed: supervisor: max failover cycles exceeded after 1 cycle(s): " +
            "profile \"meet.example-call.ru/<hidden>\": no peer in room"
    const val ROOM_JOINED = "interop-p2p: joined 5d0c2f77aa31 as peer-4"

    /** The client's own lines (olc-client socks.rs, dns): a destination and a resolver. */
    const val TUNNEL_TO_HOST = "sid=14 tunnel to cdn.example-site.org:443"
    const val TUNNEL_TO_V4 = "sid=15 tunnel to 198.51.100.23:443"
    const val DNS_RESOLVER = "dns over the stream: session=9 resolver=198.51.100.53:53"
    const val DIAL_MAPPED = "sid=16 connect failed: dial ::ffff:198.51.100.24:443: connection refused"

    /** Lines that name nothing and must come through whole (access lines are redacted by the core itself). */
    val UNTOUCHED = listOf(
        "from <redacted-source> accepted <redacted-ip>:443 outbound=out",
        "failover cycle=1 room=<id> ended",
        "failover cycle=4 starting room=<malformed room link>",
        "\$.outbounds[0].streamSettings.realitySettings.fingerprint: not a known fingerprint",
        "ice connection state changed: connected",
    )
}
