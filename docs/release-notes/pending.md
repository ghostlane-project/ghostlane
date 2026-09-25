### Android and iPhone: the app's local proxy asks for a password

Between the VPN and the engine that carries it, the app runs a small proxy on the phone itself. On Android it sat on a fixed, well-known port with no password for most kinds of connection (and on the iPhone for XHTTP), so any other app could use it to reach the internet through the tunnel or to learn the VPN's address, which is exactly what some apps look for. It now asks for a login only Ghostlane knows, and on Android it moves to a new port every time you connect.

### Server lists: provider links, and lists that outlive a blocked domain

A provider's subscription page can now open the app with a `ghostlane://` link, including the `ghostlane://add/…` form such pages use for other apps. A provider can also keep its users when its domain is blocked: when a list's usual address does not answer, the app asks the spare address the provider named in advance, and when the provider says the list has moved, the app follows it. Both are accepted only over a secure connection. On Android and computers, a provider's short note now appears under its server list.

### Android: always-on VPN

With Android's own "Always-on VPN" turned on for Ghostlane, the connection now comes up by itself after a restart and whenever Android brings it back; before, the app stopped the moment Android started it. Connection settings has a row that opens Android's VPN screen, where always-on and "Block connections without VPN" are set. The "Russian apps" preset for apps that bypass the VPN now also finds T-Bank, Avito, Wildberries and VK.

### Android and computers: smart connect

When a server is published over several kinds of connection, the app now checks them before connecting and uses the first one through which a real download gets through, not just a first handshake. If none does, it ends on olcRTC in the same country, and when only domestic sites answer (a mobile "whitelist" shutdown) it goes to olcRTC straight away. It never changes the country by itself, remembers what worked for each server, and can be turned off in the server list settings.

### Server lists: Trojan, Shadowsocks and VMess

Links and subscriptions with Trojan, Shadowsocks (including Shadowsocks 2022) and VMess servers now import and connect, as do VLESS servers that use WebSocket or HTTP upgrade, which used to import but never connect. A link that asks for something the app cannot carry, such as a Shadowsocks plugin, is left out instead of being added as a server that cannot work.

### The app in Russian, Chinese and Persian

On a phone or computer set to Russian, Chinese or Persian, the app is now in that language: every screen, message and notification, and the notice shown before the first connection. On Android, Persian is laid out right to left. Other languages still see English.

### Android and computers: your own routing rules, and only blocked sites through the tunnel

Routing has a new mode, "Only blocked sites through the tunnel": sites blocked in Russia, and services that shut Russian users out, go through the tunnel, and everything else connects directly. The list is Re:filter's, bundled with the app, so it works offline; it includes whole address ranges of some large services, so other sites hosted there go through the tunnel too. In any mode you can now add rules of your own: domains or IP addresses that always connect directly, or always go through the tunnel. On Android, olcRTC rooms still carry everything in the new mode.

### Android TV

Ghostlane now installs on Android TV and appears in its launcher. Every control can be reached with the remote, with a clear mark on the one in focus. A TV has no camera to scan with, so a server list comes from your phone: the TV shows a code, the phone opens a page on the same home network, and the link pasted there lands on the TV. The same option appears on any Android device without a camera.

### Android and computers: servers for your router

Server lists now have a Router button in "Server lists & sharing". It gives each server of the list in the form a router takes: the link or a sing-box outbound for Podkop on OpenWrt, an Xray outbound for XKeen on Keenetic. Encrypted server lists do not offer it: their provider chose that their servers are not read out of them.
