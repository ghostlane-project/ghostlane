### Android: Connected, and nothing loads

Since 1.0.443, when the phone moved between Wi-Fi and mobile data, a Reality, Hysteria2, Trojan, VMess, Shadowsocks or XHTTP connection reconnected, said Connected and carried nothing until you disconnected and connected again. The engine came back on a new local port while the tunnel kept pointing at the old one. The engine now keeps one port for the whole session, and the tunnel is pointed at it again whenever that changes. olcRTC rooms were not affected.

### Android: servers named by hostname

A server list whose servers are named by hostname rather than by address did not connect on Android with routing set to Global: Reality, Hysteria2, Trojan, VMess and Shadowsocks came up and carried nothing ("No traffic reached the internet through the tunnel"). The engine had no resolver to ask for the server's own name, and now gets the network's. Smart connect's check of such a server failed for the same reason and no longer does. When a connection comes up and carries nothing, the log now includes what the engine itself said.

### Links: the HTTP transport, and VLESS without Reality

Three things a server list could say that the app did not read (ghostlane#82). A VLESS, Trojan or VMess line over sing-box's HTTP transport (`type=http`, or `h2`) was dialled as plain TCP, so it came up and carried nothing; it now uses that transport. VLESS with `security=none` was dialled with TLS anyway; it is now dialled as it asks. And `allowInsecure=1` on a VLESS line over ordinary TLS, for a server with a self-signed certificate, is now honoured as it already was for Trojan and VMess. Reality lines are unaffected. The router export offers no Xray outbound for the HTTP transport, which Xray no longer has.

### Android: the tunnel is held for the whole session

Until now some failures made the app close the VPN and open it again: tun2socks stopping, an olcRTC call dropping, choosing another server while connected. For as long as that took, and for every retry when the server did not come back, the phone's traffic went out directly, with your own address. From the first successful connection until you disconnect, the VPN interface now stays up and everything restarts behind it: apps on the VPN wait instead of going around it, and the notification says so. Editing split tunnelling while connected replaces the interface without a gap. If the first connect fails, nothing is held: traffic stays as it was before you tapped.

### Android: is the system's kill switch on?

The one thing the app cannot hold is its own death: if Android kills it, the VPN goes with it until it is started again. Android's answer is "Always-on VPN" with "Block connections without VPN", in its own settings. On Android 10 and later the "Always-on VPN" row in connection settings now says, while you are connected, whether that is off, on, or on and blocking, and one tap opens the system screen. With blocking on it also says which apps are left without network: the ones you excluded from the VPN, or, when only chosen apps use it, every other app. Android cuts those off itself, and no VPN app can exempt them.

### Server lists are saved in one step

The file that holds your server lists and settings was written in place: emptied, then filled. An app that died in between, or was read at that moment, came back to a file that could not be read and started over with no lists. It is now written beside the old file and moved over it, on Android and on the desktop, so there is always a whole file to read. On Android the app and its VPN service also share one store now, instead of each keeping its own view of the file.

### Android: another server of the same country when yours stops answering

Until now a server that died kept the session reconnecting to it for as long as you let it. With smart connect on (it is by default), after two failed reconnects and half a minute the app now looks at the rest of the same server list: the other transports of the same exit first, then the other servers of the same country, then olcRTC of that country, and olcRTC first when only domestic sites answer. Each server is checked by an engine of its own before the session moves, and the tunnel stays up throughout, so nothing goes around it while the app looks. The first line that carries traffic becomes the active one and a message says which. The country and the list never change, nothing moves back by itself, and with smart connect off the session keeps retrying the one server as before.

### Desktop: an engine that stops no longer takes the tunnel down

On macOS, Windows and Linux, when the olcRTC engine stopped in the middle of a session the app took its own tunnel down and showed an error, and from that moment your traffic went out directly, with your own address. When the engine of a Reality, Hysteria2, Trojan, VMess, Shadowsocks or XHTTP connection stopped, nothing noticed at all: the app went on saying Connected and nothing loaded. Both are now noticed, within a couple of seconds, and answered the same way. The tunnel stays exactly as it is, the app says Reconnecting, and the engine is started again behind it after 2, 4, 8, 16 and then every 30 seconds, until it is back or you disconnect. Meanwhile apps wait instead of going around the tunnel. On Linux, starting the olcRTC engine again asks for the administrator password again, and nothing passes until it is given. In proxy mode the system's proxy setting is kept the same way. If the tunnel's own process stops, the session still ends with an error, as before.

### Linux desktop: every kind of server in tunnel mode

In the Linux app's tunnel mode only olcRTC rooms connected. A Reality, Hysteria2, Trojan, VMess, Shadowsocks or XHTTP server came up and then failed its check ("No traffic reached the internet through the tunnel") unless the app itself ran as root: the tunnel's rule takes every user's traffic, the connection to the server included, and lets only root past. The server's connection is now bound to the machine's network interface, which takes it past the tunnel and needs no privilege on Linux 5.7 and later. A server named by hostname is resolved by the network's own resolver and not through the tunnel. One case is left: an XHTTP server named by hostname may still not connect in this mode, and the log says so; proxy mode carries it. The tunnel's own process is watched behind these servers too, so its death no longer leaves a session that says Connected.

### Windows, proxy mode: LAN sharing changed while connected

In proxy mode on Windows, switching LAN sharing on or off, choosing its address or renewing its login while connected to a Reality, Hysteria2, Trojan, VMess, Shadowsocks or XHTTP server pointed the system's proxy at a port nobody was listening on, and pages stopped loading until the next connect. In an olcRTC room with routing rules it pointed past the rules instead, so everything went through the room. The change now touches LAN sharing only.
