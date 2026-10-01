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
  64 `olcrtc://` lines = 16 countries × 4 carriers (telemost `vp8channel`, wbstream
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
  `Restart=on-failure`, `RestartSec=2`, `After=network-online.target`,
  `Wants=network-online.target`. No `ConfigurationDirectory`: `ProtectSystem=strict`
  keeps `/etc` read-only, and the daemon owns its config, so it lives in the state dir.
- **Control socket** `/run/ghostlane/ghostlane.sock`, 0660 `ghostlane:ghostlane`: root and
  members of group `ghostlane` drive the daemon. Newline-delimited JSON: a request
  `{"verb": …}` gets `{"ok": true, …}` or `{"ok": false, "error": "<code>", "message":
  …}`. Verbs: `version`, `status`, `list`, `add`, `remove`, `refresh`, `connect`
  `{selector, mode}`, `disconnect`. Keys are never returned; subscription URLs come back
  with the token masked.
- **Files:** `/var/lib/ghostlane/config.yaml` 0600 — subscriptions (url, title, interval;
  a single pasted `olcrtc://` line is stored as an `inline:` subscription), selection
  (subscription, selector, mode), proxy (listen, port, user, pass). Beside it: the cached
  body and headers of each list with `fetched_at`, the device id, the last good line per
  group. Every CLI command goes through the daemon; nothing else writes these files.
- **CLI:** `ghostlane add <source>`, `list`, `connect <selector> [--tun|--proxy]`
  (default `--proxy`: no privileges, no routing change), `disconnect`, `status [--json]`,
  `refresh`, `remove <subscription>` (by URL, masked-URL prefix or title), `version`, and
  `run [--subscription <url> --connect <selector> --mode tun|proxy]` for containers (the
  flags store the list and selection at start, then the daemon connects). In proxy mode
  `status` prints the `http_proxy` / `ALL_PROXY` lines to paste. Exit codes: 0 ok,
  1 failed, 2 usage, 3 daemon unreachable.

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
  blocked — a partner can host one), `--family` (force one), `--no-service`. It verifies the signature of
  `SHA256SUMS` and the file's sum before installing and refuses on any mismatch. Idempotent:
  re-running upgrades in place; the service restarts and reconnects the stored selection.
- **apt/dnf repositories:** not in v1.

## 10. CI

