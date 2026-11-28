# The macOS tunnel daemon

macOS is the last platform to get a system-wide tunnel. Linux and Windows have
had one for as long as the desktop app has existed; macOS had a SOCKS proxy,
which only applications that read the system proxy settings ever used — so a
browser was covered and a game, a terminal or anything dialling a hardcoded IP
was not.

It gets one the way every other cross-platform VPN does on this operating
system: a small daemon running as root, owning a `utun`, with the app talking to
it over a socket. Not NetworkExtension — see the last section for why.

Design: `docs/superpowers/specs/2026-08-04-macos-tun-daemon-design.md`. That
design ran a sing-box as the tunnel; the daemon now runs hev-socks5-tunnel in its
place, and no Go program is left in it (see "What it used to be" below).

## What is actually running

```
ProofKit.app (as you)                    ProofKitTunnelDaemon (as root)
  ghostlane-engine (the Rust core)         launchd, KeepAlive
  SOCKS on 127.0.0.1:<port>, a login  ◄─┐  /var/run/org.olcbox.app.tunneld.sock
                                        │    ↑ peer checked by code signature
                                        └── hev-socks5-tunnel: utun → that SOCKS port
                                              the daemon lays the routes
                                              the servers' addresses are left out
                                              the system asks hev's resolver for names
                                            when the kill switch is on:
                                              a pf block behind the tunnel
```

The engine is the same program, on the same port, as in proxy mode. The daemon
adds hev in front of it, whose only job is to own the tun: what the tun takes
is handed to the engine's SOCKS port, and everything the tunnel carries still
leaves the machine through the engine that would have carried it without a tun.

The daemon speaks protocol 5 (`Daemon.protocolVersion`). The verbs are `tun`,
`stop`, `line`, `line-stop`, `release` and `status`. A request for the old
`start` verb, which carried a sing-box config, is an unknown verb. The `tun`
request is fields, never a config: an interface's name, an address, a number, a
login. hev's own config can name programs it runs as its tun goes up and down,
and hev runs as root, so the daemon writes the config itself and refuses any
field it does not know, and any key that names a script, wherever it stands.

