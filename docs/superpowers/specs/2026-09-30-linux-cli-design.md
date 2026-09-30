# Ghostlane CLI for Linux — design

Date: 2026-09-30. Status: draft for the owner's review.
Home: this repository, new Go module `cli/`. Companion plan: written after this spec is approved.

## 1. Goal

A headless Ghostlane for Linux servers and boxes: one static binary, installed from a
deb, rpm, apk or Arch package or a tarball on any distribution, run as a systemd service
or in the foreground, that takes the same subscription links the app takes and routes the
machine through Ghostlane transports — the whole machine (`tun`) or one consumer at a time
(`proxy`). The first user is a subscriber of a partner whose list holds only olcRTC rooms.

## 2. Facts the design rests on

- **The partner's list** (`https://sub.<partner>/sub/<id>/<token>?c=olcbox`): a base64 body of
  68 `olcrtc://` lines = 17 countries × 4 carriers (telemost `vp8channel`, wbstream
  `vp8channel`, salutejazz `datachannel`, vkcalls `vp8channel`), labels like `🇩🇪 DE · VP8`,
  `🇩🇪 DE · VP8 · WB`, `🇩🇪 DE · SJ`; one key per country. Headers: `profile-title`
  (`base64:` prefix), `profile-update-interval` (hours), `subscription-userinfo`,
  `support-url`, `profile-web-page-url`. The same URL without `?c=` holds 8 Hysteria2 and
  9 VLESS-XHTTP lines and no olcRTC. ProofKit's own `/sub/<token>/unified` is plaintext
  with vless, hy2, xhttp and olcrtc lines.
- **The engine** (`github.com/openlibrecommunity/olcrtc`, branch `proofkit`, pinned by
  `scripts/cores-pins.sh` as `OLCRTC_VERSION`): `mobile.Runtime` is a plain Go package,
  usable without gomobile. Providers: `jitsi`, `telemost`, `wbstream`, `salutejazz`,
  `vkcalls`, `none`. Transports: `datachannel`, `vp8channel`, `seichannel`,
  `videochannel`. `AddFailoverRoom` is same-provider room hopping fed by the server's
  `##rooms`; moving between carriers is the host's job. `WaitReady(ms)` blocks until the
  SOCKS listener serves; `State()` is `idle|starting|running|stopping|stopped`.
- **sing-box 1.13.14** (the app's pin) as a library: `box.New(box.Options{Context:
  include.Context(ctx), Options: …})`. Its Linux `auto_route` policy rules (sing-tun 0.8.11,
  `tun_linux.go`): uid exclusions jump past the tun; every non-DNS packet first tries
  `main` with `suppress_prefixlength 0`; what is left goes to table 2022 (default via the
  tun). A reply to an inbound connection (SSH) only matches main's default route, which is
  suppressed, so the reply enters the tun and the session dies. §7.3 fixes that.
- **Xray-core** as a library at the version the iOS Cores link (`libxray v1.260711.0` →
  `xray-core v1.260327.1-0.20260711155151-50231eaff98c`); its role is XHTTP only, as in the
  app (`sing-box check` refuses xhttp).
- **Licences:** sing-box GPL-3.0-or-later, Xray-core MPL-2.0, olcrtc Apache-2.0, this app
  MIT. A binary that links sing-box is a GPL-3 work.

## 3. Decisions

1. **Go.** Every core is Go. A Rust wrapper would exec or FFI the same cores and add a layer.
2. **One static binary `ghostlane`**, `CGO_ENABLED=0`, for amd64, arm64 and armv7, with the
   cores linked as libraries. The `cli/` module is therefore licensed GPL-3.0-or-later
   (its own `LICENSE`); the app stays MIT. Reserve option, if the owner wants the CLI MIT:
   exec a bundled sing-box the way the desktop app does — a second binary in the package
   and a child process; nothing else in this design changes.
3. **Where:** `cli/` in this repository with its own `go.mod`
   (`github.com/ghostlane-project/ghostlane/cli`), released by `release.yml` under the
   app's version. Core pins are read from where the app keeps them (`OLCRTC_VERSION` in
   `scripts/cores-pins.sh`, `SINGBOX_VERSION` in `release.yml`); a test fails when
   `cli/go.mod` disagrees.
4. **Names:** package `ghostlane-cli`, binary `ghostlane`, unit `ghostlane.service`,
   service user and group `ghostlane`.
5. **Stages.** Stage A: olcRTC over `tun` and `proxy`, packaging, install script — a release
   can ship after it. Stage B: VLESS Reality, Hysteria2 and XHTTP with the app's dispatch
   (Reality and hy2 in sing-box, XHTTP in Xray). Stage C (not designed here): crypt1 lists,
   Bypass-Russia rules, kill switch, apt/dnf repositories, Docker image.

## 4. Components

```
cli/
  cmd/ghostlane/            subcommands; `run` is the daemon
  internal/links/           line and list parsing, headers, grouping
  internal/store/           config and state files
  internal/engine/olcrtc/   wraps mobile.Runtime
  internal/engine/singbox/  the front: config builder + box lifecycle
  internal/engine/xray/     Stage B
  internal/netlink/         own-address rules, stale-rule cleanup, address watch
  internal/daemon/          state machine: connect, failover, refresh, status; control socket
  internal/ipc/             request/response types shared by daemon and CLI
  packaging/                nfpm.yaml, ghostlane.service, scripts, install.sh
