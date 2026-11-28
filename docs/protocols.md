# Protocols

Which protocols Ghostlane connects over, which engine carries each, and how the app moves between them. [Back to the README](../README.MD).

| Protocol | Carried by |
| --- | --- |
| olcRTC: a tunnel inside a WebRTC video call | the olcRTC client in the Rust core |
| VLESS + Reality, over TCP, gRPC or HTTP/2 (`type=http`) | the Rust core |
| VLESS over TLS, incl. behind a CDN, over TCP, WebSocket, HTTPUpgrade or HTTP/2; a link's `allowInsecure` is honoured, and `security=none` is VLESS with no TLS | the Rust core |
| Hysteria2, with Salamander obfuscation | the Rust core |
| Trojan | the Rust core |
| Shadowsocks, AEAD and 2022 | the Rust core |
| VMess | the Rust core |
| XHTTP | the Rust core |

A link that asks for something the app cannot carry is left out at import
rather than added as a server that can never connect: a Shadowsocks plugin or
stream cipher, Trojan over Reality, VMess over mKCP or QUIC.

The table is the set the apps start. The iPhone runs the same core behind its
packet tunnel, and the lines it refuses are named under [On iPhone](#on-iphone);
the Linux command names its own set in [cli.md](cli.md#first-connection).

## olcRTC and the meeting services

olcRTC opens a WebRTC media session to a public meeting service and moves
traffic inside it, so what stays on the wire is a call the network already
carries. Its relays hold a fixed number of slots, and the app shows how full
each room is before you join one. We maintain the engine at
[ghostlane-project/olcrtc](https://github.com/ghostlane-project/olcrtc); it has
our own UDP wire, multi-key support and metering.

olcRTC rides six meeting services: Yandex Telemost, WB Stream, Sber SaluteJazz,
VK Calls, MTS Link and Jitsi. A server list says which one each room is on, and
a provider that offers one exit over several of them shows up as a server per
service. SaluteJazz admits a guest to a call's data channels only, so its rooms
always run over `datachannel`; a VK Calls room is its call's join link and an
MTS Link room its meeting's, and both run over `vp8channel`. An experimental
switch in the connection settings opens olcRTC's encrypted connection the way
Chrome does, for networks that refuse the WebRTC library's own.

## Several transports in one list

One server list usually carries several. The app groups them by provider,
filters by protocol, and remembers the exit last used.

Hysteria2 rides QUIC, and a carrier that throttles or drops UDP leaves it
connected and carrying nothing. On iOS the app asks the tunnel twice whether a
few hundred bytes reach a well-known endpoint, and on two failures moves to the
same exit over a transport that rides TCP: once per connect, never to another
exit, and it says so in the log.

## Smart connect

**Smart connect**, on Android and the desktop, checks before a connect instead,
for a server published over several transports. Through each one it asks for a
`204` and then for 64 KB whole: Russian DPI lets a connection to a foreign
address carry its first 16 to 20 KB and then freezes it, so one small request
passes exactly where browsing then stalls. It takes the first transport that
passes, starting with the one that worked last time, and ends on olcRTC in the
same country. When only domestic sites answer, a mobile "whitelist" shutdown, it
goes to olcRTC first. It never changes the country, and it can be turned off in
the server-list settings.

The same switch covers a server that dies during a session, on Android and the
desktop. After two failed reconnects and half a minute without the server, with
a network present, the app tries the other transports of the same exit, then the
other servers of the same country in the same list, then olcRTC of that country.
A candidate is checked by an engine of its own first, so a dead one costs a check
and not a reconnect, and all of it happens behind the tunnel the session keeps
up. The first line that carries traffic becomes the active one, and the app says
which. It never changes the country or the list, and nothing moves back by
itself.

The desktop differs in three things. The count begins from a server the app is
already retrying: a core or an olcRTC engine that died, or a server chosen while
connected that never answered. A server that goes quiet under a core that keeps
running is not noticed there, so nothing moves. olcRTC does not go first on a
whitelisted network: from behind its own tunnel the app cannot ask which kind of
network it is on, and "a network is present" means the machine still has a
default route of its own. And on Linux, where an olcRTC engine runs as root and
starting one asks for the administrator's password, no dialog is opened for a
move nobody asked for: a session on any other server goes only to servers whose
cores ask for nothing, and a session in a room moves only once that room's
engine has stopped, when its own restart is asking already.

## One engine at a time

One engine runs at a time. What hands it the packets is the platform's business,
and the platforms differ: the [platform matrix](building.md#platform-matrix)
says how.

## On iPhone

The iPhone's packet tunnel carries no sing-box and no Xray. One Rust library,
`ghostlane-core`, runs there behind `hev-socks5-tunnel`: its core carries VLESS
over Reality (TCP or gRPC), VLESS over TLS on WebSocket or HTTPUpgrade,
Hysteria2 and XHTTP with Reality, and its olcRTC client carries the rooms. A
line outside that set, or one the core refuses, is not started: the connect
fails with the reason. The cases are `IosCore.refusal` in the shared Kotlin
sources.
