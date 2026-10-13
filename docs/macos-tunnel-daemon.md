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
                                           while that sing-box runs:
                                             the system asks it for names
                                           when the kill switch is on:
                                             a pf block behind the tunnel
```

The cores are unchanged — same binaries, same ports, same logs as in proxy mode.
The daemon adds one more sing-box in front of them whose only job is to own the
tun. Everything the tunnel carries still leaves the machine through the same core
that carried it before.

Two things beside the tun are the daemon's, because only root can hold them: who
the system asks for a name while the tunnel runs ([Names](#names)), and the kill
switch's block ([The kill switch](#the-kill-switch)). Both are the helper's from
its third protocol on. A helper from before them goes on doing what it did, and
the app asks which it is talking to before it hands it a tunnel. After an
update of the app the previous build's helper is still the one running: a
connect tells it to stop before anything else is started, which is what makes
it step aside for the one on disk, and the log says "the tunnel helper was
updated". A tunnel that comes up under the switch with no block behind it,
whatever the reason, is stopped and the connect fails saying so.

## What the app reads back from it

The traffic chart in the status strip. The app owns no tun and so counts
nothing itself; the daemon's sing-box is the one that knows what went through,
and it is asked: its local API (`experimental.clash_api`) on a loopback port the
system picks at connect, behind a secret made for that tun alone. Both are in
`tun.json`, which is root's and 0600, and in the app's memory; nowhere else. The
app asks `GET /connections` once a second while its window is in front of
somebody, and reads two things from the answer: the totals, and the flows that
are open with the program behind each.

The second is why the totals are not simply shown. What the tun routed is not
only the user's traffic: a line whose server the tun could not be told to leave
out reaches the internet through the tun too — every olcRTC room, by the rule on
the engine's binary — and is counted there with the traffic it carries, which
for a room is every byte twice. So the flows of the session's own binaries are
taken off. They are few and long (a room's is one media stream for as long as
it stands), so what each had moved when it was last listed is kept off for good
once it is gone; what a flow moved in its last second before it closed is the
error, once per flow.

sing-box names a flow's program as its path and, in brackets, whoever runs it
(`/…/olcrtc-darwin-arm64 (roman)`). That, and the names of the totals, are in no
schema; `scripts/check-singbox-traffic-api.sh` asks the pinned release for them
in CI, on Linux, and has been run by hand on a Mac. The Windows tun reads the
same API, and a Windows build was asked once, by hand, on 13 October 2026:
the same totals, and a flow's program as its bare path, with nobody in
brackets. The rule on a program's path matches there only when the path is
spelled as Windows spells it, drive letter and case included (`d:\…` for
`D:\…` did not match, nor capitals, nor forward slashes), and so does what the
app takes off the totals. The app hands both the paths the system itself
reports for the running processes.

What it means on the machine: one more listener on `127.0.0.1` for as long as a
tunnel is up, beside the verification inbound that was always there. The same
API also lists where every program is connected to and closes connections on
request, which is what the secret is for. A session in proxy mode has no tun and
so no count, and its status strip has no chart.

### Running is not serving

The daemon answers a start a second after it started sing-box, and what it
answers is that the process runs. The app's next step asks the tunnel
something: the session's check goes through the tunnel's own listener
(`verify-in`), and behind a block that stands a line is started behind it. So
the app first waits until that listener accepts a connection, for up to twenty
seconds, and writes how long it took when that was more than a second
(`macOS TUN: the tunnel began to serve … ms after the helper called it
running`). A tunnel that never does ends the connect with what its sing-box
said of a trouble.

The check itself tells two answers apart. A probe that fails at once was
refused on this machine: the tunnel, or the line behind it, is not serving
yet. A probe that waits out its eight seconds was not answered by the far end.
Each one that brings nothing back is written to the log with its reason and
its time (`the check of … brought nothing back after … ms: …`); a check whose
every probe was refused is made again, a second later, for up to twenty
seconds; one with a probe that waited is taken as the answer it is. When the
check fails for good, two more lines say which half does not carry: the
errors and warnings of the tunnel's sing-box (`macOS TUN, the tunnel's
sing-box: …`), and what the line answers when it is asked on its own port
with no tunnel in front of it (`The line itself, asked on its own port, …`).

Why. Build 1.0.463 on a Mac in the field, 13 October 2026: with the kill
switch on, the tunnel's process was killed, and a connect to a room followed.
The room was joined, by the engine the helper runs; the tunnel came up; the
check, made at once, failed within seconds, with not one request handed to
the engine; and the connect ended there, four times of four, for two
carriers. A core's line connected each time: behind a block its tunnel comes
first and is asked seconds later (in one of those starts it had still not
answered the app's own name twelve seconds after it was called running). The
same steps on a runner, with a real room and the app's own check, pass in a
second, and the first run of the build's signed programs takes a third of a
second there with the block or without (`lab/macos-room.sh`,
`lab/macos-exec.sh` on `lab/macos-tunnel`): what makes that Mac's tunnel slow
to serve is not known. The log said nothing either way: the check's null was
all there was, and the daemon's tail of that tunnel was gone by the time the
log was copied.

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

Measured on macOS 26.6.2 with the config the app writes for a room's session:
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
ahead of the network's own. 172.19.0.2 is the far end of the tun's own network:
nobody is there, the address only has to be one whose route is the tun's. The
helper sets the key once its sing-box is up and removes it the moment that
process is gone, however it went, so the system is back at the router's at
once. `State:` keys are not kept across a restart of the Mac, and a helper that
finds one at its own start removes it.

The tun then has to answer what it is asked, on every line, so the config the
app writes for such a helper differs from the one it wrote before
(`SingBoxConfig.buildDesktopTun`, `answersEveryName`):

- **Every question that enters the tun is answered there**, on a core's line as
  on a room's: a fake address at once (`198.18.0.0/15`), the name carried to the
  exit, which resolves it. Until now a core's line hijacked nothing.
- **It answers before it lets the lines' binaries out.** A line may be asking
  that same address, and let out first, as on Windows, its question would leave
  by the physical interface for an address that is nowhere.
- **What a line's own binary asks gets real addresses**, from the network
  underneath: a core asks for its server and an engine for its meeting service,
  and a fake address for either is an address inside the tunnel the asker is
  there to build. sing-box asks the interface's own resolver for these itself
  (`local`), which the key above does not change: seen on `en0`, from the
  tunnel's process, to the router.
- **The local network's own names are asked of the local network**: `.lan`,
  `.home`, `.local`, `.internal` and their like, a name with no dot in it, and
  the reverse of the private ranges, which the system asks about by itself all
  day. After the user's own routing rules, which may say otherwise.
- **The question about an encrypted resolver is kept.** A system asks its
  resolver whether it names an encrypted one of its own (`_dns.resolver.arpa`,
  RFC 9462), and here the tun is that resolver. Passed on with every other
  name, the question came back with the far resolver's answer about itself
  (1.1.1.1 names `one.one.one.one` over TLS and over HTTPS) and the system
  went to what was named: on a hosted Mac a connection to
  `one.one.one.one:853` and one to `:443` through the tunnel within two
  seconds of its start, and on a Mac in the field, in a room, one to port 853 every
  few seconds for as long as the session ran. The tun answers it itself, ahead
  of anybody's rules: no error and no record, which is what the RFC has a
  resolver that names none say, and a forwarder is to keep the question.

What this does not move: a lookup that an application scopes to an interface
itself, and encrypted DNS an application or a profile does on its own. And a
session in proxy mode has no tun and so no part of this.

## The kill switch

Settings → Connection → **Kill switch**, offered once the helper is installed,
off unless switched on, applied at the next connect.

**The leak.** On a Mac the tun and every route through it belong to the
tunnel's process and go with it, however it ends. Measured on macOS 26.6.2:
`kill -9` of the tunnel's sing-box, and a second later the interface did not
exist, no route named it, the default route underneath carried everything
again, and a request from a plain shell was answered directly. The app notices
within about eight seconds (below) and until then nothing says so. An app that
is killed, or a core that dies, does not do this: the tun stays and points at a
port nobody listens on. The sentence in this file that said a killed tunnel
leaves a Mac with no network until it is restarted was wrong, and so was the
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
pass  out quick inet  from any to { <the server's addresses> }
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
- **The tunnel's own sing-box is let out by its group.** It is a core's way
  out: a line's binary leaves the tun by the rule on its program, through
  sing-box's own socket. pf can tell a process only by the user or the group
  that owns its sockets, and the tunnel has to be root's, so it runs in a group
  nothing else is in. `Process` cannot set a group between fork and exec, so
  the helper starts a copy of itself that does (`--as-group`, `GroupExec.swift`)
  and becomes sing-box. Root's other programs are held like everybody's.
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
- **The first server's addresses are let out**, the ones the tun keeps out of
  itself: its core dials them from the user's own process.
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
  measured (below); the server's and the local network's are the same rule
  with another address in it.

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

**Behind a block that stands, the tunnel comes first.** A connect has always
started its line and then the tunnel. Under a block nothing of a line gets out
except through a tunnel's process: a room's engine has to reach a meeting
service whose addresses nobody knows in advance. So there the tunnel is started
first and the line behind it, asking the tunnel for names, as a line that comes
later in a session does.

**A room's engine is not behind it.** Run by the helper in the group the block
lets out, it is started as it is without the switch: first, by the interface
that carries the internet, whether a block stands or not, and the tunnel after
it. Until 1.0.462 a room under the switch was started behind its tunnel
instead, the engine the app's own child; that is the build the timeouts above
were seen on, and what a helper that cannot run a line (protocol 3) still gets.

In 1.0.463 and 1.0.464 that order cost the room. The engine joined and held
its room's signalling, the start that followed loaded the block over it, and
the block as it was written then reset that connection: on a Mac in the field
the engine closed its room within six seconds of the start, three times of
three, and the session's check, made into that, found nothing twice and once
passed a moment before the room went. With the block standing first (after a
tunnel that was killed) the same connect held, which is what told the two
apart. The second rule of each pair above is for this.

**A core under the switch starts in the usual order** where no block stands:
it has made no connection by the time the block is loaded, its first dial is
the session's check, made once the tunnel is up. It is kept out of the tun by
address and let out of the block by address, and is told to ask the tunnel for
its server's name, since what it asked the router later in the session would
be asked on the port the block refuses.

That order has one thing to wait for. sing-box looks up the program behind a
connection only when the connection's source is one of the machine's own
addresses, as its list of interfaces has them, and it reads that list again a
second after the routing table last changed (sing-box 1.13.14,
`route/process_cache.go` `isLocalSource`; sing-tun 0.8.11 `delayCheckUpdate`;
the same in 1.14.3). A tun's own address is not in the list it read before the
tun existed. So from the moment a tun comes up until its first quiet second the
rule that lets the lines' binaries out matches nothing, and what a line dials is
handed back to the line: measured with a stand-in line that dials every
destination itself, five seconds and 891 connections deep. The config carries
one name (`names-its-programs.ghostlane.invalid`) that the tun answers only to
the app's own program, and the app asks it until it is answered before it
starts the line: 0 to 750 ms on the Mac it was measured on, twelve seconds at
most before the line is started anyway. A connect in the usual order does not
wait for it: the first server's core is kept out of the tun by address and
never needed the rule. A room's engine, joined before its tunnel, does need it
for what it dials over TCP afterwards, and has since this mode shipped: by the
code, what it sends in those seconds is handed back to it, and it has to make
those flows again. That has not been watched on a Mac.

And the rule misses a flow now and then after that second too. In the
helper's check a line's binary asks the tun for a name twice in a row; on one
run of two the second answer was an address of the tunnel's, which is what a
program the tun did not name gets (2026-10-13, macOS 15 runner; the check now
asks up to three times). An engine started behind its tunnel had every flow
judged that way, its first call to the meeting service included: on the Mac
the timeouts were seen on, two of nine such starts ended at once with `EOF`
from that first call. An engine the helper runs dials before there is a tunnel,
and its sockets for media never enter one.

**What it holds, and what it does not.** Every program's traffic that would
have reached the internet beside the tunnel, root's included, and another VPN's
with it: its packets are not the tunnel's. Not the local network. Not across a
restart of the Mac. And only a tunnel that was started with the switch on:
switched on in the middle of a session, it is there from the next connect.

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
- **A later core asks a resolver of its own.** A core that asked the system
  for its server's name could be asking through the tunnel it is there to
  build. So a sing-box started behind the tun is told whom to ask: with a
  helper that has the system ask the tun (above), the tun, which answers a
  line's binary with real addresses; with a helper from before that, the
  resolvers the Mac had before the tun came up, its query leaving by the rule
  on its binary. Xray has no such part and asks the system, so an XHTTP
  server named by hostname may not come up as a later server, and the log says
  so. Behind a tun that answers names the config gives it a way: a fake
  address, which it dials, and the rule on its binary, which leads that out
  with the name put back. That way has not been tried.

The rule needs the name of the physical interface (`route -n get default`). When
that cannot be found, and no routing rules need it either, the tun starts as it
always did, with the first server's address excluded and nothing else, the log
says so in one line, and another server in that session is the full restart it
used to be.

The same two things carry a session that goes to another server by itself, when
its own has stopped answering (smart connect; README, Protocols). A server it
considers is checked first by a core of its own, started beside the tun: that
core is one of the named binaries and leaves as the session's own does, and it
asks the same resolvers. A tun that started without the rule lets neither that
core nor another server's out, so a session in it does not move, and the log
says so once in an outage.

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
`netstat -rn | grep -c 172.19.0.1` does not drop to 0 in between. And for the
move the session makes by itself: with the same loop running, choose a server
whose address does not answer, in a list that has another server of the same
country. Within about a minute and a half the loop answers with that other
server's exit, the app says which server it moved to, and the Mac's own address
never appears. `route -n get default` has to name the physical interface while
the tunnel is up: that is how the app tells a dead server from a Mac with no
network, and a `utun` there would mean it never looks.

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
| the core it runs | `/Applications/ProofKit.app/Contents/Resources/sing-box`, copied to `/Library/Application Support/org.olcbox.app/bin/sing-box` |
| the step into a group | the daemon's own binary, copied to `/Library/Application Support/org.olcbox.app/bin/as-group` |
| a room's engine, under the switch | the app's `olcrtc-darwin-*`, copied to `/Library/Application Support/org.olcbox.app.lines/` (root's, everybody's to read) and run from there as the app's user, in the tunnel's group |
| what that engine prints | a file beside its config in the app's own `runtime` directory, named as the config with `.out` after it |
| generated config | `/Library/Application Support/org.olcbox.app/tun.json` (root, 0600) |
| control socket | `/var/run/org.olcbox.app.tunneld.sock` |
| the system's resolver, while a tunnel runs | `State:/Network/Service/org.olcbox.app.tunnel/DNS` in the dynamic store |
| the kill switch's block | pf anchor `com.apple/100.org.olcbox.app.hold` |

## Reading it

```bash
sudo launchctl print system/org.olcbox.app.desktopApp.tunneld | head -20
log show --last 10m --info --predicate 'process == "ProofKitTunnelDaemon"'

