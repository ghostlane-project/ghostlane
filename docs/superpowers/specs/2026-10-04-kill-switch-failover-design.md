# Fail-closed sessions, a kill switch, and another server of the same country

**Date:** 2026-10-04
**Status:** Approved by the owner 2026-10-04 with the scope widened: every platform, in the next release
(see "Decided"). Being built in the stages listed under "Order of work".
**Checked against:** main `33aa480`. Every file and line below was read at that commit.
**Builds on:** `2026-09-25-smart-connect-design.md` (groups, order, the probe), `2026-09-30-linux-cli-design.md`
(the CLI's kill switch and its connect loop), `docs/macos-tunnel-daemon.md`, `docs/ios-room-failover.md`,
`docs/roadmap-2026-08-14.md` item 2.

## Problem

A provider's customer asked support two things: can my Russian address show when the VPN drops, and how do I
get moved to another server when one falls. Services have begun closing accounts seen from a Russian address,
so one second of direct traffic during a reconnect is the incident, not the outage around it.

Three different things let traffic out, and they have different owners.

- **The app takes its own tunnel down while it recovers.** Ours to fix in code, on every platform.
- **The process that holds the tunnel dies**: killed for memory, crashed, replaced by an update. Only the
  operating system can keep traffic in then. The app can ask it to, or report whether it has been asked.
- **The tunnel never claimed the traffic.** IPv6 on iOS, as far as the code's own comments say.

Separately, a server that stays dead keeps the session down until the user picks another one, although the
list usually has another in the same country.

## What the code does today

**Android** (`OlcboxVpnService.kt`).

- A reconnect in place keeps the interface (`reconnectTransport`, :548-588; "keeping tunnel alive", :556).
- A full restart closes it first (`startFullTunnel`, :598), and it stays closed through the restart and, when
  the transport does not come back, through every retry after it (:616-623). Three things ask for one:
  tun2socks dead (:1198), the user picking another location while connected (`shouldRestartForStartCommand`,
  :1675), and, not in the request this document answers, **an olcRTC connection lost in tun mode** (:1439 and
  :1450 through `shouldRecreateTunnelOnRtcLoss`, :1640). For an olcRTC user that last one is the ordinary
  failure.
- The retry is to the same line, 4 → 8 → 16 → 30 s and then every 30 s, without end (:2152-2155).
- **A reconnect in place of a core line has carried nothing since 1.0.443.** The core's SOCKS port is drawn
  per start (:773, commit `87e6ecd`), tun2socks is configured once, at the full start (:661 → :1044 → :1164),
  and `reconnectTransport` does not restart it. After a move between Wi-Fi and mobile data the core listens on
  a new port, the tunnel check passes because it asks that port directly (:703), and every app's connection is
  refused at the old one. Read in the code, not reproduced on a phone.
- **A server named by a hostname does not resolve under Global routing.** `SingBoxConfig.build` writes no
  `dns` section there, sing-box's `local` resolver reads `/etc/resolv.conf`, and Android has none; the
  rule-based shape passes `LinkProperties.dnsServers` for exactly this reason. `TransportProbe` builds the
  Global shape, so a probe of such a candidate fails whatever the server does. Reproduced with sing-box 1.13.14
  on Linux with `/etc/resolv.conf` hidden (ghostlane#82); not on a phone.
- Android's always-on VPN starts the service (:342) and a settings row opens the system's VPN screen
  (`AndroidAppSettingsSheets.kt`:601-610). Nothing reads whether it, or "Block connections without VPN", is on.
- When the process dies the interface goes with it; `START_REDELIVER_INTENT` (:368) brings the service back.

**iOS** (`PacketTunnelProvider.swift`, `OlcboxIosApp.swift`, `IosVpnManager.kt`).

- Inside the extension the tunnel is already held. Nothing but libbox's own stop ends it
  (`cancelTunnelWithError`, `PacketTunnelProvider.swift`:530-539); a network change, a dead olcRTC engine and a
  dead outbound all leave the tun where it is (:446-458, `RoomKeeper.swift`:287-296).
- What lets traffic out is everything around it. The extension is killed at about 50 MB and the system then
  shows the VPN as inactive (:247-250); the watchdog that restarts it lives in the app
  (`IosVpnManager.kt`:848-890), which iOS suspends. Every change the app makes is a stop, a wait and a start
  (`OlcboxIosApp.swift`:674-679): another location, another routing mode, the Hysteria2 → TCP move, each attempt
  of the reconnect loop.
- `includeAllNetworks()` answers false (`LibboxBridge.swift`:114); `prepare()` sets neither it nor on-demand
  rules (`OlcboxIosApp.swift`:340-368), and runs only for a missing or disabled profile (:375), so a flag added
  there alone would never reach an existing install.
- The tunnel claims IPv4 only (`LibboxBridge.swift`:85-96). The code says where IPv6 then goes: "a fake IPv6
  would leave through the physical interface" (`SingBoxConfig.kt`:397-400). On the hev paths (olcRTC, XHTTP)
  the resolver is a public one reached through the tunnel, and it answers AAAA as it finds them. Not measured.
- The extension starts from three files in the App Group and from nothing passed to it (:156-170), so a start
  by the system would bring up the last location the app started.

**Desktop** (`DesktopVpnManager.kt`, `vpn/desktop/`).

- The olcRTC engine's death is noticed and answered by a teardown that takes the tun down first
  (`handleUnexpectedProcessExit`, :1501-1512, from :1420; `stopDesktopMode`, :1145-1193), so traffic goes direct
  by the app's own hand. A sing-box or Xray core's death is not noticed at all: it is asked whether it runs
  once, at connect (:556), and after that the tun stays, nothing passes and the status says `Connected`.
  Nothing reconnects in either case. Another location while connected is a teardown and a start (:165-187).
- The tun's upstream is fixed for as long as the tun lives: one local port and one login (`buildDesktopTun`,
  `SingBoxConfig.kt`:305-316; hev's yaml on Linux). olcRTC and a core differ in the port, and in the login when
  the user has set one.
- Linux: the tun is hev-socks5-tunnel, started through `pkexec` or `sudo -n` (`LinuxTunController.kt`:25,
  :353-356), with two policy rules of its own: uid 0 to `main` at pref 10, everything else to table 51820,
  whose default route is the tun, at pref 20 (:296-298). Of what the app starts, only hev and the olcRTC engine
  run as root (`DesktopVpnManager.kt`:477, :1750-1751). When hev dies the tun and its route go, the second rule
  finds an empty table and IPv4 falls through to `main`. `pkexec` asks for the password at every launch, and
  there is no privileged process that lives as long as the session.
- Windows: the app relaunches itself elevated (`WindowsTunController.kt`:96) and runs a second sing-box with a
  tun, `auto_route` and no `strict_route` (`DesktopVpnManager.kt`:684-696); the cores' binaries leave the tun by
  process path.
- macOS: the root daemon runs that sing-box (`MacOsTunController.kt`:66-76) and keeps the server out of the tun
  by address, not by process. A killed app leaves the tun up and traffic stops, which was measured
  (`docs/macos-tunnel-daemon.md`:139-146). The daemon does not restart its child.
- The Linux CLI has both halves already: `--kill-switch` (rules 9098-9100 and an `unreachable` table 2023,
  kept between lines and through a backoff) and a loop over every line of the chosen country. It can because
  the daemon lives as long as the selection, holds `CAP_NET_ADMIN`, and cleans stale rules at its start; the
  desktop app has none of the three.

## Decisions

- **Two layers, two names.** *The session holds the tunnel* is not a setting: from the first verified
  connection until the user disconnects, no failure takes the tunnel down and recovery happens behind it. That
  closes every leak the app makes itself. *Kill switch* is the operating system keeping traffic in when the
  tunnel's process is gone; where the app can ask for it, it is a switch, and where only the user can, the app
  reports its state. The README and the interface use the words this way, so "kill switch" never promises more
  than the platform delivers.

### Android

- **A1. One interface per session, and only the user closes it.** From the first `Connected` of a tun-mode
  session until Disconnect, a switch to proxy mode, `onRevoke` or the service's destruction, `vpnInterface` stays
  open. Every recovery runs behind it: a dead transport, a dead tun2socks, a lost RTC connection, the user picking
  another location, a failed restart and the whole of its backoff. While nothing is up behind the interface the
  status is `Reconnecting`, never `Error`: an `Error` over a held interface would say "disconnected" on a phone
  that has no network.
  This is a kill switch for as long as the process lives, and the reason is Android's, not ours: the Builder adds
  one IPv4 address and one IPv4 route and calls neither `allowFamily` nor `allowBypass`, so IPv6 is blocked and
  an app routed into the tun has no other way out (`VpnService.Builder`: "if no address, route or DNS server of
  a specific family is added to this VPN, then all outgoing traffic of that family is blocked"). tun2socks
  refuses what it cannot pass on, and a tun nobody reads drops.
- **A2. A restart does not re-establish; a handover happens only when the interface itself must change.** The
  request was `establish()` over an open descriptor at every full restart. It is not needed there: tun2socks is
  given a duplicate of the descriptor (`startTun2socks`, :1043) and the JNI closes its own copy
  (`olcbox_tun2socks_jni.c`:19), so it restarts on the interface that already exists. The interface has to change
  only when its Builder parameters do, which is split tunnelling edited while connected. That one case uses the
  documented handover: `establish()` with the old descriptor open, tun2socks started on the new one, the old one
  closed. `VpnService.Builder.establish`: "the old interface will be deactivated when the new one is created
  successfully … If the new interface cannot be created, the existing interface and its file descriptor remain
  untouched."
- **A3. tun2socks follows the core.** The core's port becomes the session's: drawn once, reused by every core
  start of the session, drawn again only when the bind fails. After every transport start the bridge is compared
  with what tun2socks was started with (address, port, login) and restarted on the held descriptor when they
  differ or when it is dead. olcRTC listens on the user's SOCKS port and a core on the session's, so a move
  between them restarts it; a core restarted on the session's port does not. This is the fix for the 1.0.443
  regression above and ships first.
- **A4. The hold begins at the first verified connection.** A first connect that never reached `Connected` ends
  as it does today: `Error`, interface closed, traffic as it was before the tap. Holding there would leave a
  phone without network behind a server that never worked. One exception: a start by Android itself (always-on,
  `SERVICE_INTERFACE`, :342) is a session from its first second and retries like one, because the system expects
  the VPN to exist and, under lockdown, blocks everything anyway.
- **A5. Android's kill switch is reported, not imitated.** When the process dies the descriptor closes with it,
  and only "Always-on VPN" with "Block connections without VPN" keeps traffic in. No API lets an app turn that
  on. The service publishes `isAlwaysOn()` and `isLockdownEnabled()` (API 29+; only a running service can ask),
  and the settings row that opens Android's VPN screen says what it found: on and blocking, on and not blocking,
  off. Below API 29, and while the service is not running, the row reads as it does now; a remembered answer
  would be a claim about a setting the user may have changed since. With lockdown on, the row warns about the
  apps split tunnelling leaves outside the VPN: the chosen apps in "bypass selected", every other app in "proxy
  selected". Android cuts those off, and the app cannot exempt them.
- **A6. tun2socks does not outlive what it points at.** Added after the review of stages A to C. Where a
  reconnect ends with nothing listening behind the interface (the transport did not start, no other line
  carried, the location is gone), tun2socks is stopped too and started again with the transport. Left running
  through a hold of minutes it keeps offering the session's SOCKS login to whatever binds that loopback port,
  and any app on the phone can bind one. A tun nobody reads drops, which is the hold. A tun2socks that will not
  stop is waited for: it is one instance per process and reads a duplicate of the descriptor, so nothing can be
  started beside it and a handover does not dislodge it.
- **A7. One location store.** Added after the same review. Stage C makes the service a writer of the stored
  bundle (the active location, the remembered line). The app and the service each had a repository, and so a
  lock, of their own, and the file was written in place: a read inside the other's write does not parse, is
  taken for an empty store, and is saved as one. The bundle is now replaced in one step (written beside itself,
  renamed over), the two share one repository, and the service reads the stored bundle past the repository's
  lock, which the app holds for as long as a list refresh downloads. The move itself is one change made by the
  repository, and only while the location that failed is still the active one.

### Desktop

- **D1. The core is watched, and a dead core or engine is restarted behind the tun.** Both deaths become the
  same thing: the tun stays, the status is `Reconnecting`, and the dead process is started again on the port it
  had, with Android's backoff. The port is not a preference: the tun's upstream cannot change while it lives.
  Until the process is back the tun has nowhere to send anything, which is the state a killed app leaves on
  macOS, where it was measured. On Windows and macOS the restart needs nothing the app does not already have.
  On Linux a core restarts as the user, and the engine needs root: `pkexec` asks again, and until the password
  is given the tun stays and nothing passes. Closed, with a dialog on the screen; the plan decides whether one
  privileged parent for the session is worth building to spare the dialog.
- **D2. The tun is built for the session, not for the line.** Today it is built for one line: its upstream port
  and login on every system, the server's addresses on macOS. That is why another line is a teardown and a start,
  and why a probe cannot leave a held tun. Three changes make it the session's:
  the tun always points at one local port without a login, on which a core listens directly and an olcRTC line
  is fronted by the sing-box chain that proxy mode with rules already uses (`buildSocksChain`); macOS lets the
  cores' binaries out by process path with the direct outbound bound to the physical interface, as Windows
  does, instead of excluding one server's addresses; and Linux gives a core started as the user a way out of
  hev's rule, which it does not have today (see Risks). With that, a restart, another location and the move of
  F3 all happen behind the tun, and a probe leaves it the way the session's core does.
- **D3. An operating-system kill switch where it can be owned, and plain words where it cannot.** When the
  tun's own process dies, something that outlives it has to hold traffic. Linux: an opt-in switch adds a second
  default route to the tun's table, on a dummy device and with the last metric, in the script hev already runs
  as root, and the app removes stale rules at its start, as the CLI's daemon does. Not the CLI's `unreachable`
  route, which was the first plan: in IPv4 a lookup that ends in such a route while its socket is bound to a
  device is sent as if the destination were on that link, with no gateway, so it would also cut off the cores,
  which get out of the tun by being bound to the physical interface (D2). A route on a device is passed over by
  a socket bound to another device, and needs no interface named in any rule (see Risks for the check). hev's own script must leave that route in place when hev stops without being asked to;
  after a crash the block stays until Disconnect, which is `pkexec` once more. macOS: nothing to add; an app killed or a core dead already
  leaves the tun in place with nowhere to go, and D1 and D2 remove the two cases where the app itself took it
  down. Windows: `strict_route` goes on, which closes the DNS leak while the tun runs; it is not a kill switch,
  because sing-tun opens its WFP session as dynamic (`tun_windows.go`:202) and the filters go with the process.
  A block that survives the tun's death on Windows needs a service that owns the filters; the app has none, and
  the README says in those words what Windows does and does not hold.

### iOS

Nothing below can be compiled in CI or run from here: Swift is not built there, and a network extension does
not run in the simulator. Each item is the owner's to pass on a phone, from a TestFlight build, before the
release that carries it is submitted.

- **I1. IPv6 first: the tunnel claims it.** A leak with the VPN connected outranks a leak during a reconnect.
  The tunnel's settings take an IPv6 address and the default IPv6 route, as the desktop tun has done since the
  same leak was found there (`DESKTOP_TUN_ADDRESS6`, `docs/macos-tunnel-daemon.md`:170-186). What arrives is
  the engine's to deal with, and the configs do not change: sing-box's gVisor stack routes both families
  whatever addresses the tun has (`stack_gvisor.go`:164-166), so it carries IPv6 to the exit like anything
  else, and hev, which is given no IPv6 address, drops it, so a client falls back to IPv4 through the tunnel.
  A reject rule in the sing-box config, the desktop's way, would answer faster, and would move seven reference
  configs and the tests that pin "nothing rejects" on the iOS shapes; it can follow once a phone has shown the
  claim itself is right. The check is one page (`test-ipv6.com`) on a mobile network that hands out IPv6, once
  on an olcRTC line and once on a Reality line, before and after.
- **I2. The kill switch is a switch, off by default.** It sets `includeAllNetworks = true` with
  `excludeLocalNetworks = true` on the protocol, leaves `excludeAPNs` and `excludeCellularServices` at the
  system's default, and turns on-demand on with one `NEOnDemandRuleConnect`. `LibboxPlatform.includeAllNetworks()`
  answers what the protocol says: sing-tun allows only the gVisor stack in that mode (`stack.go`:45-61), which is
  the stack the config already asks for. Disconnect switches on-demand off and saves before it stops, or iOS
  brings the tunnel straight back. The profile of an existing install is loaded and saved again, because
  `prepare()` never touches one that exists.
  Off by default because of what it costs: with the extension gone the phone has no network at all until it is
  back, other VPN apps report an app update over a connected VPN as a way to get there, and the way out is the
  VPN switch in Settings. The switch says so where it is turned on.
- **I3. A start by the system uses the last start's files.** On-demand starts the extension with no app
  beside it, and the extension already starts from the App Group alone. Two things follow. The three files are
  written atomically; today a start can read half a file (`OlcboxIosApp.swift`:621-622). And an olcRTC location
  whose stored rooms have all outlived their day fails its start, which under the kill switch means no network
  until the app is opened; that is accepted and written next to the switch (`docs/ios-room-failover.md` already
  lists it as open).
- **I4. The app's stop-and-start stays for now.** Replacing it with a reload behind the tun needs the extension
  to restart sing-box in place (`serviceReload` is empty today, `PacketTunnelProvider.swift`:541-543), to restart
  hev on the descriptor it holds, and to hand the tun from one to the other when the engine changes. That is a
  design of its own. Whether `includeAllNetworks` covers the gap between the app's stop and its start is a
  question for the phone, not for this document.
- **I5. Another server of the same country, on iOS, is not in this release.** It was planned for the app's
  reconnect loop, while the app is awake. Reading that loop again decided against it: it runs only when the
  tunnel is down, and for a Reality or Hysteria2 line a dead server leaves the tunnel up and carrying nothing,
  which nothing on iOS notices; and the app is suspended minutes after it leaves the screen, which is when a
  server usually dies. A move that works only for a room that fails to start, and only with the app in front,
  is a switch that mostly does nothing. The real thing lives in the extension: the candidates as ready sets of
  its three files in the App Group, a periodic check that traffic crosses the tunnel, and the reload of I4.
  Built after I4.

### Another server of the same country

- **F1. One switch.** Smart connect (`SubscriptionSettings.smartConnect`, default on) also governs the move
  during a session. Off is exactly today's endless retry of the one line.
- **F2. When.** After two failed reconnect attempts and at least 30 s of outage, both counted only while a
  network is present: a phone in a lift is not a dead server. One pass over the candidates; if none passes, the
  active line keeps its 30 s retry and the pass repeats every 5 minutes. An olcRTC line takes up to 35 s per
  attempt, so its move comes later than a core's; the rule is the same.
- **F3. Where to.** Never another country, never another server list. In order: the other transports of the
  same exit (smart connect's plan without the line that just failed), then the other exits of the same country in
  the same list, each exit's transports in smart connect's order and the exits in the list's order, then olcRTC
  of that country. In whitelist mode olcRTC goes first, as at connect. A session that was on olcRTC goes to the
  country's other rooms first, then to its cores. The country is `TransportGroup.countryOf`, so a list whose
  names do not lead with a country code gets the same-exit transports and nothing wider: the safe direction, as
  at connect.
- **F4. How a candidate is judged.** Unchanged from smart connect: a core candidate is probed by its own core on
  a loopback port (`TransportProbe`, the 204 and the 64 KB), an olcRTC candidate is connected and the tunnel
  check is its test. On Android the probe leaves by the physical network because the app's UID is outside the
  tun; on the desktop it leaves the way the session's core does, once D2 is in.
- **F5. The winner becomes the active location.** It is set active in the repository, remembered under its own
  group in `lastKnownGoodTransport`, connected behind the held tunnel, and announced: a line in the notification
  and a notice on the home screen naming the line the session moved to. Nothing moves back by itself; the next
  Connect starts from what is active, as after any change of location.
- **F6. Price.** Every line of a list is one the user or their provider put there, with its own price in its
  name, so a move inside the list is a move to something already accepted. The notice names it.

## Architecture

Pure units first, so the rules are pinned by tests that need no phone; the services become their executors.

1. `vpn/TunnelSessionPolicy.kt` (`jvmAndroidMain`, pure). `TunSpec` (split-tunnel mode and app lists, MTU,
   address, resolver): what an interface was established with. `Bridge` (address, port, login): what tun2socks
   was started with. `tunAction(held, wanted)` → `Keep` | `Establish` | `Handover`;
   `bridgeNeedsRestart(running, alive, wanted)`; `closesInterface(event, inSession)`, true only for Disconnect,
   revoke, the switch to proxy mode and a failed first connect.
2. `OlcboxVpnService`: `startFullTunnel` keeps the first start and proxy mode. Inside a session its place is
   taken by `restartBehindTunnel`: stop the transport, start it (the same line or another), bring the bridge in
   line with it, check the tunnel. The interface is touched only through `tunAction`. `sessionCorePort` is drawn
   once per session. A start by the system retries as a session. `failUnverifiedTunnel` logs
   `activeCoreDiagnostics()`, as the stall watchdog and a failed core start already do.
3. `net/SessionFailover.kt` (`commonMain`, pure). `candidates(active, all, lastKnownGood, whitelist)` in the
   order of F3, built on `SmartConnect.plan` and a new `TransportGroup.sameCountryExits(entry, all)`;
   `Outage(startedAt, failedAttempts, lastPassAt)` and `due(outage, now, networkPresent, enabled)` for F2.
4. `VpnManager.sessionNotices: SharedFlow<SessionNotice>`: "moved to <line>". `HomeScreenModel` reloads the
   active location on it and shows the notice; the Android notification carries the same line.
5. Android glue: the service counts the outage, runs the pass (the probe's starter is the one
   `AndroidVpnManager.probeTransport` has, moved where both can reach it, with a `failover-…` label), makes the
   winner active through the repository and calls `restartBehindTunnel`. The pass and the tunnel check share
   the process-wide SOCKS authenticator, so they run one after the other, never side by side.
6. `OlcboxVpnState.systemVpnMode: StateFlow<SystemVpnMode?>` (`alwaysOn`, `lockdown`; null below API 29 and
   while the service is not running), set in `onStartCommand` and on every status change; the settings row and
   its warning read it.
7. `SingBoxConfig.build(…, serverResolver: DirectDns? = null)`: when the server is not an address literal and
   a resolver is given, the config carries one `dns-direct` server and `route.default_domain_resolver`, as the
   rule-based shape always has. Without one the output is byte for byte what it is today, so the reference dumps
   do not move. `TransportProbe` takes the same resolver. Checked in the simulation that reproduced the bug: the
   same link connects once the resolver is given.
8. `DesktopVpnManager`: a watcher for the sing-box and Xray cores beside the engine's, and
   `handleUnexpectedProcessExit` split by which process died. The tun's death keeps today's teardown; the
   core's or the engine's becomes a restart of that process on the session's port under the manager's mutex,
   with the tun, its verify port and, on Linux, hev left as they are. `startDesktopMode` splits into the
   session's part (the tun, once) and the line's part (the core, or the engine and its front), and a change of
   line runs only the second.

## Order of work

One subject per pull request. The stages are the order of building; all of them are the next release.

| Stage | Pull request | Decisions |
|---|---|---|
| A | `fix(android)`: tun2socks follows the core across a reconnect in place | A3 |
| A | `fix(android)`: a server named by hostname resolves under Global routing, in the session and in the probe | architecture 7 |
| A | `fix(android)`: a failed tunnel check logs what the core said | architecture 2 |
| A | `fix(links)`: `type=http`, and VLESS `security=none` and `allowInsecure`, in the app and the CLI | ghostlane#82 |
| B | `feat(android)`: the session holds the tunnel | A1, A2, A4 |
| B | `feat(android)`: Android's kill switch, as the system reports it | A5 |
| C | `fix(android)`: one location store for the process, saved in one step | A7 |
| C | `feat(connect)`: another server of the same country during a session (common code and Android) | F1-F6 |
| D | `feat(desktop)`: a dead core or engine is restarted behind the tun | D1 |
| D | `feat(desktop)`: the tun is the session's; another line and the move happen behind it | D2, F4 |
| D | `feat(desktop)`: Linux kill switch; `strict_route` on Windows | D3 |
| E | `fix(ios)`: the tunnel claims IPv6 and refuses it; the extension's files are written atomically | I1, I3 |
| E | `feat(ios)`: kill switch | I2, I3 |

Each pull request updates `README.MD` (Features), `docs/release-notes/pending.md` and the document whose truth
it changes (`docs/roadmap-2026-08-14.md` item 2, `docs/macos-tunnel-daemon.md`).

## Decided

The owner's answer of 2026-10-04: everything, on every platform, in the next release, together with the fixes
that had piled up. What that settles:

1. **Scope.** One release carries all five stages. Stages D and E contain code that cannot be run from here;
   the release waits for the owner's pass on a Mac, a Windows machine, a Linux desktop and an iPhone, and a stage
   that does not pass is taken out of the release rather than shipped unverified.
2. **The hold begins at the first verified connection** (A4), as proposed.
3. **The move may leave the exit the user picked for another exit of the same country** (F3), under the smart
   connect switch, as proposed.
4. **The desktop moves too**, which is why D2 rebuilds its tun around the session.

## Reviewed

Stages A to C were read by an independent reviewer once they were built (2026-10-04), against this document and
without a device. Twelve findings, all confirmed in the code and fixed on the branch each belongs to. What they
changed in the design is A6 and A7 above; the rest brought the code back to what was already decided here:

- the hold began wherever an interface existed, not at the first verified connection (A4);
- an Error could still be published over a held interface by helpers a first connect shares with a restart
  (A1), and a start by Android that failed after its transport was up did not retry (A4);
- an edit of the app lists was applied only by the restart that carried it, and lost when that restart ended
  early (A2);
- time without a network counted as outage, a superseded start counted as a failure, and a look at the other
  lines that was cut short still put the next one off by five minutes (F2);
- the line moved to was not named in the notification (F5);
- a stop arriving while Android created an interface could leave that interface up; a tun2socks told to stop
  was taken for a live one; its stop could be asked for twice.

## Testing

- **Unit (jvmTest, runs in PR Checks).** `TunnelSessionPolicy`: no failure event closes the interface inside a
  session, a failed first connect does, a handover is asked for only when the `TunSpec` differs, the bridge
  restarts exactly when its target differs or it is dead. `SessionFailover`: the order of F3 on real list names
  (same exit, other exits of the country, olcRTC last; whitelist; an olcRTC session; a list without country
  codes; nothing from another country or another list), and `due` (attempts, time, no network, the repeat).
  `SingBoxConfig`: the hostname shape is written for a hostname and only then, and `SingBoxConfigDumpTest`
  hands it to `sing-box check`.
- **Loopback (no device).** The interop lab from ghostlane#82, kept as a script: a sing-box server and the
  config the app builds, in a network namespace, including the case with `/etc/resolv.conf` hidden. For the
  desktop: hev and its two rules in a namespace, the core behind it killed, `curl` gets nothing while the rules
  stand, and traffic returns when the core does (the CLI's `netns_test.go` already works this way).
- **On a device, by the owner, per stage.** A: Wi-Fi to mobile data on a Reality line, pages keep loading; a
  list whose servers are named by hostname connects. B: with an address-check page open, kill the transport,
  switch location, edit split tunnelling; the home address never appears; the settings row under always-on with
  and without "Block connections without VPN". C: make the active server unreachable; the session is on another
  line of the country within about a minute and says which. D, on each desktop system: kill the core, the page
  stops and comes back; switch location with the address-check page open; on Linux, the kill switch with hev
  killed. E: `test-ipv6.com` before and after; the kill switch with the extension stopped from Xcode; on-demand
  after Disconnect.

## Risks, and what cannot be verified from here

- **No device.** None of the Android behaviour above was run; it is read from the code and from AOSP's
  documentation. The handover of A2 is documented by Android and rare in practice, so vendors' builds are where
  it could differ. Gradle is not run on this box either: the unit tests run in PR Checks.
- **A held tunnel with nowhere to go is a phone without network.** Smart connect off, one dead line: by design,
  and the notification has to say it in words and keep its Stop action, because the user may need the network
  to fix the cause (an expired list, an empty balance).
- **The Linux desktop is the least certain part of stage D.** The engine's restart asks for the password again
  (D1). And the tun's rules let only root out while the sing-box and Xray cores run as the user: reproduced in
  two network namespaces with the app's own rules, a core run as the user dials its server into the tun and
  times out, so a Reality or Hysteria2 line cannot connect in the Linux tunnel unless the app itself runs as
  root. In the same namespaces `route.auto_detect_interface` (and `bind_interface`) lets the core out:
  binding a socket to a device needs no privilege since Linux 5.7, and a lookup with an outgoing interface does
  not match the tun's table. That is the way out stage D takes.
  Checked again while building it, with the core stripped of `CAP_NET_RAW` and `CAP_NET_ADMIN` (in the first
  run it was root inside its namespace, which proves the routing and not the permission): plain fails, bound
  connects, and a server named by hostname connects once the config also carries a resolver of its own
  (`dns-direct`, bound like everything else), because the system's resolver is pointed at hev's fake addresses
  for as long as the tun is up. sing-box does not bind sockets that go to loopback, so a front before Xray or
  the engine is not affected. What stays broken there: an XHTTP line named by hostname (Xray asks the system's
  resolver). A server that answers only over IPv6 is not among them: the IPv6 half of hev's rule is a blackhole
  route, and an IPv6 lookup bound to a device passes over it and reaches the main table (`ip -6 route get`,
  not traffic).
  The kill switch of D3 was checked the same way, with `ip route get` in a namespace: with
  `default dev <dummy> metric 4294967295` in the tun's table, an app's lookup goes to the tun while it is up
  and to the dummy once it is gone; a socket bound to either of two physical interfaces goes to the main table
  in both states, also after the default route has moved from one interface to the other; root's goes to the
  main table. With an `unreachable` route in its place the bound socket's IPv4 lookup comes back on the physical
  interface without its gateway (the kernel takes a destination for on-link when a lookup with an outgoing
  interface fails), which reaches nothing, unless a rule names the interface and sends it to the main table
  first.
- **macOS, a killed tun.** The daemon's own comment says a sing-box killed outright leaves the default route on
  a utun that no longer exists (`TunnelChild.swift`:135-138). D1 does not change that; D2 has to.
- **The move costs a little traffic and battery.** Each probe starts a core and pulls 64 KB; the 5-minute
  repeat bounds it.
- **iOS, all of it**: what `includeAllNetworks` does to the extension's interface-pinned sockets and to the
  loopback hop between hev and its engine, whether the gap in the app's stop-and-start is covered, how
  on-demand treats a 35-second olcRTC start, what an app update does to a connected phone.
- **Other lists' names.** The country is read from the line's name. A list that does not lead with a country
  code moves between transports of one exit only.

## Out of scope

A block that survives the tun's death on Windows and a pf anchor on macOS. A reload in place on iOS, and with
it the move to another server there. Another country, another list. Proxy mode on any platform: nothing is
routed there, so there is nothing to hold. sing-box JSON and Clash server lists (ghostlane#82).
