### Android: Connected, and nothing loads

Since 1.0.443, when the phone moved between Wi-Fi and mobile data, a Reality, Hysteria2, Trojan, VMess, Shadowsocks or XHTTP connection reconnected, said Connected and carried nothing until you disconnected and connected again. The engine came back on a new local port while the tunnel kept pointing at the old one. The engine now keeps one port for the whole session, and the tunnel is pointed at it again whenever that changes. olcRTC rooms were not affected.
