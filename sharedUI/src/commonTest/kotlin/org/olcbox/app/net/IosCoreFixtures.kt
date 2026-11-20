package org.olcbox.app.net

/**
 * One share link per [IosCoreLine], shaped as ProofKit hands them out, for the
 * shape tests here and for the reference configs the JVM dump test writes
 * (`RustCoreConfigDumpTest`). The addresses are loopback and the keys made up.
 */
object IosCoreFixtures {
    const val PUBLIC_KEY = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"

    /** A SHA-256 in OpenSSL's spelling, as `pinSHA256` usually comes. */
    const val PIN = "9B:D5:34:57:E3:0A:CF:B2:35:6C:8E:A2:8E:DB:9F:95:2E:06:01:F5:6A:1A:A3:70:1A:5B:4E:A2:78:A1:11:F8"

    /** The pair the iOS shapes are written with in tests; the app generates its own. */
    val login = SocksLogin("ios-tun-user", "ios-tun-password")

    /**
     * Stand-ins for the Russian lists, one of each spelling the bundled ones
     * use (`domain:`, `full:`, IPv4 ranges), so a line's Bypass Russia config
     * stays small and does not move when the bundled lists are refreshed.
     */
    val lists = XrayGeodata.Lists(
        domains = listOf("domain:sberbank.ru", "full:ozon.ru", "domain:ru"),
        cidrs = listOf("5.8.0.0/19", "77.88.0.0/18"),
    )

    val links: Map<IosCoreLine, String> = mapOf(
        IosCoreLine.RealityVision to
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
            "?security=reality&encryption=none&pbk=$PUBLIC_KEY" +
            "&sid=ab12cd34&fp=chrome&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#DE-reality",
        IosCoreLine.RealityGrpc to
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:2053" +
            "?security=reality&encryption=none&pbk=$PUBLIC_KEY" +
            "&sid=ab12cd34&fp=chrome&sni=rutube.ru&type=grpc&serviceName=media%2Fsync#KZ-grpc",
        IosCoreLine.WebTunnelWs to
            "vless://33333333-3333-3333-3333-333333333333@127.0.0.1:443" +
            "?security=tls&encryption=none&sni=wt.example.com&fp=chrome" +
            "&type=ws&path=%2Fv&host=wt.example.com&alpn=h2%2Chttp%2F1.1#NL-webtunnel",
        // ProofKit's WebTunnel line as its subscription writes it
        // (coordinator/src/routes/subscription.rs), with a test address.
        IosCoreLine.WebTunnelHttpUpgrade to
            "vless://33333333-3333-3333-3333-333333333333@127.0.0.1:443" +
            "?encryption=none&security=tls&sni=wt.example.com&host=wt.example.com" +
            "&type=httpupgrade&path=%2Fu&fp=chrome&alpn=h2%2Chttp%2F1.1#NL-webtunnel-hu",
        IosCoreLine.Hysteria2 to
            "hysteria2://PASSWORD123@127.0.0.1:443" +
            "?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&pinSHA256=$PIN#RU-hy2",
        IosCoreLine.XhttpReality to
            "vless://22222222-2222-2222-2222-222222222222@127.0.0.1:443" +
            "?type=xhttp&security=reality&encryption=none&pbk=$PUBLIC_KEY" +
            "&sid=cd34&fp=chrome&sni=www.microsoft.com&path=%2Fdownload&host=www.microsoft.com&mode=packet-up#FI-xhttp",
    )

    fun spec(line: IosCoreLine): OutboundSpec =
        requireNotNull(LinkParser.parse(links.getValue(line))) { "fixture for $line does not parse" }
}
