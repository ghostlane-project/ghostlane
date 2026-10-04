# The macOS tunnel daemon

macOS is the last platform to get a system-wide tunnel. Linux and Windows have
had one for as long as the desktop app has existed; macOS had a SOCKS proxy,
which only applications that read the system proxy settings ever used — so a
browser was covered and a game, a terminal or anything dialling a hardcoded IP
was not.

It gets one now the way every other cross-platform VPN does on this operating
system: a small daemon running as root, owning a `utun`, with the app talking to
it over a socket. Not NetworkExtension — see the last section for why.

Design: `docs/superpowers/specs/2026-08-04-macos-tun-daemon-design.md`.

## What is actually running

```
ProofKit.app (as you)                    ProofKitTunnelDaemon (as root)
  sing-box / Xray / olcrtc                 launchd, KeepAlive
  SOCKS on 127.0.0.1:<core port>  ◄────┐   /var/run/org.olcbox.app.tunneld.sock
                                       │     ↑ peer checked by code signature
                                       └── sing-box #2: tun → socks(core)
                                             auto_route owns the routes
                                             the server's IPs are excluded
                                             the engines' binaries leave direct
```

The cores are unchanged — same binaries, same ports, same logs as in proxy mode.
The daemon adds one more sing-box in front of them whose only job is to own the
tun. Everything the tunnel carries still leaves the machine through the same core
that carried it before.

## The tun is the session's, not one server's

The tun is built once, when a session starts, and points at one local port, with
one login when the session started in an olcRTC room. Neither can change while
it lives. So another server chosen while connected is not a new tun: the engine
behind the tun is replaced, and the new one is started on that same port and
asks for that same login, whatever its own would have been. The tun is not
stopped, not handed a new config and not started again, and the daemon learns
nothing of the change.

Two things in the tun's config make that possible, and both are the first
server's no longer:

- **The engines leave by program.** The config names every binary a server can
  be reached with — sing-box, Xray, the olcRTC engine — in a `process_path` rule
  that sends what they dial to a `direct` outbound bound to the physical
  interface. It is the rule the Windows tun has always had, and it comes with
  `auto_detect_interface`, as it does there. The address exclusion stays as it
  was: it spares the first server's packets a pass through the daemon's
  sing-box, and is not what a later server relies on.
- **A later core asks a resolver of its own.** With the tun up, the system's
  resolver is reached through the tun, whose way out is the core that is asking
  because it is not up yet. So a sing-box started behind the tun is given the
  resolvers the Mac had before the tun came up, and its own query leaves by the
  rule on its binary. Xray has no such part: an XHTTP server named by hostname
  may not come up as a later server, and the log says so.

The rule needs the name of the physical interface (`route -n get default`). When
that cannot be found, and no routing rules need it either, the tun starts as it
always did, with the first server's address excluded and nothing else, the log
says so in one line, and another server in that session is the full restart it
used to be.

What stays the first server's for the whole session is how names are looked up
(`upstreamUdpIsLossy`). A session that started on Reality or Hysteria2 and moves
into an olcRTC room keeps sending lookups as datagrams, through a carrier that
loses datagrams, so a name can take a retry until the next connect.

A change of the connection mode, of the routing settings or of the SOCKS port
and login is still a full restart: those are in the tun's config, or decide
where the rules live.

None of this section has been exercised on a Mac. It is read in the code, and
the configs it produces are checked by `sing-box check` in CI. What a Mac has to
show: with `while :; do curl -4 -s -m 3 https://api.ipify.org; echo; sleep 1;
done` running in a plain shell, choose another server. The loop stops answering,
then answers with the new exit, and the Mac's own address never appears in it;
`netstat -rn | grep -c 172.19.0.1` does not drop to 0 in between.

## Installing it

Settings → Connection → **System-wide tunnel** → tap.

macOS then asks you to approve it in **System Settings › General › Login Items**.
That is one authorisation, once — after it, connecting and disconnecting never
prompt again. Until it is approved the app keeps using the SOCKS proxy exactly as
it always has; nothing about connecting changes and nothing fails.

The row says which of the states you are in:

| Row reads | Meaning |
|---|---|
| `Not installed — tap to install` | Nothing registered yet. |
| `Approve in System Settings › General › Login Items` | Registered; macOS is waiting for you. |
| `Installed` | Approved and running. Connecting now builds a real tun. |
| `Missing from this build — reinstall ProofKit` | The app was built without the daemon (no signing identity in CI). |
| *(row absent)* | Not macOS, or macOS older than 13. |

Deleting ProofKit.app removes the daemon with it: the launchd plist points into
the bundle (`BundleProgram`), so there is nothing left in `/Library` to clean up
afterwards.

Every macOS build is notarised now, test builds included. Whether `SMAppService`
refuses to register from an un-notarised bundle is not documented either way, and
asking someone to authorise a root background item inside an app macOS has just
called unverified is a bad way to earn that authorisation.

## Where things live

| | |
|---|---|
| daemon binary | `/Applications/ProofKit.app/Contents/MacOS/ProofKitTunnelDaemon` |
| launchd plist | `/Applications/ProofKit.app/Contents/Library/LaunchDaemons/org.olcbox.app.desktopApp.tunneld.plist` |
| the core it runs | `/Applications/ProofKit.app/Contents/Resources/sing-box`, copied to `/Library/Application Support/org.olcbox.app/bin/sing-box` |
| generated config | `/Library/Application Support/org.olcbox.app/tun.json` (root, 0600) |
| control socket | `/var/run/org.olcbox.app.tunneld.sock` |

## Reading it

```bash
sudo launchctl print system/org.olcbox.app.desktopApp.tunneld | head -20
log show --last 10m --info --predicate 'process == "ProofKitTunnelDaemon"'

ifconfig | grep -A3 utun            # a utun holding 172.19.0.1
netstat -rn | head -20              # default via that utun, the server IP via the LAN gateway
netstat -rn -f inet6 | head -20     # and the IPv6 half, via fdfe:dcba:9876::1
curl -4 -s https://api.ipify.org    # from a plain shell that knows nothing about a proxy
curl -6 -s https://ifconfig.co      # expected: nothing at all — IPv6 is refused, see below
```

**The socket does not answer `nc`, and that is the design working.** `nc -U … <<<
'{"verb":"status"}'` returns `{"error":"unauthorized"}` even under `sudo`,
because the daemon authenticates the *code signature* of its peer, not its uid.
Being root buys nothing here; being the signed app is the only thing that does.
An earlier version of this document suggested that command, which was wrong.
The status a person can actually read is the one in the app's own log.

That last command is the whole point of this work. `curl` never reads the system
proxy settings the old mode set, so an exit IP there is the system-wide tunnel and
could not have been produced by the mode it replaces.

## What has been verified on a real Mac

**macOS 26.5.1**, Apple Silicon, a direct hysteria2 link to a Japanese origin —
so the native shape of the config (`upstreamUdpIsLossy = false`: no DNS hijack,
DNS riding the tun as UDP), which is the one `desktop-tun-native.json`
schema-checks in CI. That version number is worth keeping: 26.x is precisely the
macOS that cannot activate a new system extension, so the machine that proves
this path is the machine that ruled the other one out.

- **The tunnel carries traffic.** `curl https://api.ipify.org` from a plain
  shell, with no proxy set anywhere, went from the machine's own address to the
  origin's egress. That command is the point of this work: it never read the
  system proxy settings the old mode wrote, so it could not have produced this
  answer before.
- **The server exclusion is installed, and the routing table says so directly.**
  `auto_route` did not lay down `0.0.0.0/1 + 128.0.0.0/1`; it laid down
  `1`, `2/7`, `4/6`, `8/5`, `16/4`, `32/3`, `64/9`, `64.128/11`, `64.160/12`,
  `64.176/19`, `64.176.32/20`, … — the complement of the whole address space
  minus one address, splitting exactly around the origin's /32. That shape is
  what `route_exclude_address` produces and nothing else does.
- **A QUIC transport survives it.** hysteria2 is UDP, and its socket to the
  origin kept working after `auto_route` took the default route — so the
  exclusion holds for more than a TCP connection that happened to be established
  before the tun came up.
- **`SMAppService` registers from a signed-but-un-notarised build.** That
  question was open when this was written; the first successful run answered it.
  Notarisation stays on for Gatekeeper's sake, but it is not load-bearing here.