```

Each package is testable alone; only `daemon` composes them.

## 5. Links and subscriptions (`internal/links`)

- **Sources `add` accepts:** an https list URL; a `ghostlane://add?url=…` or
  `ghostlane://add/<url>` deep link (payload rules as `ImportLink.kt`); a single
  `olcrtc://`, `vless://`, `hysteria2://` or `hy2://` line; a file or stdin of lines.
  `olcrtc://crypt1/…` is refused in v1 with a message naming Stage C (it needs the build
  secret).
- **Fetch:** `GET` with `User-Agent: Ghostlane-cli/<version> (linux)`. Body: base64 (whole
  body, padding and newlines tolerated) or plaintext; lines split by scheme; unknown
  schemes are kept as "unsupported" so `list` can say so. Headers read: `profile-title`,
  `profile-update-interval` (hours, default 24, drives the refresh timer),
  `subscription-userinfo` (shown by `status`), `support-url`, `profile-web-page-url`,
  `announce`. Entry ids are stable across refreshes by (subscription, label), the app's
  rule, so a running selection survives a refresh.
- **olcRTC grammar** (port of `LocationsDatasource.parseOlcRtcUri`):
  `olcrtc://<provider>?<transport>[&k=v…]@<room>#<key>[%<client>][$<label>]`.
  Provider normalised as `LocationConfig.normalizeProvider` (telemost/yandex…,
  wbstream/wb-stream/wildberries, jitsi…, salutejazz/jazz/sberjazz…, vkcalls/vk…);
  transport as `transportOrNull`; `fps`/`batch` (or `vp8-fps`/`vp8-batch`) honoured for
  `vp8channel`; key is 64 hex characters; label defaults to the room.
- **Grouping:** country = the label with a leading regional-indicator flag stripped, then
  the app's `countryOf` (first token, two capitals). Within a country the lines keep list
  order, which is the coordinator's carrier order (telemost, wbstream, then by name).
  Selectors: `DE` (the country group), `"DE · SJ"` (one line by label), `3` (index from
  `list`).
- **Fixtures:** `internal/links/testdata/` holds the line cases of the Kotlin parser tests
  and a sanitised copy of the partner's two bodies (keys and tokens replaced), plus, for
  Stage B, the shapes `SingBoxConfigTest` and `XrayConfigRoutingTest` accept. Rule in
  `CONTRIBUTING.md`: a change to the Kotlin parsers lands with the same case in `cli/`.

## 6. Connecting