Two things beside the tun are the daemon's, because only root can hold them: who
the system asks for a name while the tunnel runs ([Names](#names)), and the kill
switch's block ([The kill switch](#the-kill-switch)). Both are the helper's from
its third protocol on; running a room's engine in the block's group (`line`) is
its fourth. After an update of the app the previous build's helper is
still the one running: a request that changes the tunnel's state tells it to stop
and exit, which is what makes it step aside for the one on disk, and the log says
"the tunnel helper was updated". A tunnel that comes up under the switch with no
block behind it, whatever the reason, is stopped and the connect fails saying so.

## The routes

hev brings its utun up with its addresses and does nothing to the routing table,
so the daemon lays the routes (`Routes.swift`): the whole of each address family
except its first /8 (`0.0.0.0/8` is "this network"; `::/8` holds the loopback
and the mapped forms), less the addresses the tun is told to keep out of itself,
as the fewest prefixes that cover the rest. A kept-out address is a hole in what
goes into the tun, so it leaves by whatever route the machine has for it, on
whichever network the machine is on now, and the local network stays reachable
because its own route is longer than any of these. Each route is one message to
the kernel's routing socket; at most 64 addresses may be kept out and at most
1024 routes are laid.

A stop takes the routes away before it stops hev; when hev ends by itself the
daemon forgets them, and the utun takes every route through it when it goes, so
hev killed outright leaves none behind.

## What the app reads back from it

The traffic chart in the status strip. The app owns no tun and counts nothing
itself; the kernel keeps the utun's link counters, and the app reads them with
`netstat -ib` (`MacTunTraffic`): what the interface received is on its way in,
what it transmitted on its way out. The header is read for the columns, so an
extra column on a newer Mac cannot shift a reading into the wrong one. A tun
that is not up, or an answer that cannot be read, is no reading and never a zero.
The lines behind the tun leave beside it, in the helper's group or bound to the
physical interface, and are not in either count. A session in proxy mode has no
tun and so no count, and its status strip has no chart.

### Running is not serving

The daemon answers a `tun` request only after hev has brought the utun up
holding its address (it waits up to five seconds), the routes are laid, and hev
was still running 0.3 s later, which is how long hev takes to refuse a config it
cannot use (its resolver, for one). The system's resolver is switched only after
the routes are in. If the system does not take the key, the tunnel is stopped
and the request fails with the tail of hev's log.

## Names

A tun takes the Mac's routes and leaves its resolver where it was. On most home
and office networks that resolver is the router, whose address is on the local
link, and no route of a tunnel outranks a route to the local link. So with the
tunnel up, every name the system looked up went to the router beside the tunnel,
in the clear, and the address the router answered with was the one the
connection then used. The traffic itself was in the tunnel; the names, and
whatever a network chooses to answer to them, were not. On a Mac whose resolver
is a public one (1.1.1.1 set by hand, as on the Mac this mode was first tried
on) the questions did enter the tunnel, which is how this went unseen.

Measured on macOS 26.6.2 while the tunnel was still a sing-box (what the
system's resolver does does not depend on the program that owns the tun):
`www.wikipedia.org` and `ya.ru`, asked by the system, came back with their real
addresses, question and answer both seen on `en0`; the same name asked of
8.8.8.8 was seen on the tun and answered `198.18.0.2`, the tunnel's own.

A route does not move those questions. With a host route for the resolver
through the tun, `route get` named the tun and the questions still left on
`en0`: the system's resolver binds its sockets to the service's interface. What
moves them is a resolver of the tunnel's own in the system's dynamic store:

```
State:/Network/Service/org.olcbox.app.tunnel/DNS
  ServerAddresses           [ 172.19.0.2 ]
  SupplementalMatchDomains  [ "" ]
```

A supplemental resolver for the empty domain matches every name and is asked
ahead of the network's own. 172.19.0.2 is inside the tun's own network, so the
question enters the tun, where hev answers it.

Under hev the names are answered by hev's own resolver (`mapdns`): it listens
on `172.19.0.2:53`, inside the tun's own network (the tun's address is
`172.19.0.1`), answers each name with an address from `198.18.0.0/15`, one per
name (a cache of 10,000), and hands the name itself to the engine's SOCKS port,
which resolves it at the exit. The helper sets the system's key to that address
when the app asks for it (`names`, protocol 3 and later) and removes it the
moment hev is gone, however it went, so the system is back at the router's at
once. `State:` keys are not kept across a restart of the Mac, and a helper that
finds one at its own start removes it.

What this does not move: a lookup that an application scopes to an interface
itself, and encrypted DNS an application or a profile does on its own. And a
session in proxy mode has no tun and so no part of this.

## The kill switch

Settings → Connection → **Kill switch**, offered once the helper is installed,
off unless switched on, applied at the next connect.

**The leak.** On a Mac the tun and every route through it belong to the
tunnel's process and go with it, however it ends. Measured on macOS 26.6.2:
`kill -9` of the tunnel's process (a sing-box then; hev's utun and its routes go
the same way, and the daemon's own are taken away when hev ends), and a second later the interface did not
exist, no route named it, the default route underneath carried everything
again, and a request from a plain shell was answered directly. The app notices
within about eight seconds (below) and until then nothing says so. An app that
is killed, or an engine that dies, does not do this: the tun stays and points at a
port nobody listens on. An earlier version of this file said a killed tunnel
leaves a Mac with no network until it is restarted; that was wrong, and so was the
design that left macOS without a switch on the strength of it.

**The block** is the packet filter's. Rules in the kernel belong to no process:
loaded once, they stand until somebody takes them away or the Mac restarts. The
helper loads them before it starts the tunnel and leaves them alone when the
tunnel dies. They are in an anchor of their own under `com.apple/`, which the
system's main ruleset already hands over to (`anchor "com.apple/*"`), so
nothing of the system's is rewritten:

```
pass  out quick on lo0 all flags any no state
pass      quick on utun225 all flags any no state
pass  out quick proto { tcp, udp } from any to any group 7302243
pass  out quick proto tcp from any to any group 7302243 flags any no state
pass  out quick inet  proto udp from any port 68  to any port 67
pass  out quick inet6 proto udp from any port 546 to any port 547
pass  out quick inet  from any to { <the servers' addresses> }
pass  out quick inet  proto tcp from any to { <the same> } flags any no state
block return out quick proto { tcp, udp } from any to any port 53
pass  out quick inet  from any to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16,
                                    169.254.0.0/16, 224.0.0.0/4, 255.255.255.255 }
pass  out quick inet  proto tcp from any to { <the same> } flags any no state
pass  out quick inet6 from any to { fe80::/10, fc00::/7, ff02::/16 }
pass  out quick inet6 proto tcp from any to { <the same> } flags any no state
block return out quick all
```

- **The tunnel's interface is named by the app** (`utun225`, or the next one no
  interface has), in the tunnel's config and in the rule, so the rule can stand
  before the interface exists and after it is gone.
- **hev is let out by its group.** pf can tell a process only by the user or the
  group that owns its sockets, and the tunnel has to be root's, so under the block
  it runs in a group nothing else is in. `Process` cannot set a group between
  fork and exec, so the helper starts a copy of itself that does (`--as-group`,
  `GroupExec.swift`) and becomes hev. Root's other programs are held like
  everybody's.
- **A room's engine runs in that group too, and the helper is what runs it**
  (`line`, `LineChild.swift`, protocol 4). Not everything of a line enters the
  tun. A WebRTC engine binds each socket it gathers its ways through to the
  address of the interface that carries the internet, and macOS routes what is
  bound that way by the address, past the tun's routes: it leaves on the
  physical interface in the engine's own name (measured: such a socket is
  answered with a tun up and the tun's log never sees it). There the block
  refused it like anything else. Seen on a Mac with a room behind the block:
  every STUN and TURN request timed out, SaluteJazz was joined by its way over
  TCP, and VK Calls, which has UDP alone, not at all. Only root can put a
  program in a group, so the helper runs the engine for the app: a copy it
  writes into `/Library/Application Support/org.olcbox.app.lines/` as root's
  own (only of a file that is the asking user's, checked for this team's
  signature where it then lies), started as the app's user with the group, its
  output appended to a file of the app's. The app holds it as it holds any
  process, and ends it as any process of its user is ended.
- **The servers' addresses are let out**, the ones the tun keeps out of
  itself (the `pass` list of the request): the engine dials them beside the tun.
- **Port 53 is refused beside the tunnel wherever it goes**, before the local
  network is let out: the router is on it. With the tunnel up the system asks
  inside it (above); with the tunnel gone it has nobody to ask, which is the
  hold.
- **The local network stays reachable**, as it always was in this mode: a
  printer, a share, AirDrop.
- `return`, not `drop`: a program that is refused says so at once instead of
  waiting out its own timeout.
- **Whatever is let out is let out twice: as a connection opened under the
  block, and as one that was open before it.** pf reads a `pass` as a rule
  about the packet that opens a connection (it lists the group's rule as
  `flags S/SA keep state`), keeps a state from that packet, and passes the
  rest of the connection by the state. A connection that was open when the
  block was loaded has no state and sends no such packet again, so the next
  thing it sent met no rule but the last one and was reset. Every rule that
  lets something out is therefore followed by one for TCP with any flags and
  no state: the older connection goes on as it came, its answers arriving the
  way they arrived before there was a block. Only the group's has been
  measured; the servers' and the local network's are the same rule with
  another address in it.

**What takes it away:** a Disconnect; the switch turned off, at once and under
a running tunnel too, since the helper needs no password; the helper being
stopped by the system (switched off in Login Items, the app deleted), because a
helper that is being removed must not leave a Mac with no network and nobody to
ask; and a restart of the Mac. Nothing else. A tunnel that died, a reconnect
that fails, a quit from a held state: the block stays, the status says "The
tunnel is down and the kill switch is blocking traffic", and Connect or the
switch are the two ways out.

**A session restarted in full keeps it.** Another routing mode is a new
tunnel, and between the old one and the new the Mac's traffic went out
directly. With the switch on the old tunnel is stopped with its block kept,
and the new one comes up behind it. Changed to proxy mode, the block goes with
the tunnel: a proxy has no tunnel for it to stand behind.

**A room's engine is not behind the tunnel.** Run by the helper in the group the
block lets out, it is started as it is without the switch: first, by the interface
that carries the internet, whether a block stands or not, and the tunnel after
it (`LineChild.swift`).

That order matters for a connection the block did not see. In 1.0.463 and 1.0.464
the engine joined and held its room's signalling, the start that followed loaded
the block over it, and the block as it was written then reset that connection: on
a Mac in the field the engine closed its room within six seconds of the start,
three times of three. With the block standing first (after a tunnel that was
killed) the same connect held, which is what told the two apart. The second rule
of each pair above is for this, and `scripts/mac-checks/room-front.sh` holds a
connection open across a start to keep it.

**What it holds, and what it does not.** Every program's traffic that would
have reached the internet beside the tunnel, root's included, and another VPN's
with it: its packets are not the tunnel's. Not the local network. Not across a
restart of the Mac. And only a tunnel that was started with the switch on:
switched on in the middle of a session, it is there from the next connect.

## The tun is the session's, not one server's

The tun is built once, when a session starts, with one local SOCKS port and one
login (the fields of the `tun` request). Neither can change while it lives, so
a request for another port is a new tunnel, which the app makes only for a change
of the connection mode, the routing settings or the SOCKS port and login.
What the app does behind a tun that stays, when another server is chosen, is the
app's; see [features.md](features.md#the-desktop).

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
| `Installed` | Approved and running. Connecting now builds a real tun, and the kill switch is offered. |
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
| hev | `/Applications/ProofKit.app/Contents/Resources/hev-socks5-tunnel`, copied to `/Library/Application Support/org.olcbox.app/bin/hev-socks5-tunnel` |
| the step into a group | the daemon's own binary, copied to `/Library/Application Support/org.olcbox.app/bin/as-group` |
| a room's engine | the app's `ghostlane-engine`, copied to `/Library/Application Support/org.olcbox.app.lines/` (root's, everybody's to read) and run from there as the app's user, in the tunnel's group |
| what that engine prints | a file of the app's own, named in the `line` request |
| hev's config | `/Library/Application Support/org.olcbox.app/hev.yml` (root, 0600; it holds the SOCKS login) |
| control socket | `/var/run/org.olcbox.app.tunneld.sock` |
| the system's resolver, while a tunnel runs | `State:/Network/Service/org.olcbox.app.tunnel/DNS` in the dynamic store |
| the kill switch's block | pf anchor `com.apple/100.org.olcbox.app.hold` |

## Reading it

```bash
sudo launchctl print system/org.olcbox.app.desktopApp.tunneld | head -20
log show --last 10m --info --predicate 'process == "ProofKitTunnelDaemon"'

scutil --dns | grep -c 172.19.0.2   # not 0 while a tunnel runs: the system asks hev's resolver
                                    # (not `head`: a Mac with a resolver of its own, from a
                                    # profile or another app, lists that one first, tunnel or not)
sudo pfctl -a 'com.apple/100.org.olcbox.app.hold' -s rules   # the block; empty with the switch off
ps -axo gid,command | grep '[h]ev-socks5-tunnel'             # 7302243 under the switch, 0 without

ifconfig | grep -A3 utun            # a utun holding 172.19.0.1
netstat -rn | head -20              # default via that utun, a server's IP via the LAN gateway
netstat -rn -f inet6 | head -20     # and the IPv6 half
curl -4 -s https://api.ipify.org    # from a plain shell that knows nothing about a proxy
```

**The socket does not answer `nc`, and that is the design working.** `nc -U … <<<
'{"verb":"status"}'` returns `{"error":"unauthorized"}` even under `sudo`,
because the daemon authenticates the *code signature* of its peer, not its uid.
Being root buys nothing here; being the signed app is the only thing that does.
The status a person can actually read is the one in the app's own log.

That last command is the whole point of this work. `curl` never reads the system
proxy settings the old mode set, so an exit IP there is the system-wide tunnel and
could not have been produced by the mode it replaces.

## What is checked, and where

The helper is the one part of the app that runs as root, so it is built and
driven on a Mac in CI. The `mac-helper` job of `pr-checks.yml` builds the helper's
own sources and runs every script of `scripts/mac-checks/` as root, on
`macos-latest` and `macos-15-intel`, with hev, a stand-in for a line's program and
the helper asked over a pipe in place of its socket (it has no signed app to ask
it):

- `pf-group.sh`: with the block armed, a program started by the helper in the
  tunnel's group fetches a page through the utun, and the same program started
  by its user outside the group is refused.
- `room-front.sh`: a room's program started before any tunnel holds a
  connection out by the physical interface; the tunnel then starts behind the
  block, and the connection that was open before the block is not cut.
- `probe.sh`: what the block does to a one-shot probe of the app's own.
- `watch.sh`: the physical interface watch, which stops a line and starts it
  again behind the tun when the default interface changes.
- `lan.sh`: the LAN listener of a line started in the group, reached by a client
  that is another address on the network, with the block standing.

These prove the helper's half. A real engine behind the block is the release's
proof (`pf-group.sh` says so in its header). Not exercised on a Mac of ours: the
app's own side of this (the switch, the status, the order of a connect), and the
helper under launchd with its signature checked.

What was measured by hand, on macOS 15 and 26 runners and a Mac in the field,
while the tunnel was still a sing-box. The packet filter and the routing table
behave the same whichever program owns the utun; none of it has been repeated
under hev:

- **A socket bound to the way out's address leaves beside a tun.** With a tun up
  and no block, an unbound probe was seen in the tun's log and a bound one was
  answered without the tun ever seeing it. Behind the block it is refused,
  whoever's it is, which is why a room's engine runs in the block's group.
- **A connection opened before the block has no state in pf.** The block as it
  was first written, loaded over such a connection of the group, reset it at
  its next request; with the second rule of each pair (below) the older
  connection was answered on and carried three megabytes. A tunnel that comes up
  takes no open connection with it: the connection opened before it keeps the
  way it has.
- **Killing the app fails closed.** `pkill -9 -x ProofKit` on a live tunnel left
  every route in place (the daemon and the tunnel are not children of the JVM,
  so nothing signals them) and `curl` then returned nothing at all: the engine
  died with the app, and what enters the tun reaches a SOCKS port nobody
  listens on. Use `pkill -x`, not `pkill -f ProofKit`: the pattern form also
  matches `ProofKitTunnelDaemon`, which runs as root and answers `Operation not
  permitted`.
- **A route does not move the system's lookups; a resolver of the tunnel's own
  in the dynamic store does** (see Names).

## Who is allowed to command it

The socket is reachable by any local process and root is behind it, so the daemon
checks every caller: the peer's **audit token** → its code signature → a
requirement pinning Apple's anchor, our Team ID and the app's identifier. A pid
would not do, because a pid can be recycled between being read and being checked.
Clash Verge shipped a local root escalation by leaving this check out.

The daemon also never execs a path a caller sends it. It copies the
`hev-socks5-tunnel` inside the bundle into a root-owned directory, verifies the
signature of the copy where it then lies, and runs that — `/Applications` is
writable by any admin, so verifying a file there and then copying or executing
it leaves a window to swap it, and a link put in its place would have been
copied as a link. The same goes for the copy of itself that it runs to put a
child in a group of its own, and for a line's program: only a file that is the
asking user's own is copied, and the copy is checked for this team's signature
where it then lies.

What the app sends is small and is read as what it is: the tunnel's interface
has to be a `utun` with a number, every address an address, the SOCKS address a
loopback address, a login 1 to 255 bytes with no control character, before any of
it is written into hev's config or a pf rule.

## IPv6

The tun is given an IPv6 address (`fdfe:dcba:9876::1`), and the daemon lays the
IPv6 routes beside the IPv4 ones.

**Claimed**, because a route table that has IPv4 alone leaves the machine's IPv6
default route on `en0`: a browser, which prefers IPv6, reached every dual-stack
site at the machine's own address, outside the tunnel, and the reported IP never
changed no matter which exit was selected. That was found on a real Mac, in the
first version of this daemon. `curl api.ipify.org` did not catch it, because that
name has an A record only: the check exercised precisely the half that worked.

What the engine behind the tun does with an IPv6 connection (carry it or refuse
it) is its own rule and is not checked here. `netstat -rn -f inet6` shows the
IPv6 routes through the tun, as `netstat -rn` shows the IPv4 half.

## The exit address can be IPv6 even when your machine has none

Reported as a leak, and it is not one. With the tunnel up, a browser on
ifconfig.co can show an IPv6 address in Germany although the machine's own IPv6
never left it. Both are true at once:

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
ask for an address over IPv6 from a plain shell (`curl -6`): whatever answers must
not be the machine's own.

## Known limits, stated plainly

- **A server hostname that re-resolves to a new address mid-session** is not
  among the addresses the tun was told to keep out of itself. They are fixed
  when the tunnel is built, so this only matters on a DNS change during a
  session. Read in the code, not exercised.
- **A tunnel left behind by a killed app is stopped at the next launch, not
  adopted.** The app cannot say which location an orphaned tun belongs to, and
  showing a connection it cannot describe would be worse than a clean restart.
  Between the kill and that relaunch the tun stays up and traffic keeps flowing
  into a SOCKS port nobody listens on — fail closed, no leak.
- **macOS 13 or newer.** `SMAppService` does not exist below it; those Macs keep
  the SOCKS proxy.
- **A dead tunnel is noticed within about eight seconds, not instantly.** On
  Linux and Windows the tun process is a child of the JVM and the OS reports its
  exit. Here it belongs to the daemon, so the app asks every four seconds and
  asks a second time before believing the answer — a single unanswered question
  is not worth tearing a working tunnel down for. For those seconds, and after
  them, the Mac's traffic goes out directly unless the kill switch is on: the
  tunnel's routes went with its process (see The kill switch). The helper itself
  knows at once, and puts the system's resolver back at once.
- **olcRTC locations have no server host to exclude** — they are addressed by a
  room on someone else's SFU, so there is no endpoint to pin a route to. The
  room's engine is run before the tunnel and leaves by the physical interface.

## What it used to be

Until the flip the helper ran a sing-box the app configured: it copied the
program into `bin`, wrote the config (with the SOCKS port's login in it) to
`tun.json`, kept the rule-sets of a bypass config under `rules`, and the
sing-box laid its own routes (`auto_route`) and answered names itself
(`hijack-dns`). The daemon no longer runs it: `Sweep` removes `bin/sing-box`,
`bin/xray`, `bin/olcrtc*`, `bin/libolcrtc*`, `tun.json` and `rules` from root's
directory when the daemon starts, before it answers anybody, because a copy of a
Go core left there is a program a root-owned directory still holds and a config
that is left holds a login. Protocols 1 to 4 numbered the sing-box verbs; the
numbers keep their meaning, and the app reads the number back to tell this daemon
from an older one that would silently drop a field.

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