scutil --dns | grep -c 172.19.0.2   # not 0 while a tunnel runs: the system asks the tun
                                    # (not `head`: a Mac with a resolver of its own, from a
                                    # profile or another app, lists that one first, tunnel or not)
sudo pfctl -a 'com.apple/100.org.olcbox.app.hold' -s rules   # the block; empty with the switch off
ps -axo gid,command | grep '[s]ing-box run'                  # 7302243 under the switch, 0 without

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

### On a macOS 26 runner, not on a Mac of ours

The names and the kill switch were measured on a hosted macOS 26.6.2 runner
(Apple Silicon, resolver on the local link), where a script can be root and
nothing is lost when the network goes: the helper's own sources, built there
and driven over a pipe in place of its socket (it has no signed app to ask
it), with the config the app writes and a stand-in line that dials directly.
That is `scripts/check-macos-helper.sh` now, and the pull-request checks run
it, on a Mac, whenever the helper or the config it is handed changes; until it
existed nothing but a release build compiled the helper at all. What it
showed, 13 October 2026, and holds the helper to since:

- **A start with the block and with names**: the tunnel's sing-box in group
  7302243, the rules above in the anchor, the system's first resolver
  172.19.0.2, a name by the system answered `198.18.0.x`, a request to the
  server's address and one elsewhere both answered. The name the app asks to
  learn that the tun names programs, asked by a JVM the way the app asks it,
  was answered in 276 ms; asked by any other program it did not exist.
  The question about a resolver of its own (`_dns.resolver.arpa`), asked of
  the tun: no error and no record. Before the rule it brought no answer back
  in three seconds, in a check whose line is a stand-in.