- **Modes.** `proxy`: sing-box `mixed` inbound on `127.0.0.1:1080` (port configurable;
  `--listen 0.0.0.0` only with user/pass), no privileges. `tun`: sing-box `tun` inbound,
  needs `CAP_NET_ADMIN`. Both: outbound `socks` → the engine's loopback SOCKS (a free
  loopback port the daemon picks, per-start credentials, UDP on), `direct` for private
  ranges, `final: tunnel`.
- **Engine per line kind.** olcRTC: one `mobile.Runtime` per connection: `SetProvider`,
  `SetTransport`, `SetRoom`, `SetKey`, `SetUDP(true)`, `SetDNS` (the host's resolvers
  from `/etc/resolv.conf`, then `1.1.1.1`), `SetDeviceIDPath` (state dir),
  `SetDirectRules` (private ranges), `SetSocksListenHost/Port/Credentials`, `Start`,
  `WaitReady(60 s)`. Stage B: vless-reality and hysteria2 are sing-box outbounds built like
  `SingBoxConfig.kt` (a `pinSHA256` means `insecure`, as the app does); xhttp is an
  in-process Xray with a loopback socks inbound and the outbound of
  `XrayConfig.buildXhttp`, fronted by sing-box like the engine.
- **Carrier failover** (the CLI's job; the engine's room hopping stays inside one carrier):
  for a country group, lines are tried in order; one not ready within 60 s is stopped and
  the next tried; all failed → the selection stays and the cycle retries with backoff
  10 s → 5 min. While up, the daemon probes every 30 s through the tunnel (`GET`
  `http://cp.cloudflare.com/generate_204` via the local SOCKS, plus the runtime's
  `State()`); three failures in a row → the next line of the group, wrapping around; the
  last good line of a group is remembered and tried first next time. A single-line
  selection reconnects the same line.
- **Refresh** never disconnects by itself. If the running line's room or key changed, the
  daemon reconnects at once (the old room is dead or rotated). If the line vanished, it
  keeps running and `status` says so.
- **Autoconnect.** `connect` stores selection and mode; the daemon restores them at start,
  so a reboot comes back connected. `disconnect` clears them.

## 7. Linux tun

- **sing-box tun options:** `interface_name: ghostlane0`, `address: [172.19.0.1/30,
  fdfe:dcba:9876::1/126]`, `auto_route: true`, `strict_route: false`,
  `iproute2_table_index: 2022`, `iproute2_rule_index: 9000`, `exclude_uid: [<daemon uid>]`,
  `route_exclude_address: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10,
  169.254.0.0/16, 127.0.0.0/8, 224.0.0.0/4, fc00::/7, fe80::/10, ff00::/8]`,
  `stack: system`, MTU default. Route: `sniff`; `protocol: dns → hijack-dns`;
  `ip_is_private → direct`; `ip_version: 6 → reject` (the app's leak fix: the tun claims
  IPv6 and refuses it, so dual-stack hosts fall back to IPv4 through the tunnel);
  `final: tunnel`; `auto_detect_interface: true`. DNS: `https` to `1.1.1.1` with
  `detour: tunnel` as `final`; a `local` server as `default_domain_resolver` (sing-box
  1.12+ refuses a `dns` section without one). `/etc/resolv.conf` is not touched: the host's
  queries reach the tun and are answered by the hijack.
- **The daemon's own traffic** — pion's ICE/DTLS to the carriers, sing-box's and Xray's
  outbounds — never enters the tun: the service runs as user `ghostlane` and the tun
  excludes that uid. No socket protector is needed.
- **Inbound services survive.** Before the tun comes up the daemon adds, for every
  global-scope address of the host (v4 and v6), the policy rule `from <addr> lookup main`
  at pref 8990, ahead of sing-box's 9000. Replies of accepted connections carry that source
  and keep their normal route; a socket that binds the public address explicitly goes
  direct too. A netlink address subscription keeps the rules in step with address changes.
  Rules go with the tun on disconnect. At start, the rules of pref 8990 and 9000–9010 and
  table 2022 are removed before a new tun is created, so a crash leaves nothing behind
  (the box has one tun, ours).