- `pr-checks.yml`: job `cli` on `cli/**` changes — `go vet`, golangci-lint (a curated v2
  configuration in `cli/.golangci.yml`), `go test ./...` including the netns test (`sudo` on
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

## 15. Stage C — the rest of the CLI (added 2026-09-30, after PRs #78 and #79)

The owner asked for everything left to be finished. Stage C is the last planned
stage; the items below are designed here and implemented by
`docs/superpowers/plans/2026-09-30-linux-cli-stage-c.md`.

**Deferred minors of Stages A and B, all fixed.** `list` numbers entries across
subscriptions and `connect <index>` resolves globally; `add` refuses `http://`
lists (the token would travel in plaintext); a proxy `listen` of `localhost` is
refused and an IPv6 listen is bracketed in `status`; a cache file that fails to
parse counts as absent and is re-fetched; a selector that matches nothing is
retried on the refresh cadence while the selection stands; after a full
failover cycle the next attempt starts after the line that just failed; the
same inline line is stored once; refresh fetches lists in parallel (four at a
time) so a slow provider cannot exhaust the control socket's deadline; labels
decode `+` as a space, as the app does; CI pins golangci-lint and nfpm and the
CLI path filter includes `release.yml`; `install.sh --help` works under
`curl | sh` and `--base-url` without `--version` says that a mirror needs the
version; the mixed-kind failover test has a tun variant.

**crypt1 lists.** The CLI decrypts what the app decrypts: `olcrtc://crypt1/<blob>`
links (the blob is a URL or olcrtc lines) and encrypted `/sub` bodies. The
format is the coordinator's `crypt_link.rs`: base64url without padding of
`IV(16) | AES-256-CBC/PKCS7 ciphertext | HMAC-SHA256(IV | ciphertext)(32)`, keys
`sha256("olcrtc-crypt-v1-enc" | master)` and `sha256("olcrtc-crypt-v1-mac" |
master)`, master = 32 bytes given as base64 (standard or url-safe, padded or
not). Decrypt only. The master key is baked at build time from the app's
`OLCBOX_CRYPT_KEY_V1` secret (`-X main.cryptKeyV1=`); a build without it refuses
crypt1 sources with the message it gives today, and `ghostlane version` says
whether crypt1 is available.

**Kill switch.** `connect --tun --kill-switch` (persisted with the selection,
`kill_switch: true`). While the selection stands, traffic that is not for the
tunnel is refused instead of leaking: a policy rule at pref 9098 sends what
`main` routes specifically to `main` (`lookup main suppress_prefixlength 0`,
the same rule sing-box's tun uses: the LAN, Docker networks, link-local, static
routes keep working; only default-route traffic is affected), pref 9099 sends
the daemon's own uid to `main` (it must reach the carriers and servers), and
pref 9100 sends everything else to table 2023, whose only route is `unreachable
default` (v4 and v6; a refused connection gets EHOSTUNREACH at once). The
own-address rules (8990) go in with the switch and stay with it, so inbound
services keep answering while no line is up. sing-box's rules (9000–9010)
come first while the tun is up, so tunnelled traffic is unaffected; between
failovers, during a dead-server backoff and at boot before the first line is up,
the box is closed rather than open. The own-address rule (8990) still keeps
inbound services answering. `disconnect` and a clean daemon stop remove the
switch (an administrator who stops the service gets the box back); a crash
leaves the kernel state as it was, and the next start removes it with
`CleanupStale` and re-installs it when the stored selection asks. Proxy mode has
no kill switch (nothing is routed). Residual: a connection opened directly
before the switch keeps its path (its packets carry the host's own address,
which rule 8990 sends to `main`).

**OpenRC.** The apk and the tarball ship `/etc/init.d/ghostlane`: `supervise-daemon`,
`command_user ghostlane:ghostlane`, `capabilities ^cap_net_admin,^cap_net_bind_service`
(OpenRC ≥ 0.45 sets ambient capabilities), `/run/ghostlane` made in `start_pre`.
The postinstall enables and starts it where OpenRC is the init system.

**apt and dnf repositories.** Hosted on GitHub Pages of the repository
(`https://ghostlane-project.github.io/ghostlane/apt` and `/rpm`) from a
`gh-pages` branch the release workflow updates: `reprepro` (deb, distribution
`stable`, component `main`, amd64/arm64/armhf) and `createrepo_c` (rpm, one repo
for all arches, packages signed with `rpm --addsign`), both under one GPG key
(`packaging/repo/ghostlane-repo.gpg.asc` public; the private key is the
repository secret `CLI_REPO_GPG_KEY`). Packages are ~25 MB each and GitHub
Pages holds 1 GB in all, so the repositories are bounded: the apt repository is
rebuilt from scratch at every release and serves the newest version (reprepro
5.3, the version every distribution packages, keeps one version per package),
the dnf repository keeps the newest two versions, and the `gh-pages` branch is
one commit holding the current tree (force-pushed, no history). Older packages
stay on the release page. The job runs after `build-cli` only when the run publishes. The owner
enables Pages once (source: `gh-pages`, root). `docs/cli.md` shows the two
`sources` snippets; `install.sh` keeps installing the package directly.

**Docker image.** `ghcr.io/ghostlane-project/ghostlane-cli:<version>` and
`:latest`, multi-arch (amd64, arm64, arm/v7), from `gcr.io/distroless/static-debian12`
(CA certificates and tzdata, nothing else) with the static binary; entrypoint
`ghostlane run --state-dir /data`; `--subscription`/`--connect`/`--mode` as
arguments. Proxy mode needs `-p 127.0.0.1:1080:1080` and a `proxy.listen` of
`0.0.0.0` with credentials (the daemon refuses an unauthenticated non-loopback
proxy), which the image sets through `GHOSTLANE_PROXY_LISTEN`/`_USER`/`_PASS`
environment variables the daemon reads at start; tun mode needs `--cap-add
NET_ADMIN --device /dev/net/tun`. Built and pushed by the release workflow with
`GITHUB_TOKEN` (`packages: write`).

**Out of scope, still.** VLESS over grpc/ws/httpupgrade, trojan, shadowsocks,
vmess; IPv6 through the tunnel; per-application split tunnelling.
