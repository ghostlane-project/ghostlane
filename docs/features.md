# Features

Everything the Ghostlane app does, feature by feature and platform by platform. [Back to the README](../README.MD).

## Connecting and server lists

- One tap to connect, with the session's elapsed time and traffic on screen.
- In English, Russian, Chinese and Persian, following the system's language;
  Persian is laid out right to left on Android.
- Server lists by link, QR code or file; grouped, foldable, and refreshed on
  a schedule you choose. A `ghostlane://`, `proofkit://` or `proofkit.org/add`
  link imports one without a paste.
- What the provider says about your plan: quota, expiry, a short note, its own
  support and web links, read from the server list's own headers, and a list
  that survives its provider's blocked domain (see
  [Server lists](server-lists.md)).
- Smart connect: another transport when one is blocked, olcRTC in the same
  country when nothing else gets through (Android and the desktop). On Android
  it also moves a session whose server stopped answering to another server of
  the same country, and says where; a desktop session moves the same way from
  a server the app is already retrying, and does not notice one that has only
  gone quiet (see [Protocols](protocols.md#smart-connect)).

## olcRTC rooms and the board

- olcRTC rooms carry their occupancy on the board, and say out loud when one is
  full or its key is no longer good rather than failing at connect.
- olcRTC on a server that moves its clients between rooms: the next room is read
  off the server list's `##rooms` header, handed to the engine while the session
  is live, and re-read through the tunnel after every handover - on iOS by the
  tunnel extension itself, since the app is asleep by then
  ([ios-room-failover.md](ios-room-failover.md)).
- Latency where it can honestly be measured: through the tunnel for the
  connection you are on; for a server you are not connected to, ICMP first and a
  TCP connect to the port the link names where ICMP is filtered. An olcRTC room
  has no address to probe, so it is timed only while you are in it, by a request
  through the tunnel.
- Sorting by ping or name, filtering by protocol, duplicate server lists
  refreshed rather than added twice, and a location or a whole list removed from
  the board itself.
- A room pinned to the top of its list with the pin on its card. Pinned rooms
  stay on the board when their list is folded away, so a list of a hundred
  rooms folds down to the three in use; the pins and the folds are still there
  the next time the app opens, and a pin stays on its room when the list is
  fetched again.
- A failure that says what failed: a link asking for a transport its provider
  cannot carry, a dead key, an empty room, a tunnel that came up but carried
  nothing.

## Routing, routers and diagnostics

- Routing: everything through the tunnel; Russia, Iran or China and the local
  network direct; or only blocked sites through the tunnel. Rules of your own on
  top (see [Routing](routing.md)).
- Servers for your router: each server of a list as its link or a sing-box
  outbound for Podkop on OpenWrt, or as an Xray outbound for XKeen on Keenetic.
  Encrypted server lists do not offer it: their provider chose that their
  servers are not read out of them.
- Diagnostics log with export, and a failed connection that says why. Server
  addresses are replaced by short tags before a line reaches the log, so the
  file is safe to send to anyone and still says which hop failed.
- TUN and proxy modes wherever the platform has both, SOCKS5 listen address and
  credentials, Android split tunneling with a preset for Russian apps that
  refuse to work through a VPN.

## Phones and TV

- Android: always-on VPN (the connection comes up by itself after a restart),
  and a quick-settings tile. While connected, the settings row says whether
  Android's always-on is on and whether "Block connections without VPN" is
  on with it, which is the kill switch for the case the app cannot cover, its
  own death, and names the apps that setting leaves without network.
- Android TV: a launcher entry, a focus mark you can see from the sofa, and a
  server list sent from your phone. The TV shows a code, and the phone opens a
  one-page form on the same home network.
- On Android and iPhone, the local SOCKS ports between the tunnel and its
  engine ask for a login only the app knows, so another app on the phone cannot
  use them or learn the exit. On Android the port also changes with every
  connect.
- Network migration, reconnect handling, and watchdog recovery.
- iPhone: a kill switch, off by default. With it on, iOS drops traffic whenever
  the VPN is not up instead of sending it directly, and brings the VPN back by
  itself; the local network stays reachable. The price is that a VPN that
  cannot come back leaves the phone without network until it is turned off in
  Settings, and the switch says so.
- iPhone: the tunnel takes IPv6 as well as IPv4. It used to take IPv4 only, and
  on a network that hands out IPv6 a connection to an IPv6 address went out
  directly, at the phone's own address, with the VPN shown as connected.
- Android holds the tunnel for the whole session: a reconnect, another server, a
  lost network or a restarted engine all happen behind the VPN interface, which
  stays up until you disconnect, so there is no moment when the apps on the VPN
  go out around it. While it reconnects those apps wait, and the notification
  says so. What the app cannot hold is its own death; for that Android has
  "Always-on VPN" with "Block connections without VPN".

## The desktop

- On the desktop a core or an olcRTC engine that dies is restarted behind the
  tunnel instead of taking it down: the tunnel stays as it is, and apps wait
  behind it, rather than going out directly, while the process comes back on
  the port it had. A dead sing-box or Xray core is noticed now; it used to leave
  a session that said Connected and carried nothing. The death of the tunnel's
  own process still ends the session.
- On the desktop another server chosen while connected is started behind the
  tunnel as well. The tunnel is built once, for the session, and is not taken
  down, rebuilt or started again for the new server, so there is no moment
  between the two when traffic goes out directly; a server that does not answer
  is retried behind it. On Linux the tunnel's password is not asked for again
  (an olcRTC room still asks, for its engine). Changing the connection mode,
  the routing or the SOCKS port and login still restarts the whole connection.
- Linux desktop: a kill switch for that last case, off by default (Settings,
  Connection). When the tunnel's own process stops, its route goes with it and
  traffic would leave directly; with the switch on, a second route in the
  tunnel's table drops it instead, the local network included, and the app
  says so. It stays blocked until you connect again, which puts a tunnel back
  without opening the block, or turn the switch off; either asks for the
  administrator password. Nothing else removes it: not a connect that fails,
  not Cancel, not quitting the app. It is routes and rules, so it holds for as
  long as they stand, and for what they cover: not across a restart of the
  computer; not for programs running as root or for what the computer forwards
  for containers and virtual machines, neither of which the Linux tunnel has
  ever carried; and not for a program that binds its sockets to a network
  interface. The `ghostlane` command has a kill switch of its own, which its
  daemon also keeps between servers and puts back after a crash
  ([cli.md](cli.md)); the desktop app has no such daemon.
- On the desktop a server that stays down no longer keeps the session on it.
  With smart connect on, a server that is being retried behind the tunnel (a
  core or an olcRTC engine that died and did not come back, or a server chosen
  while connected that never answered) is given two failed attempts and half a
  minute; then the other servers of the same country in the same list are
  tried behind the tunnel, which stays as it is, and the first that carries
  traffic becomes the active one, with a message that says which. The tunnel
  is never taken down for it, and no password dialog opens by itself: on Linux
  a session on any other server is not moved into an olcRTC room, and one that
  is in a room moves only once that room's engine has stopped and is asking
  for the password already. A server that goes quiet while its core keeps
  running is not noticed on the desktop.
- The desktop apps can also lend the tunnel, as an authenticated SOCKS5 listener
  on one private address, to another device on the same local network: a phone,
  a TV, a laptop that must not have a client installed. It is off by default, the
  network it was enabled on is pinned by its gateway, and what it costs is in
  [lan-sharing.md](lan-sharing.md).

## Privacy

- No account, no analytics, nothing collected.
