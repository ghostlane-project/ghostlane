# iOS: following a server that moves the client between olcRTC rooms

Status: written 2026-09-20, not yet built - needs a Cores.xcframework from an
olcRTC engine that carries `AddFailoverRoom` / `SetSessionListener`
(olcrtc branch `proofkit-failover-rooms`), and a device to run it on.

## The problem

Some olcRTC servers do not keep one room. A conference room on the providers
they ride lives about a day, so the server mints the next room while the client
is still in the current one, advertises it in the subscription as a `##rooms:`
header under the location's line, and retires the old room once the client has
been told. Android and desktop follow that already: the engine walks the room
list under its supervisor, the app parses `##rooms`, hands the list to the
engine and re-reads the subscription after every hop.

On these servers the subscription is usually reachable **only through the
tunnel** - the user sits behind a whitelist where the conference provider is
the one thing that answers. A client whose every stored room has been retired
cannot ask for a live one. So the client must always know the next room before
the current one goes, and its own reads of the list must go through the tunnel.

What is different on iOS is which process is awake. The app parses the
subscription and owns the stored list, but iOS suspends it minutes after it
leaves the foreground, while the tunnel extension carries traffic for hours.
The pieces that on Android live in the VpnService's process therefore live in
the extension here.

## What lands where

**Kotlin, shared** (`LocationConfig`, `LocationsDatasource`): the `##rooms`
header is parsed into `LocationConfig.failoverRoomIds`, and `LocationEntry`
persists it as `failover_rooms`. `failoverRooms()` is the ordered list, primary
first. `HomeScreenModel` refreshes the subscriptions when the tunnel comes up
and, when auto-update is on and the active location is an olcRTC one with
standby rooms, once more - bounded to three seconds - before a deliberate stop.
Everyone else stops at once.

**Kotlin, iOS** (`IosVpnManager`, `IosBridge`): the start request carries
`failoverRooms` and the location's `subscriptionUrl`; while connected, a change
to the stored list is sent to the extension as an `olcrtc-rooms` message
(`updateOlcRtcRooms`). Only the running location, matched by key - a list that
changed because the user picked another location is a restart.

**Swift, app** (`SwiftPacketTunnelBridge`): the two new fields go into
`olcrtc.json`; `PacketTunnelController.send` delivers the message to a running
`NETunnelProviderSession`.

**Swift, extension**:

- `OlcrtcEngine` hands the engine the primary and the extras (`applyRooms`),
  installs `RoomKeeper` as the engine's session listener, and can `relaunch`
  over a new list.
- `RoomList` reads a subscription body - plain or base64 - for the line whose
  key is the running location's, and the rooms beside it. Foundation only;
  `scripts/test-ios-room-list.sh` runs its test on any swift.org toolchain.
- `RoomKeeper` is the part that replaces the sleeping app. After every session
  the engine opens beyond the first - a handover or a reconnect - it fetches the
  subscription (through the tun: an unpinned request inside the extension goes
  where the tunnel goes), parses it, and hands the engine the current list,
  which the engine reads at its next hop. Every five minutes as well, for
  whatever an event did not cover. One fetch per 15 s at most, so a client
  cycling through dead rooms cannot storm the server. And a watchdog: an engine
  whose generation ended - every room tried once, none held - is started again
  over the rooms known now, with a 2-20 s backoff, indefinitely, since where the
  list lives behind the tunnel a restart is the only way back to one.
- `RoomMemory` keeps the last list this process learned in the App Group
  (`olcrtc-rooms.json`, the key as a digest). A start merges it into the app's
  list: the app may have slept through several handovers and its file may name
  only rooms since retired.

## What the extension deliberately does not do

- Discover rooms. It uses what the subscription says; inventing a room id is
  not possible, the provider assigns them.
- Refresh on the first session of a start. The app is awake then and refreshes
  on Connected; the extension refreshes after its own restarts instead.
- Pin the subscription request to the physical interface. On these servers the
  list is reachable only through the tunnel; a pinned request would reach
  nothing.

## What remains open

- No recovery from a list in which every room is dead: nothing here can fetch
  a subscription without a tunnel. Everything above shortens the window in
  which that state can be reached.
- Room ids never reach a log the user can export: `olcrtc.log` and the app's
  log name rooms by the first eight hex digits of their SHA-256 (`RoomDigest`),
  `network-diagnostics.log` keeps counts only. A room id is the address of a
  meeting; the digest still shows a standby appearing and a list that did not
  change.
- Bypass Russia: a subscription host that matches the direct rules is fetched
  directly by the engine, off the tunnel. Fine where the host is reachable
  directly; not the whitelist case this was built for.
