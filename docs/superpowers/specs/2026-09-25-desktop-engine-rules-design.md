# olcRTC on the desktop: the engine's rules where only the engine can route

**Status:** design (issue #33, desktop scope), 2026-09-25
**Repository:** app `ghostlane-project/ghostlane`; the engine is used as pinned (`8d97e32e0b1a`), unchanged

## Where #33 stood

The Android half shipped in 1.0.441 (#57): the engine takes the region's rules
itself and Android starts no sing-box front for olcRTC. For the desktop, #33
asked for the same: a `route:` block in the engine's yaml, the proxy-mode front
removed, and the engine's resolver pointed at the system's DNS servers on macOS
and Windows first, since `DesktopDnsResolver` reads them only on Linux.

Since #33 was written, #69 gave routing two things the engine's rules cannot
say. The engine takes one list, "these go direct", and sends everything else
into the room:

- **Only blocked sites through the tunnel** is the inverse ("only these go in").
- **A tunnel rule of the user's** wins over the mode's lists. The engine can only
  drop list entries equal to or under it. A name under a whole-TLD entry
  (`mail.ru` under `domain:ru`) stays direct.

On the desktop proxy the sing-box front does both exactly. On Android they are
out of reach for rooms by design: the owner chose "for now everything into the
room" on 2026-09-25, with no engine change.

## Decision

- **Desktop proxy keeps its front.** Removing it would take blocked-only mode
  and exact tunnel rules away from desktop rooms. On a computer the front costs
  one local process and nothing anyone has measured. The part of #33 this
  answers ("PAC and verify reach the front without credentials") is how every
  core on this port behaves: the Reality, Hysteria2 and XHTTP cores listen
  without credentials too.
- **The Linux tunnel gets the engine's rules for rooms.** It is the one desktop
  mode where the engine can route and nothing else can:
  - `linux-tun-up.sh` sends root's traffic through the main table
    (`ip rule add uidrange 0-0 lookup main pref 10`), and the engine runs as
    root there (`LinuxPrivilege`). So its direct sockets and its DNS queries
    leave by the physical interface.
  - hev's `mapdns` hands the engine names, not addresses. This is Android's
    shape exactly (hev, mapdns 100.64.0.0/10, engine rules), which has run on
    devices since 1.0.441.
  - The cores (Reality, Hysteria2, XHTTP) run as the user. Their direct sockets
    would enter the tunnel, so they stay Global there.
- **macOS and Windows: the system's DNS servers first.** The engine's resolver
  ring gets the system's servers followed by `1.1.1.1:53`, the list Android
  builds (`UpstreamDns.list`). A network that answers only its own resolver (a
  mobile "whitelist" shutdown, olcbox#16) then resolves at once. Today it waits
  out the public operators' silence (up to 4 s, `configuredLookupBudget`) before
  asking the host. Linux keeps its per-interface discovery unchanged.
- **Unchanged:** the macOS tunnel (the daemon routes; an unprivileged engine's
  direct socket would enter the utun) and the Windows tunnel (Global).

## Design

### Where the rules live (`desktopRulesHome`)

A pure function of the desktop mode, the location kind and whether any rules
were asked for (`RoutingSettings.needsRules`):

| mode | olcRTC | other cores |
|---|---|---|
| proxy | the front (`Core`) | the core (`Core`) |
| macOS tunnel | the daemon (`Daemon`) | the daemon (`Daemon`) |
| Linux tunnel | the engine (`Engine`) | nowhere (Global) |
| Windows tunnel | nowhere (Global) | nowhere (Global) |

No rules asked for is `Nowhere` everywhere. `startDesktopMode` branches on it
instead of on its current `rulesApply` flag.

### The engine's rules (`Engine`)

- The text is `OlcrtcDirectRules.forRouting(routing)`, exactly what Android
  hands `setDirectRules`: the private ranges, the user's direct rules and the
  region's lists, with the entries under the user's tunnel domains carved out.
  Under blocked-only or Global with rules, only the user's direct rules.
- It is written to `runtime/olcrtc-direct-*.txt` next to the yaml, about 224 KB
  for Russia. The yaml names it by absolute path:
  `route:` / `  direct_file: '<path>'`. The engine reads it once, at start, like
  the yaml. Both files are deleted when the session stops.
- An empty text writes no file and no `route:` block.

### The resolvers (`DesktopDnsResolver`)

- macOS and Windows: the system's servers as the JDK's DNS provider reads them.
  An `InitialDirContext` over `com.sun.jndi.dns.DnsContextFactory` reports them
  in `java.naming.provider.url` (`dns://192.168.1.1 dns://[fe80::1%en0]`).
  - On Windows that is the IP Helper list of the adapters that are up.
  - On macOS it is `/etc/resolv.conf`, which configd writes from the primary
    resolver.
  - No subprocess and no localized output to parse. On Windows this avoids a
    PowerShell start on every connect.
- `fec0::/10` is dropped: Windows lists the deprecated site-local placeholders
  on adapters with no IPv6 DNS.
- The packaged runtime gains `java.naming` and `jdk.naming.dns`. The call fails
  softly: any error, a missing module included, falls back to `1.1.1.1:53`,
  today's value.

### Settings

- **Linux:** routing becomes available, with a note: "In the Linux tunnel,
  routing applies to olcRTC rooms; other servers carry everything."
  "Only blocked sites" is not offered there, since it would change nothing.
- **Windows tunnel:** the unavailable reason says what to do: "The Windows
  tunnel carries everything. Routing applies in proxy mode." It replaces "…the
  Linux and Windows tunnels follow in a later build".
- The rules note's "On Android, in olcRTC rooms a tunnel rule can only take a
  site out of a region's direct list" gains Linux.
- A new optional `routingNote` passes from `ApplicationSettingsSheet` to the
  routing screens. It is shown above the lists note when routing is available.

## Testing

- **jvmTest:**
  - `OlcRtcCommand.yaml()` with and without `direct_file`, including a quote in
    the path.
  - `desktopRulesHome` over every mode and kind.
  - `DesktopDnsResolver.providerUrlServers`: IPv4, bracketed IPv6, zone, port,
    `fec0` placeholders, junk.
  - The JNDI path against the test host's `/etc/resolv.conf`.
  - Routing availability, note and modes per OS.
  - `LocalizationTest` covers the new and changed strings in all four languages.
- **Against the pinned engine:** the generated yaml with a real rules file,
  parsed by the engine's own config loader.
- **Not testable here:** a Linux tunnel end to end. The DATA host's network is
  off limits. The shape is Android's, and the check in #33 ("How to verify")
  applies to Linux as written.
