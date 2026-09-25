### Android and iPhone: the app's local proxy asks for a password

Between the VPN and the engine that carries it, the app runs a small proxy on the phone itself. On Android it sat on a fixed, well-known port with no password for most kinds of connection (and on the iPhone for XHTTP), so any other app could use it to reach the internet through the tunnel or to learn the VPN's address, which is exactly what some apps look for. It now asks for a login only Ghostlane knows, and on Android it moves to a new port every time you connect.

### Server lists: provider links, and lists that outlive a blocked domain

A provider's subscription page can now open the app with a `ghostlane://` link, including the `ghostlane://add/…` form such pages use for other apps. A provider can also keep its users when its domain is blocked: when a list's usual address does not answer, the app asks the spare address the provider named in advance, and when the provider says the list has moved, the app follows it. Both are accepted only over a secure connection. On Android and computers, a provider's short note now appears under its server list.
