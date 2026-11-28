# ghostlane — Ghostlane for Linux servers

The headless Ghostlane: a static program, the Rust core's engine (`ghostlane-engine`) and
the tun's `ghostlane-hev` beside it, and a systemd service that connect
the lines of your subscription — an olcRTC room (a tunnel inside an ordinary
video call on Yandex Telemost, WB Stream, Sber SaluteJazz, VK Calls or MTS
Link), VLESS Reality, Hysteria2 or XHTTP — with failover between them, and
route a Linux box through the tunnel. Servers, VPS boxes, routers, anything
without a screen.

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

Built from `cli/` in this repository: a Rust program (`make build`, `make test`, `make lint`;
`rust-toolchain.toml` names the toolchain). `ghostlane-engine` is the core's program at the pin in
`../scripts/ghostlane-core-pin.sh` and `ghostlane-hev` is hev-socks5-tunnel; the Core build makes
both (`packaging/build-arch.sh` stages them with the program). Licence:
GPL-3.0-or-later; the Ghostlane app itself stays MIT. The engine
([xray-rust](https://github.com/ghostlane-project/xray-rust), MPL-2.0) and hev-socks5-tunnel (MIT) are
separate programs in the package, each under its own licence. See `COPYRIGHT` and `../THIRD_PARTY_NOTICES.md`.
