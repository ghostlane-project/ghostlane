# ghostlane — Ghostlane for Linux servers

The headless Ghostlane: one static binary and a systemd service that connect
the lines of your subscription — an olcRTC room (a tunnel inside an ordinary
video call on Yandex Telemost, WB Stream, Sber SaluteJazz or VK Calls), VLESS
Reality, Hysteria2 or XHTTP — with failover between them, and route a Linux
box through the tunnel. Servers, VPS boxes, routers, anything without a screen.

```sh
curl -fsSL https://github.com/ghostlane-project/ghostlane/releases/latest/download/install.sh | sh
ghostlane add 'https://…/sub/…'      # the subscription URL your provider gave you, or one vless:// / hy2:// / olcrtc:// line
ghostlane connect DE --proxy         # SOCKS5 + HTTP on 127.0.0.1:1080, no privileges
ghostlane connect DE --tun           # the whole machine; SSH and your services keep working
ghostlane status
```

Packages: deb, rpm, apk, Arch and tarballs for amd64, arm64 and armv7, on the
[Releases](https://github.com/ghostlane-project/ghostlane/releases) page, each
release signed (`install.sh` verifies the signature and the checksum before it
installs anything); signed apt and dnf repositories at
`https://ghostlane-project.github.io/ghostlane/` for updates with the package
manager; a Docker image, `ghcr.io/ghostlane-project/ghostlane-cli`. `ghostlane help`
and `ghostlane help <command>` explain every command with examples; the full
guide is [docs/cli.md](../docs/cli.md).

What it connects: olcRTC rooms, VLESS Reality, Hysteria2 and XHTTP lines from
the same lists — plain or encrypted (crypt1) — with failover between them,
over tun (with an optional kill switch) or a local proxy; systemd and OpenRC.
Not yet: VLESS over grpc/ws, trojan, shadowsocks, vmess.

Built from `cli/` in this repository (`make build`; Go 1.26). Licence:
GPL-3.0-or-later — this binary links [sing-box](https://github.com/SagerNet/sing-box);
the Ghostlane app itself stays MIT. See `COPYRIGHT` and `../THIRD_PARTY_NOTICES.md`.