- **It coexists with another VPN**, and loses to it only where the other one is
  more specific. On the test machine another tunnel held a host route for
  `1.1.1.1`, which beats our `1.0.0.0/8` by longest-prefix match — so the
  system's DNS went there. Worth knowing before it reads as a leak in this app:
  it is the other tunnel's route, on a machine with two.

- **Stopping cleans up after itself.** `netstat -rn | grep -c 172.19.0.1` went
  from 38 while connected to 0 after disconnecting — `auto_route` removes what it
  installed, which is why this controller has none of the route bookkeeping the
  Linux and Windows ones carry.
- **The exit IP is the origin's egress, not its address, and that surprises
  people.** The test connected directly to a Japanese origin and landed on a US
  address, because that origin has WARP on: the tunnel chooses the *path to* the
  node, `warp_enabled` chooses how the node leaves for the internet. The routing
  table settles which of the two was in play — the excluded /32 names the host
  actually dialled, so a direct connection carves around the origin and one
  through an edge carves around the edge.

- **Killing the app fails closed, and that was measured rather than argued.**
  `pkill -9 -x ProofKit` on a live tunnel left all 39 routes in place — the
  daemon and its sing-box are not children of the JVM, so nothing signals them —
  and `curl` then returned nothing at all. That is the whole claim: traffic stops
  instead of falling back to the clear. The mechanism is that the app's own core
  died with it, so the daemon's socks outbound points at a `127.0.0.1` port
  nobody listens on any more, and packets entering the tun reach a dead end
  rather than a way around it.
  Use `pkill -x`, not `pkill -f ProofKit`: the pattern form also matches
  `ProofKitTunnelDaemon`, which runs as root and answers `Operation not
  permitted` — which looks like a failed test and is a refused one.

Not yet exercised on hardware: the adopt-and-stop path on the next launch.

## Who is allowed to command it

The socket is reachable by any local process and root is behind it, so the daemon
checks every caller: the peer's **audit token** → its code signature → a
requirement pinning Apple's anchor, our Team ID and the app's identifier. A pid
would not do, because a pid can be recycled between being read and being checked.
Clash Verge shipped a local root escalation by leaving this check out.

The daemon also never execs a path a caller sends it. It verifies the signature of
the `sing-box` inside the bundle, copies it into a root-owned directory and runs
the copy — `/Applications` is writable by any admin, so verifying and executing
the same file there leaves a window to swap it.

## IPv6

The tun claims IPv6 and refuses it. It carries none.

**Claimed**, because `auto_route` only takes over the address families the
interface has an address for. The first version of this had an IPv4 address
alone, and the consequence was found on a real Mac rather than here: the machine
kept its IPv6 default route on `en0`, so a browser — which prefers IPv6 — reached
every dual-stack site at the machine's own address, outside the tunnel, and the
reported IP never changed no matter which exit was selected. `curl
api.ipify.org` did not catch it, because that name has an A record only: the
check exercised precisely the half that worked.

**Refused**, because whether the far end has working IPv6 is a property of each
operator's node, not of this config. A reject is answered immediately and Happy
Eyeballs falls back to IPv4 in milliseconds; forwarding IPv6 into a node without
it would hang instead — the same outcome, bought with a timeout.

The Linux and Windows tun controllers have the same IPv4-only shape and so the
same leak. Not fixed here — this branch is macOS — but recorded so it is not
rediscovered as a macOS quirk.

Verified on the Mac: `netstat -rn -f inet6` shows `100::/8`, `200::/7`, `400::/6`,
`800::/5` … via `fdfe:dcba:9876::1` — the same complement form the IPv4 half
takes — and `curl -6 https://ifconfig.co` returns nothing at all. Before the fix
the browser reported the machine's real mobile IPv6 address and it did not change
no matter which exit was chosen.

## The exit address can be IPv6 even when your machine has none

Reported as a leak, and it is not one. With the tunnel up, `curl -6` returns
nothing (IPv6 is refused locally, as designed) while a browser on ifconfig.co
shows an IPv6 address in Germany. Both are true at once:

1. The machine reaches the site over **IPv4**, through the tunnel, to the origin.
2. The origin has WARP on, so it hands the request to Cloudflare.
3. WARP picks its own address family for its own upstream hop. ifconfig.co is
   dual-stack, so it goes IPv6 and the site records `2a09:bac5::…` —
   `CLOUDFLAREWARP` by RDAP, geolocated wherever that WARP egress sits.

