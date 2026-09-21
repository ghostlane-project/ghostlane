# Sharing the tunnel with the rest of the room

The desktop app can let another device on the same local network use the
tunnel it already has: a phone with no client on it, a TV, a work laptop that
must not have a VPN installed. It is off by default, and everything below is
what it costs to turn on.

## What it is

sing-box listens as a SOCKS5 proxy on **one** private address of this machine —
`10/8`, `172.16/12` or `192.168/16`, chosen from the interfaces that are up and
not virtual — and forwards through the same chain the app is already using,
including the routing mode. Nothing is exposed to the internet: a public
address, `0.0.0.0` and the tunnel's own interfaces are refused, and the
listener is closed when the tunnel stops, when the app exits, when its health
check fails and when sharing is switched off.

## What it costs

**SOCKS5 authentication is not encrypted.** The username, the password and the
address of every request a client makes cross the local network in the clear;
anyone able to listen on that segment — or to answer ARP for the gateway — can
read them and then use the credential themselves, exiting through this
machine's node on this machine's allowance. The credential is 24 characters
from a 56-character alphabet drawn from `SecureRandom`, so guessing it is not
the risk; the wire is.

So: a home or office network you control. Not a cafe, a hotel, a conference or
a coworking space.

## What the app does about it

- **The network is pinned, not merely remembered.** Turning sharing on records
  the default gateway's address *and* its link-layer address. A network whose
  gateway does not match is a different network even when the laptop was given
  the same `192.168.1.x` lease, and sharing stays off until it is turned on
  again there. A gateway the app cannot identify is also treated as a
  different network: it fails closed.
- **The port is proved to be ours.** Before sharing is reported healthy, the
  app opens a second, loopback-only listener with its own random credential in
  the same sing-box process and reaches its exit through it. A port held by
  another process fails the whole configuration instead.
- **The path is checked, and re-checked.** The exit is verified through the LAN
  listener itself at start and every fifteen seconds after; a failure stops the
  listener rather than leaving it accepting into a dead tunnel.
- **On Windows** an inbound firewall rule is added for the sing-box binary
  alone, scoped to the private profile, the local subnet and that one address
  and port. It is removed on stop, and a rule left behind by a crash is swept
  at the next start of the app.

## Turning it off

Settings → the proxy row → **Share on the local network**. Switching it off
closes the listener at once; regenerating the credential from the same screen
invalidates what any device on the network was given.
