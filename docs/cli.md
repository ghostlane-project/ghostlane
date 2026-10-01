# Ghostlane CLI for Linux

A headless Ghostlane for servers, VPS boxes, routers and anything else that runs
Linux without a screen: one static binary, a systemd service, and the same
subscription links the app takes — olcRTC rooms, VLESS Reality, Hysteria2 and
XHTTP lines. Source: `cli/` in this repository; its own
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
`ghostlane version` prints the version, the engine, sing-box and Xray-core
pins, whether this build reads encrypted (crypt1) lists, and the sha256 of
the public key every release is signed with.

**Package repositories** (updates with the system's package manager; signed
with the key in `cli/packaging/repo/ghostlane-repo.gpg.asc`, fingerprint
`862C 1829 3437 3928 03AD BF47 E168 6027 7F51 A4DE`):

Debian, Ubuntu and derivatives (amd64, arm64, armhf):

    curl -fsSL https://ghostlane-project.github.io/ghostlane/ghostlane-repo.gpg.asc | sudo gpg --dearmor -o /etc/apt/keyrings/ghostlane.gpg
    sudo curl -fsSL https://ghostlane-project.github.io/ghostlane/ghostlane.list -o /etc/apt/sources.list.d/ghostlane.list
    sudo apt-get update && sudo apt-get install ghostlane-cli

Fedora, RHEL, Rocky, Alma (x86_64, aarch64, armv7hl):

    sudo curl -fsSL https://ghostlane-project.github.io/ghostlane/ghostlane.repo -o /etc/yum.repos.d/ghostlane.repo
    sudo dnf install ghostlane-cli

The apt repository serves the newest version; the dnf repository keeps every
version (`dnf install ghostlane-cli-<version>`); older debs stay on the
release page. Alpine (apk), Arch and the tarballs come from the release page
or `install.sh`.

**Docker:** `ghcr.io/ghostlane-project/ghostlane-cli` (amd64, arm64, arm/v7;
distroless, the released binary and nothing else), see [Containers](#containers).

## First connection

    ghostlane add 'https://…/sub/…'      # a subscription URL, a ghostlane:// link, or one olcrtc://, vless:// or hysteria2:// line
    ghostlane list                       # every line with its country and carrier
    ghostlane connect DE --proxy         # or --tun
    ghostlane status

`connect` takes a country (`DE`: all of that country's lines, rooms and
servers alike, tried in the list's order with failover between them), an
exact label from `list` (`"🇩🇪 DE · SJ"`: that one line), or an index (`6`;
`list` numbers the lines of every subscription continuously). Lists whose
labels carry no country code (a partner's `🇷🇺 EKB · Hy2 → 🇪🇺`) are selected
by label or index. The selection is stored: the service reconnects it after a
reboot; `disconnect` clears it. A selector that matches nothing today (the
country is not in the list yet) is retried when a refresh changes the list.

`add` takes an `https://` list (an `http://` list is refused: its token would
travel in plaintext), a `ghostlane://add?url=…` link, one `olcrtc://`,
`vless://` or `hysteria2://` line, and — when `ghostlane version` says
`crypt1 lists available` — an encrypted `olcrtc://crypt1/…` link (its payload is
a list URL or lines) or a list whose body is encrypted, as the app does. A
release build carries the key; a build without it says so and refuses those.

**Which core carries what.** olcRTC rooms run in the olcRTC engine, XHTTP
lines in Xray-core, VLESS Reality (tcp) and Hysteria2 in sing-box itself; all
behind the same sing-box front. Lines this version does not connect (VLESS
over grpc/ws/httpupgrade, trojan, shadowsocks, vmess) are listed with a note.

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
- **Fail-open by default, closed with `--kill-switch`.** Stopping the service
  or `disconnect` removes the tun and the rules; traffic flows directly again.
  `connect DE --tun --kill-switch` adds a kill switch to the selection: while
  it stands and no line is up (at boot before the first room answers, between
  failovers, during a dead-server backoff), connections that are not for the
  tunnel are refused at once (`EHOSTUNREACH`) instead of leaking. What `main`
  routes specifically keeps working (the LAN, Docker networks, link-local,
  static routes), inbound services keep answering, and the daemon reaches the
  carriers. `disconnect` and a clean stop of the service open the box again;
  after a crash the next start replaces the leftover rules at once. A
  connection opened directly before the switch keeps its path. `status`
  shows `kill switch on`.
- **A dead server never gets the tun.** A Reality or Hysteria2 line is first
  proven through a local proxy-only front; only then are the rules and the
  tun created. Rooms and XHTTP lines are proven through their engine first.
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

**Alpine / OpenRC.** The apk and the tarball ship `/etc/init.d/ghostlane`
(`supervise-daemon`, user `ghostlane` with ambient `cap_net_admin` and
`cap_net_bind_service`, log in `/var/log/ghostlane.log`); the package enables
and starts it, the tarball installer does where `openrc-run` exists:

    rc-service ghostlane status
    rc-update show default | grep ghostlane

Installing the `.apk` by hand needs `apk add --allow-untrusted` (the installer
verifies the release signature itself; the package carries no apk signature).
**Other init systems** (runit, s6): run `ghostlane run` yourself, as root or as
a user with `CAP_NET_ADMIN` for tun mode, or wrap that command in your init.

## Containers

The image `ghcr.io/ghostlane-project/ghostlane-cli:<version>` (and `:latest`;
amd64, arm64, arm/v7) is the released binary on a distroless base; its state
lives on `/data`. A local proxy for the host:

    docker run -d --name ghostlane --restart unless-stopped \
      -p 127.0.0.1:1080:1080 \
      -e GHOSTLANE_PROXY_LISTEN=0.0.0.0 -e GHOSTLANE_PROXY_USER=u -e GHOSTLANE_PROXY_PASS=p \
      -v ghostlane:/data \
      ghcr.io/ghostlane-project/ghostlane-cli \
      run --subscription 'https://…' --connect DE --mode proxy
    curl -x socks5h://u:p@127.0.0.1:1080 https://api.ipify.org
    docker exec ghostlane ghostlane status

The proxy is set through the environment: `GHOSTLANE_PROXY_LISTEN` (`0.0.0.0`
to publish the port), `GHOSTLANE_PROXY_PORT`, `GHOSTLANE_PROXY_USER`,
`GHOSTLANE_PROXY_PASS`. A listen that is not loopback without credentials is
refused at start: a published proxy without a password is open to whoever
reaches the port. The list and selection given once are stored on the volume;
a restart with no arguments reconnects them. For `--mode tun` (route the
container's network namespace, or another container's with
`--network container:ghostlane`) add `--cap-add NET_ADMIN --device /dev/net/tun`
and, if wanted, `--kill-switch`. The image runs as root (Docker grants the
capability to root); the daemon's own traffic, and that of any other root
process in the same namespace, stays outside the tun.

`ghostlane run` is the daemon in the foreground, usable in any container or
init: `--state-dir` (or `GHOSTLANE_STATE_DIR`), `--socket`, `--subscription`,
`--connect`, `--mode tun|proxy`, `--kill-switch`, `--probe-url`.

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
- A partner's plain list (VLESS XHTTP + Hysteria2 on relays) and its
  `?c=olcbox` variant (rooms) can both be added; `connect` picks by label or
  index when the labels carry no country code.

## Not in this version

VLESS over grpc/ws/httpupgrade, trojan, shadowsocks, vmess; IPv6 through the
tunnel; per-application split tunnelling. Encrypted (crypt1) lists need a
release build (`ghostlane version` says whether this one reads them).
