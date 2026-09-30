# ghostlane — Ghostlane for Linux servers

The headless Ghostlane: one static binary and a systemd service that join an
olcRTC room — a tunnel inside an ordinary video call on Yandex Telemost,
WB Stream, Sber SaluteJazz or VK Calls — with failover between carriers, and
route a Linux box through it. Servers, VPS boxes, routers, anything without
a screen.

```sh
curl -fsSL https://github.com/ghostlane-project/ghostlane/releases/latest/download/install.sh | sh
ghostlane add 'https://…/sub/…'      # the subscription URL your provider gave you
ghostlane connect DE --proxy         # SOCKS5 + HTTP on 127.0.0.1:1080, no privileges
ghostlane connect DE --tun           # the whole machine; SSH and your services keep working
ghostlane status
```

Packages: deb, rpm, apk, Arch and tarballs for amd64, arm64 and armv7, on the
[Releases](https://github.com/ghostlane-project/ghostlane/releases) page, each
release signed (`install.sh` verifies the signature and the checksum before it
installs anything). `ghostlane help` and `ghostlane help <command>` explain every
command with examples; the full guide is [docs/cli.md](../docs/cli.md).

What it does today: olcRTC rooms over tun or proxy. What comes next: VLESS
Reality, Hysteria2 and XHTTP lines from the same lists.

Built from `cli/` in this repository (`make build`; Go 1.26). Licence:
GPL-3.0-or-later — this binary links [sing-box](https://github.com/SagerNet/sing-box);
the Ghostlane app itself stays MIT. See `COPYRIGHT` and `../THIRD_PARTY_NOTICES.md`.