- **The tunnel killed** (`kill -9`): the helper says idle with the block
  standing, the key is gone and the system asks the router again, the request
  elsewhere is refused in a millisecond, a name by the system gets no answer,
  and the address the block lets out still answers.
- **A start under the block that stands**: the tun names programs at once,
  requests by address and by name are answered through it.
- **A stop that keeps the block**: everything refused. **A release**: everything
  direct again, the anchor empty.
- **A start with names and no block**: sing-box in root's own group, the key
  set, and both gone after a plain stop.
- **What is refused**: an interface that is not a utun, an address that is not
  one, a resolver that is not an address. Nothing changed by any of them.
- **The helper told to end** (SIGTERM) with a tunnel up behind the block: the
  helper gone, the tunnel gone, its line gone, the anchor empty, the key gone,
  requests direct.
- **A line's program** (a stand-in that binds a socket to the address of the
  way out and asks a STUN server who it sees, once a second). Started by its
  user behind the block: no answer. Run by the helper: the user's uid, group
  7302243, root's own copy in the lines directory, its words in the app's
  file, and answered. It outlives its tunnel and is answered still; its user
  ends it; one is started under a block with no tunnel; the helper stops one
  when asked. Refused: a link, a relative name, a file that is not the
  asker's, an output its user cannot write.

