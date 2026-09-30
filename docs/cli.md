# Ghostlane CLI for Linux

A headless Ghostlane for servers, VPS boxes, routers and anything else that runs
Linux without a screen: one static binary, a systemd service, and the same
subscription links the app takes. Source: `cli/` in this repository; its own
licence is GPL-3.0-or-later because the binary links sing-box (see
`THIRD_PARTY_NOTICES.md`).

## Install

    curl -fsSL https://github.com/ghostlane-project/ghostlane/releases/download/<tag>/install.sh | sh

The script picks the package for the distribution (deb, rpm, apk, Arch) or a
tarball for everything else, verifies the release's `SHA256SUMS` against the
release signing key embedded in the script, verifies the package against the
sums, installs it, creates the `ghostlane` user and enables the service. Flags:
`--version`, `--base-url` (a mirror, for networks where GitHub is slow or
blocked), `--family deb|rpm|apk|pacman|tar`, `--no-service`.

Packages and tarballs are also on the release page as
`ghostlane-cli-<version>-linux-<amd64|arm64|arm7>.<deb|rpm|apk|pkg.tar.zst|tar.gz>`.
`ghostlane version` prints the version, the engine and sing-box pins, and the
sha256 of the public key every release is signed with.

## First connection

    ghostlane add 'https://…/sub/…'      # a subscription URL, a ghostlane:// link, or one olcrtc:// line
    ghostlane list                       # every line with its country and carrier
    ghostlane connect DE --proxy         # or --tun
    ghostlane status

`connect` takes a country (`DE`: all of that country's rooms, tried in the
list's order with failover between carriers), an exact label from `list`
(`"🇩🇪 DE · SJ"`: that one room), or an index (`6`). The selection is stored:
the service reconnects it after a reboot; `disconnect` clears it.

## Modes

**proxy** (default) serves SOCKS5 and HTTP on `127.0.0.1:1080` and needs no
privileges. `status` prints the lines to paste:

    export ALL_PROXY=socks5h://127.0.0.1:1080
    export http_proxy=http://127.0.0.1:1080 https_proxy=http://127.0.0.1:1080

**tun** routes the whole machine. The daemon creates `ghostlane0` and sing-box's
policy rules send everything through it except private and link-local ranges.
What stays as it was:

- **Inbound services keep working.** Before the tun comes up the daemon adds a
  policy rule per public address of the host, `from <address> lookup main`,
  ahead of sing-box's rules. Replies of accepted connections (SSH, web, a
  database) keep their normal route, also with strict reverse-path filtering.
  The rules follow address changes and go away with the tun.
- **The daemon's own traffic** to the carriers never enters the tun: the
  service runs as user `ghostlane` and the tun excludes that uid.
- **IPv6 is refused, not leaked**: the tun claims IPv6 and rejects it, so
  dual-stack hosts fall back to IPv4 through the tunnel.
- **Fail-open.** Stopping the service removes the tun and the rules; traffic
  flows directly again. A kill switch is not part of this version.
- Nothing else is touched: not `/etc/resolv.conf`, not global sysctls, not
  nftables. DNS the host sends to a public resolver is answered through the
  tunnel while it is up; a resolver on a private range (a home router) stays
  direct, like the rest of the private ranges.

## The service

    systemctl status ghostlane
    journalctl -u ghostlane -f

`ghostlane.service` runs `ghostlane run` as user `ghostlane` with
`CAP_NET_ADMIN` (tun and rules) and `CAP_NET_BIND_SERVICE`, `ProtectSystem=strict`,
`NoNewPrivileges`. Its files live in `/var/lib/ghostlane/` (root-only):
`config.yaml` (subscriptions, selection, proxy settings — the list URL is a
credential), the cached lists, the device id, the last working room per
selection. The control socket is `/run/ghostlane/ghostlane.sock`, group
`ghostlane`; root and members of that group may drive the daemon. Keys and
list tokens never appear in logs or in `status`.

Lists refresh on the interval the provider announces (`profile-update-interval`,
default 24 h) or on `ghostlane refresh`. A refresh never disconnects by itself;
a rotated room or key reconnects the running line.

**Proxy settings.** The proxy's address, port and credentials are the `proxy:`
block of `/var/lib/ghostlane/config.yaml` (edit as root, then
`systemctl restart ghostlane`):

```yaml
proxy:
  listen: 127.0.0.1   # anything else needs user and pass
  port: 1080
  user: ""
  pass: ""
```

**Systems without systemd** (Alpine with OpenRC, runit, s6): the packages ship
the unit only. Run `ghostlane run` yourself, as root or as a user with
`CAP_NET_ADMIN` for tun mode, or wrap that command in your init system; an
OpenRC script is planned. Installing the `.apk` by hand needs
`apk add --allow-untrusted` (the installer verifies the release signature
itself; the package carries no apk signature).

## Containers

    ghostlane run --state-dir /data --subscription 'https://…' --connect DE --mode proxy

`run` is the daemon in the foreground; the flags store the list and selection
at start. For `--mode tun` the container needs `--cap-add NET_ADMIN --device /dev/net/tun`.

## Troubleshooting

- `status` says `failed` with a last error: the list could not be fetched, or
  no room of the selection answered. The daemon keeps retrying with backoff
  (10 s to 5 min) while the selection stands.
- `room carries no traffic`: the room was joined but nothing came back within
  45 s; the next carrier is tried. A room that is full or rotated shows up the
  same way.
- `probe n/3 failed` in the log: the liveness probe through the tunnel failed;
  three in a row move to the next carrier of the group.
- Partner lists that have been disabled answer 404: the cached lines stay,
  `status` shows the error against the list.
- `ghostlane list` shows VLESS / Hysteria2 lines with a note: those transports
  come in the next version; only olcRTC rooms connect today.

## Not in this version

VLESS Reality, Hysteria2 and XHTTP lines; encrypted (`olcrtc://crypt1/…`) lists;
a kill switch; IPv6 through the tunnel; per-application split tunnelling.
