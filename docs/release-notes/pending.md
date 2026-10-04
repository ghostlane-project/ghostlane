### Android: Connected, and nothing loads

Since 1.0.443, when the phone moved between Wi-Fi and mobile data, a Reality, Hysteria2, Trojan, VMess, Shadowsocks or XHTTP connection reconnected, said Connected and carried nothing until you disconnected and connected again. The engine came back on a new local port while the tunnel kept pointing at the old one. The engine now keeps one port for the whole session, and the tunnel is pointed at it again whenever that changes. olcRTC rooms were not affected.

### Android: servers named by hostname

A server list whose servers are named by hostname rather than by address did not connect on Android with routing set to Global: Reality, Hysteria2, Trojan, VMess and Shadowsocks came up and carried nothing ("No traffic reached the internet through the tunnel"). The engine had no resolver to ask for the server's own name, and now gets the network's. Smart connect's check of such a server failed for the same reason and no longer does. When a connection comes up and carries nothing, the log now includes what the engine itself said.

### Links: the HTTP transport, and VLESS without Reality

Three things a server list could say that the app did not read (ghostlane#82). A VLESS, Trojan or VMess line over sing-box's HTTP transport (`type=http`, or `h2`) was dialled as plain TCP, so it came up and carried nothing; it now uses that transport. VLESS with `security=none` was dialled with TLS anyway; it is now dialled as it asks. And `allowInsecure=1` on a VLESS line over ordinary TLS, for a server with a self-signed certificate, is now honoured as it already was for Trojan and VMess. Reality lines are unaffected. The router export offers no Xray outbound for the HTTP transport, which Xray no longer has.

### Android: the tunnel is held for the whole session

Until now some failures made the app close the VPN and open it again: tun2socks stopping, an olcRTC call dropping, choosing another server while connected. For as long as that took, and for every retry when the server did not come back, the phone's traffic went out directly, with your own address. From the first successful connection until you disconnect, the VPN interface now stays up and everything restarts behind it: apps on the VPN wait instead of going around it, and the notification says so. Editing split tunnelling while connected replaces the interface without a gap. If the first connect fails, nothing is held: traffic stays as it was before you tapped.