- **A line that came before the block** (the same stand-in, also holding one
  connection open and asking over it once a second): run by the helper with
  no tunnel and no block, answered three times; then a start behind a block,
  and the same connection answered still, time after time, with what it binds
  to the way out. With the block as it was written until 1.0.464 the first
  request after the start was reset ("Connection reset by peer"), which is how
  this part of the check was seen to fail before the rule was added.

Asked on macOS 15 and 26 runners, apart from the check (`lab/macos-arm.sh` on
the `lab/macos-tunnel` branch), one connection opened first and asked over
once a second:

- **The block as it was written, loaded over a connection of the group**: reset
  at its first request, and a new connection of the group served. Over a
  connection of anybody else: reset, and a new one refused, which is the block.
- **With the second rule**: the older connection answered on and carried three
  megabytes; a new connection of the group served, anybody else's refused.
- **One rule with any flags and a state kept** did as well in that run. It is
  not what was built: pf would then track a connection whose opening it never
  saw. `keep state (sloppy)` is not in this pf at all (a syntax error).
- **A tunnel that comes up takes no open connection with it.** With no block,
  the table sent the far end by the tun once the tun was up, and the
  connection opened before it kept answering and carried three megabytes: it
  keeps the way it has, by the address it was opened from. So what the block
  does to an older connection is all that happens to one.

