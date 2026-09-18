# Ghostlane support

Ghostlane is a free, open-source VPN client. You bring a server list from your
provider; the app connects to it. It has no account and nothing to buy.

## Getting a server list into the app

Tap **+** on the home screen, then one of:

- **Paste link or URI** — a server-list URL, a single server link (`vless://`,
  `hysteria2://`, `hy2://`), or an olcRTC room link.
- **Scan QR code** — the camera opens only while you are on that screen.
- **Import file** — a server list saved as a file.

The list appears grouped by where it came from. Pick a server and tap **Start**.

## Transports

- **olcRTC** — a tunnel inside a WebRTC video call to a public meeting service.
  Rooms have a fixed number of slots; the occupancy bar shows how full each one
  is before you join.
- **VLESS with Reality**, **VLESS over TLS** (including through a CDN),
  **Hysteria2** (with Salamander obfuscation) and **XHTTP**.

Move between them as the network around you changes. If a transport does not
connect, try another one from the same list before changing servers.

## Automatic server choice

In the server-list settings, **Lowest at connect** ranks the list's servers by
reachability before connecting and starts with the fastest. It is off by
default. It never joins an olcRTC room just to rank it, and it moves to the next
server only after a connection fails outright.

## Latency

A server with an address (VLESS, XHTTP, TLS, Hysteria2) can be measured at any
time: the bolt in the list header measures the whole list, **Measure** on the
selected card measures one server, and the result shows at the right of the
card's title. An olcRTC room has no address to probe; measuring one would mean
joining it and taking a seat. So a room is measured only while you are in it:
the status card shows `HTTP 245ms` (a small HTTPS request through the tunnel,
every 30 s while the home screen is open), and **Measure** on the connected card
measures it on demand. The chip next to the heading (AS SERVED, PING, A-Z) only
orders the list. Annotated screenshots (captions in Russian):
[connected](screenshots/help/latency-connected.ru.png),
[not connected](screenshots/help/latency-disconnected.ru.png).

## When something does not work

1. Check that the server list is current: pull to refresh, or open the list's
   settings and refresh it.
2. Try a different transport or server from the same list.
3. Export the diagnostics: the first button at the top of the home screen (a
   clock with an arrow) opens **Diagnostics**; **Export** saves a file, **Share**
   sends it. The export is scrubbed of addresses and credentials. On iOS it also contains the packet tunnel's own
   trace, which is the part that explains a dropped tunnel.
4. Open an issue in this repository and attach the export. Say which platform,
   which transport, and what the network was (Wi-Fi or cellular, which country).

## Privacy

The app collects nothing. See [privacy.md](privacy.md).

## Source

Everything the app does is in this repository. The olcRTC engine is at
[romanpodpriatov/olcrtc](https://github.com/romanpodpriatov/olcrtc).