- **Not touched:** `/etc/resolv.conf`, global sysctls (`ip_forward`, `rp_filter`), nftables.
  If the netns test shows a strict `rp_filter` dropping the tun's traffic, the daemon sets
  `rp_filter=2` on `ghostlane0` only.
- **Fail-open.** When the daemon stops, the tun and the rules go away and traffic flows
  direct: a server must stay reachable. A kill switch is Stage C and off by default.

## 8. Daemon and CLI

- **Process:** `ghostlane run` is `ExecStart`; `Type=notify`, READY once the control socket
  listens; logs to stdout (journald) through a scrubber for keys, tokens and tokened URLs.
- **Unit:** `User=ghostlane`, `Group=ghostlane`, `AmbientCapabilities=CAP_NET_ADMIN
  CAP_NET_BIND_SERVICE`, `CapabilityBoundingSet=` the same, `NoNewPrivileges=yes`,
  `ProtectSystem=strict`, `ProtectHome=yes`, `PrivateTmp=yes`, `DeviceAllow=/dev/net/tun
  rw`, `RuntimeDirectory=ghostlane` (0750), `StateDirectory=ghostlane` (0700),
  `ConfigurationDirectory=ghostlane` (0700), `Restart=on-failure`, `RestartSec=2`,
  `After=network-online.target`, `Wants=network-online.target`.
- **Control socket** `/run/ghostlane/ghostlane.sock`, 0660 `ghostlane:ghostlane`: root and
  members of group `ghostlane` drive the daemon. Newline-delimited JSON: a request
  `{"verb": …}` gets `{"ok": true, …}` or `{"ok": false, "error": "<code>", "message":
  …}`. Verbs: `version`, `status`, `list`, `add`, `remove`, `refresh`, `connect`
  `{selector, mode}`, `disconnect`. Keys are never returned; subscription URLs come back
  with the token masked.
- **Files:** `/etc/ghostlane/config.yaml` 0600 — subscriptions (url, title, interval),
  selection (subscription, selector, mode), proxy (listen, port, user, pass), options.
  `/var/lib/ghostlane/` — the cached body and headers of each list with `fetched_at`, the
  device id, the last good line per group.
- **CLI:** `ghostlane add <source>`, `list`, `connect <selector> [--tun|--proxy]`,
  `disconnect`, `status [--json]`, `refresh`, `remove <subscription>`, `version`, and
  `run [--config <file>]` for containers (no socket needed: reads the config, connects,
  stays in the foreground). In proxy mode `status` prints the `http_proxy` /
  `ALL_PROXY` lines to paste. Exit codes: 0 ok, 1 failed, 2 usage, 3 daemon unreachable.

## 9. Packaging and distribution

- **Build:** `CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=<ver>"`,
  `GOOS=linux`, `GOARCH` amd64 | arm64 | arm (`GOARM=7`). `ghostlane version` prints the
  version and the three core pins.
- **nfpm** (`packaging/nfpm.yaml`) builds deb, rpm, apk and Arch packages from one file:
  `/usr/bin/ghostlane`, the unit (`/lib/systemd/system` on deb, `/usr/lib/systemd/system`
  on rpm), `/usr/share/doc/ghostlane-cli/README.md`, `LICENSE`. Scripts: preinstall creates
  the system user and group; postinstall runs `daemon-reload` and `enable --now`;
  preremove stops and disables; postremove keeps the data. No dependencies (static binary).
- **Tarball** `ghostlane-cli-<ver>-linux-<arch>.tar.gz`: binary, unit, README, LICENSE, for
  every other Linux.