Asked on macOS 15 and 26 runners the same day as the check, apart from it
(`lab/macos-bound.sh` on the `lab/macos-tunnel` branch):

- **A socket bound to the way out's address leaves beside a tun.** With a tun
  up and no block, an unbound probe was seen in the tun's log and a bound one
  was answered without the tun ever seeing it.
- **Behind the block it is refused**, whoever's it is: no answer for a line's
  binary nor for any other, and bound TCP refused.
- **pf can send it into the tun instead** (`pass out quick route-to (utunN
  172.19.0.2) inet proto { tcp, udp }` before the last rule): bound UDP and TCP
  were then answered and seen by the tun, and with the tun killed under those
  rules nothing was answered, bound or not. That is not what was built. It
  would leave a room's engine behind its tunnel, every flow of it judged by
  the rule on its program, which misses now and then (above).

Not exercised anywhere: the app's own side of this on a Mac (the switch, the
status, the order of a connect), and the helper under launchd with its
signature checked. A real room behind the block: see the pull request.

## Who is allowed to command it

The socket is reachable by any local process and root is behind it, so the daemon
checks every caller: the peer's **audit token** → its code signature → a
requirement pinning Apple's anchor, our Team ID and the app's identifier. A pid
would not do, because a pid can be recycled between being read and being checked.
Clash Verge shipped a local root escalation by leaving this check out.

The daemon also never execs a path a caller sends it. It copies the `sing-box`
inside the bundle into a root-owned directory, verifies the signature of the
copy where it then lies, and runs that — `/Applications` is writable by any
admin, so verifying a file there and then copying or executing it leaves a
window to swap it, and a link put in its place would have been copied as a
link. The same goes for the copy of itself that it runs to put the tunnel in a
group of its own.

What the app sends beside the config is small and is read as what it is: the
tunnel's interface has to be a `utun` with a number, and every address an
address, before either is written into a pf rule.

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
  manage it inside the one component that runs as root. For those seconds, and
  after them, the Mac's traffic goes out directly unless the kill switch is on:
  the tunnel's routes went with its process (see The kill switch). The helper
  itself knows at once, and puts the system's resolver back at once.
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
  it does not go around. Only the tun's own death still ends the session, and
  that one does go around: see The kill switch. This is read in the code and
  not yet exercised on a Mac. As the config read when
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
  is given a resolver of its own. Nor does any session whose helper has the
  system ask the tun: the tun answers the first server's name from the network
  underneath, whoever asks for it.

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
