### Android: Connected, and nothing loads

Since 1.0.443, when the phone moved between Wi-Fi and mobile data, a Reality, Hysteria2, Trojan, VMess, Shadowsocks or XHTTP connection reconnected, said Connected and carried nothing until you disconnected and connected again. The engine came back on a new local port while the tunnel kept pointing at the old one. The engine now keeps one port for the whole session, and the tunnel is pointed at it again whenever that changes. olcRTC rooms were not affected.

### Android: servers named by hostname

A server list whose servers are named by hostname rather than by address did not connect on Android with routing set to Global: Reality, Hysteria2, Trojan, VMess and Shadowsocks came up and carried nothing ("No traffic reached the internet through the tunnel"). The engine had no resolver to ask for the server's own name, and now gets the network's. Smart connect's check of such a server failed for the same reason and no longer does. When a connection comes up and carries nothing, the log now includes what the engine itself said.