`api.ipify.org` has an A record and nothing else, so the same tunnel reports an
IPv4 there. The client's address family and the exit's are independent, and a
dual-stack checker will report the far end's choice, not yours.

So: **what these sites show is the WARP egress, not the node.** To see the node's
own address, turn `warp_enabled` off for it. To check for an actual IPv6 leak,
use `curl -6` — silence is the correct answer.

## Known limits, stated plainly

- **A server hostname that re-resolves to a new address mid-session loses its
  exclusion** until the next reconnect. The core's redial used to route into its
  own tunnel then. It now enters the tun and leaves it again by the rule on the
  core's binary (above): a longer way round, not a dead end. Every address known
  at connect time is excluded, so this only matters on a DNS change during a
  session, and it is read in the code, not exercised.
- **A tunnel left behind by a killed app is stopped at the next launch, not
  adopted.** The app cannot say which location an orphaned tun belongs to, and
  showing a connection it cannot describe would be worse than a clean restart.
  Between the kill and that relaunch the tun stays up and traffic keeps flowing
  through it — fail closed, no leak.
- **macOS 13 or newer.** `SMAppService` does not exist below it; those Macs keep
  the SOCKS proxy.
- **A dead tunnel is noticed within about eight seconds, not instantly.** On
  Linux and Windows the tun process is a child of the JVM and the OS reports its
  exit. Here it belongs to the daemon, so the app asks every four seconds and
  asks a second time before believing the answer — a single unanswered question
  is not worth tearing a working tunnel down for. Asked rather than pushed on
  purpose: a notification would need a long-lived connection and the state to
  manage it inside the one component that runs as root.
- **olcRTC locations have no server host to exclude** — they are addressed by a
  room on someone else's SFU, so there is no endpoint to pin a route to. What the
  engine dials after the tun is up leaves by the rule on its binary. Their DNS
  still takes the reliable path (`hijack-dns` plus a TCP resolver through the
  tunnel), which is the same treatment iOS gives them.
- **A core or an engine that dies is restarted behind the tun, which stays
  up.** The app notices a dead sing-box or Xray core within two seconds and a
  dead olcRTC engine at once, says Reconnecting, and starts the process again on
  the port it had, after 2, 4, 8, 16 and then every 30 seconds for as long as
  the session lasts. The port is not a preference: the daemon's sing-box was
  given it when the tun was built and cannot be given another. The tun is not
  touched, so until the process is back its outbound points at a port nobody
  listens on, the dead end the killed-app test above measured: traffic stops,
  it does not go around. Only the tun's own death still ends the session. This
  is read in the code and not yet exercised on a Mac. As the config read when
  this was written, two cases could not come back by themselves under Global
  routing, and the rule on the engines' binaries (above) is there for both. A
  restarted olcRTC engine had no exclusion at all: its own sign-in to the
  meeting service entered the tun, whose only way out was the engine that was
  not up yet. It now leaves by that rule. A restarted core's own connection
  leaves by it too, also to an address that is new since connect. What is left
  is the name: the core a session started with is started again exactly as it
  was, so it asks the system's resolver for its server's name, and where that
  resolver is not on the local network the question enters the tun and waits
  for the core that is asking. Such a session stays Reconnecting, closed, until
  Disconnect. A server chosen later in the session does not have this limit; it
  is given a resolver of its own.

## Why not NetworkExtension

It was built, signed, notarised, embedded and installed, and macOS would not
activate it. `sysextd` rejects **new** system-extension activations on macOS 26
with `no policy, cannot allow apps outside /Applications` for apps that are in
`/Applications` — fully native, correctly signed ones included, LuLu among them.
Apple DTS calls that log line a red herring and has published no fix; extensions
activated before macOS 26 keep working, new ones do not.

- https://developer.apple.com/forums/thread/817101
- https://developer.apple.com/forums/thread/820254

Two further reasons the daemon would have won anyway: Apple requires the
activation request to come from the app's **main executable**, which for a
jpackage app is a JVM launcher; and no cross-platform VPN ships NE on macOS —
Mullvad, Tailscale and Clash Verge Rev all run a root daemon.

The NE work is parked on `feat/macos-system-extension` with its setup notes in
`docs/macos-system-extension-setup.md`. It is the shape to resume from if the Mac
App Store ever needs a provider.