- **Release assets:** the packages, the tarballs, `SHA256SUMS`, `SHA256SUMS.sig` (detached
  ed25519 over the sums file, OpenSSL-verifiable, private key = new repository secret
  `CLI_RELEASE_SIGNING_KEY`, public key embedded in `install.sh` and shown by
  `ghostlane version`), `install.sh`. Asset names carry `ghostlane-cli-` so they cannot be
  confused with the desktop AppImage.
- **install.sh** (POSIX sh): arch from `uname -m`; family from `/etc/os-release` (`ID`,
  `ID_LIKE` → deb | rpm | apk | pacman, else the tarball into `/usr/local/bin` with the
  unit); `--version`, `--base-url` (a mirror, for networks where GitHub is slow or
  blocked — a partner can host one), `--no-service`. It verifies the signature of
  `SHA256SUMS` and the file's sum before installing and refuses on any mismatch. Idempotent:
  re-running upgrades in place; the service restarts and reconnects the stored selection.
- **apt/dnf repositories:** not in v1.

## 10. CI

- `pr-checks.yml`: job `cli` on `cli/**` changes — `go vet`, golangci-lint (the engine's
  configuration copied), `go test ./...` including the netns test (`sudo` on
  `ubuntu-latest`, which has `/dev/net/tun`), `nfpm package` for deb and rpm,
  `systemd-analyze verify` on the unit, install smoke in `debian:bookworm` and
  `rockylinux:9` containers (`dpkg -i` / `rpm -i`, then `ghostlane version`).
- `release.yml`: `plan` gains a `cli` output (true on full releases and on a `cli`
  selector); job `build-cli` (matrix over the three arches) → artifacts → `publish` uploads
  them beside the app's assets under the app's version.
- The live gate (`gate.yml`) does not run the CLI in v1; before a tag, the live smoke of
  §11 runs by hand.

## 11. Testing

- **Unit:** parser cases and the sanitised bodies (§5), grouping and selectors, header
  parsing, config builders — the sing-box options are validated by constructing the box in
  a test (the real validator, no binary), the state machine against a fake engine, the IPC
  round trip.
- **netns** (needs root; `unshare -n` in CI and on a dev box): two namespaces joined by a
  veth pair. The "host" side runs the daemon as an unprivileged uid with ambient
  `CAP_NET_ADMIN` (`setpriv`) and, as root, a TCP listener on the host's veth address; a
  fake SOCKS5 server under the daemon's uid stands in for the engine and answers by dialing
  from the "exit" side. Asserts: a client in the peer namespace still reaches the listener
  with the tun up (inbound survives); a connection from the host to an address behind the
  exit is served by the fake SOCKS (tunnelled); an IPv6 connection is refused; the rules
  and table 2022 are gone after `disconnect`, and after `SIGKILL` + restart.
- **Engine integration** (opt-in, network): connect to a room from a fixture list and fetch
  a URL through the local SOCKS. Run on the DATA box by hand and in a nightly job, not on
  every PR — the rooms are shared with the app's gates.
- **Release smoke:** proxy mode in a container on DATA; tun mode only in netns or on a
  throwaway VPS — never on DATA's host network.

## 12. Security

- Subscription URLs and keys are credentials: 0600 files, masked in `status` and logs.
- The daemon execs nothing and has no self-update; updates come through the package
  manager or `install.sh`.
- The control socket is group-gated; there is no TCP control port. A proxy listening
  beyond loopback requires credentials.
- Release assets are signed; `install.sh` refuses an unsigned or altered download.

## 13. Rollout

1. Stage A on `feat/linux-cli` → PR → the next release carries the CLI assets. The
   partner's user: `curl -fsSL <release>/install.sh | sh`, `ghostlane add
   '<list URL>?c=olcbox'`, `ghostlane connect DE --tun` (or `--proxy`).
2. Stage B in the release after.
3. Stage C items as separate specs.

## 14. Out of scope for v1

GUI; macOS and Windows builds of the CLI; IPv6 through the tunnel; per-app split
tunnelling; kill switch; crypt1 lists; transport probing beyond the carrier loop; telemetry.
