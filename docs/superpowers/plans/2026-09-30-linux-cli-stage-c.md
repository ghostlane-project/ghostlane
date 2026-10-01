# Ghostlane CLI for Linux — Stage C Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the CLI: every deferred minor of Stages A and B fixed, crypt1 lists, a kill switch for tun mode, OpenRC for Alpine, apt and dnf repositories on GitHub Pages, and a multi-arch Docker image — each proven by a test or a container run.

**Architecture:** Spec §15. Small, separable changes on the Stage A/B code: `links` and `store` for the parsing minors, `daemon` for selection/failover/refresh behaviour and the kill switch, `routes` for the kill-switch rules (netlink only, no nftables), a new `internal/crypt1`, packaging files and two new release jobs.

**Tech Stack:** as before; `crypto/aes`, `crypto/cipher`, `crypto/hmac` from the standard library; reprepro and createrepo_c in containers; docker buildx.

**Spec:** `docs/superpowers/specs/2026-09-30-linux-cli-design.md` §15 (plus §5–§9 as before).

## Global Constraints

- Stage A/B constraints hold (pins test, `CGO_ENABLED=0`, tags, GPL-3, no exec by the daemon, tun only in netns on DATA).
- Kill-switch rule prefs: 9098 (private → main), 9099 (daemon uid → main), 9100 (→ table 2023 `unreachable default`); `CleanupStale` removes 9098–9100 and flushes 2023.
- crypt1 wire format exactly as `coordinator/src/services/crypt_link.rs` (§15); decrypt only.
- Repos: `gh-pages` branch, paths `apt/` and `rpm/`, GPG key id in `packaging/repo/KEY.md`; every version kept.
- Docker: `ghcr.io/ghostlane-project/ghostlane-cli`, base `gcr.io/distroless/static-debian12`, platforms amd64/arm64/arm/v7.

## Review Focus

1. Kill switch with a stored selection at boot: between daemon start and the first line coming up, an outbound connection to the internet must fail fast (unreachable), LAN and inbound must work, the carriers must remain reachable for the daemon's uid. Task 4 netns test.
2. `disconnect` and SIGTERM remove the kill switch; a simulated crash (rules left) followed by a start with the selection still stored re-installs it. Task 4.
3. A crypt1 blob with a bad MAC, wrong key or truncated length is refused without panicking; a crypt1 link whose payload is a list URL is added as a subscription, one whose payload is lines becomes an inline subscription. Task 3.
4. `connect <index>` across two subscriptions picks the right line; `list` shows continuous numbers. Task 1.
5. The Docker image in proxy mode with `0.0.0.0` listen refuses a client without the credentials set through the environment. Task 6.

---

### Task 1: Deferred minors — parsing, store, daemon behaviour

**Files:** `cli/internal/links/list.go`, `share.go`, `olcrtc.go` (+ tests); `cli/internal/store/store.go` (+ test); `cli/internal/daemon/handlers.go`, `connect.go`, `daemon_test.go`.

- [ ] **Step 1: Failing tests** (each names the change):
  - links: `TestLabelPlusIsSpace` — `vless://…#DE%20via+RU` → label `DE via RU`; the olcrtc `$label` is not URL-decoded (it never was).
  - store: `TestCorruptCacheIsAbsent` — a cache file with garbage → `LoadCache` returns `nil, nil` and the file is removed.
  - daemon: `TestGlobalIndices` — two subscriptions (inline vless + the partner rooms list): `list` numbers 1..65 continuously and `connect 2` resolves to the rooms list's first line; `TestHttpListRefused` — `add http://…` → `Fail("insecure", …)`; `TestLocalhostListenRefused` — config `proxy.listen: localhost` → `connect --proxy` fails with a clear error, and `listen: "::1"` shows `[::1]:1080` in status; `TestInlineLineAddedOnce`; `TestSelectorRetriedOnRefresh` — selection `FR` stored, the cached list has no FR, state `failed`; the list gains FR → `refresh` → up; `TestFailoverContinuesAfterWrap` — three lines; the first dies after being up; the loop must try the second next, not the first again; `TestRefreshIsParallel` — four subscriptions whose fetch takes 100 ms each → `refresh` returns in < 250 ms.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**: `links.Entries` takes labels through `url.QueryUnescape` for share links; `store.LoadCache` removes and ignores an unparsable file; the daemon keeps a global ordering by iterating subscriptions in config order and offsetting indices (`list` and `connect` share `allEntries(ctx) []indexedEntry{sub, entry}`; `Select` on the flattened slice; the label/country selection then searches the same slice); `add` refuses `http://`; `frontParams` refuses `localhost` (and the front's builder rejects it); status brackets with `net.JoinHostPort`; `add` of an inline line already present answers "already added"; `connectLoop` on `ErrNoMatch` sets `failed` and returns, and `afterRefresh` (now called for every refreshed subscription) restarts the loop when the selection is stored but nothing runs; the wrap-around keeps a cursor: after `tearDown` of candidate i the next round starts at i+1 (`orderCandidates` rotates by the cursor, last-good only on a fresh connect); `refresh` runs fetches through a semaphore of 4 goroutines.
- [ ] **Step 4: Run** — `go test -race ./internal/...` → PASS. **Step 5: Commit** — `fix(cli): the deferred minors — global indices, http refused, listen checks, corrupt cache, retries, wrap order, parallel refresh, labels`

### Task 2: Deferred minors — CI, installer, tests

**Files:** `.github/workflows/pr-checks.yml`, `.github/workflows/release.yml`, `cli/packaging/install.sh`, `cli/internal/packaging/install_test.go`, `cli/internal/daemon/daemon_test.go`.

- [ ] Pin `golangci/golangci-lint/v2@v2.14.0` and `goreleaser/nfpm/v2@v2.47.x` (the versions that ran here: `golangci-lint --version`, `nfpm --version`) in both workflows; add `^\.github/workflows/release\.yml$` to the cli path filter.
- [ ] `install.sh`: usage text embedded in a heredoc printed by `--help` (test: `sh install.sh --help` from a pipe prints "Flags"); `--base-url` without `--version` → `die "a mirror needs --version"` (test).
- [ ] `TestMixedCountryFailoverTun`: the tun variant — first native line's pre-flight fails, the next kind's tun comes up; assert `front:proxy:vless front-close` then `routes:sync front:tun:hy2` and no `front:tun:vless`.
- [ ] Commit — `ci(cli): pinned tools, release.yml in the path filter; installer help and mirror message; tun failover test`

### Task 3: crypt1

**Files:** `cli/internal/crypt1/crypt1.go`, `crypt1_test.go`; `cli/internal/links/list.go` (`DecodeBody` takes an optional `Decrypt func(string) ([]byte, bool)`), `cli/internal/daemon/*` (add of `olcrtc://crypt1/…`, bodies), `cli/cmd/ghostlane/main.go` (`cryptKeyV1` ldflag), `commands.go` (version prints `crypt1: available|unavailable`), `cli/Makefile` (`CRYPT_KEY_V1` → ldflags), `.github/workflows/release.yml` (build-cli passes `secrets.OLCBOX_CRYPT_KEY_V1`), docs.

- [ ] Tests first: `TestDeriveAndRoundTrip` (a Go encrypt helper in the test builds a blob the way the coordinator does; decrypt returns the plaintext), `TestRefusals` (bad MAC, short blob, wrong key, bad base64 → `nil, false`, no panic), `TestParseKey` (std/url-safe, padded/unpadded, wrong length); links: a body that is a crypt1 blob decodes to lines when a decryptor is given, stays "unsupported" without one; daemon: `add "olcrtc://crypt1/<blob of a URL>"` → the URL is added as a subscription (fetched); `add "olcrtc://crypt1/<blob of lines>"` → an inline subscription with those lines; without a key → the Stage A message.
- [ ] Implement; `Decrypt(master [32]byte, blob string) ([]byte, bool)`; `ParseKey(b64 string) ([32]byte, bool)`; `Daemon.Deps.Decrypt func(string) ([]byte, bool)` (nil = unavailable). Commit — `feat(cli): crypt1 links and bodies (decrypt only), key baked at build`

### Task 4: Kill switch

**Files:** `cli/internal/routes/routes.go` (+ netns test), `cli/internal/store/store.go` (`Selection.KillSwitch`), `cli/internal/daemon/*`, `cli/cmd/ghostlane/commands.go` (`--kill-switch`), `docs/cli.md`.

- [ ] Tests first: routes netns: `InstallKillSwitch(uid)` adds rules 9098 (one per private range, v4 and v6), 9099 (uidrange), 9100 (→2023) and table 2023 `unreachable default` (v4+v6); `RemoveKillSwitch` removes them; `CleanupStale` removes them too. Daemon (fakes): `connect --tun --kill-switch` → `routes:killswitch` recorded before the first engine start, kept across failover (no `routes:killswitch-off` between lines), removed on disconnect; a stored selection with the flag installs it at start; `Run` returning (ctx done) removes it. Netns e2e (Review Focus 1–2): selection stored with kill switch, engine fake refuses (dead) → within 2 s a dial to 203.0.113.10:80 fails with "unreachable" (not a timeout), the far client still reaches the host listener, then the engine comes alive → up → tunnelled dial works; disconnect → dial goes direct again.
- [ ] Implement; the daemon's `Routes` interface gains `InstallKillSwitch(uid int) error` and `RemoveKillSwitch() error`. Commit — `feat(cli): kill switch for tun mode — the box is closed, not open, while no line is up`

### Task 5: OpenRC

**Files:** `cli/packaging/openrc/ghostlane`, `cli/packaging/nfpm.yaml` (apk: `/etc/init.d/ghostlane` 0755), `cli/packaging/scripts/postinstall.sh` and `preremove.sh` (OpenRC branch), `cli/packaging/install.sh` (tarball path: install the script when `/sbin/openrc-run` exists), `cli/Makefile` (tarball includes it), `cli/internal/packaging/install_test.go` (a fake `rc-update` records `add ghostlane default`), `docs/cli.md`.

- [ ] Script: `#!/sbin/openrc-run`, `supervisor=supervise-daemon`, `command=/usr/bin/ghostlane`, `command_args="run"`, `command_user="ghostlane:ghostlane"`, `capabilities="^cap_net_admin,^cap_net_bind_service"`, `pidfile`, `start_pre() { checkpath -d -m 0750 -o ghostlane:ghostlane /run/ghostlane; checkpath -d -m 0700 -o ghostlane:ghostlane /var/lib/ghostlane; }`, `depend() { need net; }`. Verify with `sh -n` and, in an `alpine:3.20` container with `openrc` installed, `rc-service ghostlane describe` (parses the script) and `openrc-run` dry checks; the apk install smoke asserts `/etc/init.d/ghostlane` exists and is executable.
- [ ] Commit — `feat(cli): OpenRC service for Alpine`

### Task 6: Docker image

**Files:** `cli/packaging/docker/Dockerfile`, `cli/Makefile` (`docker-image`, `docker-push`), `cli/cmd/ghostlane/run.go` (env `GHOSTLANE_PROXY_LISTEN/_PORT/_USER/_PASS` applied to the config at start), `.github/workflows/release.yml` (build-cli logs in to ghcr and pushes when publishing), `docs/cli.md`.

- [ ] Tests: `run` env handling unit test (config written with the values; refuses `0.0.0.0` without credentials with a clear message); local: `make docker-image` (amd64) → `docker run --rm ghostlane-cli:dev version`; proxy mode: `docker run -d -p 127.0.0.1:1085:1080 -e GHOSTLANE_PROXY_LISTEN=0.0.0.0 -e GHOSTLANE_PROXY_USER=u -e GHOSTLANE_PROXY_PASS=p -v data:/data ghostlane-cli:dev run --subscription '<partner list>' --connect 1 --mode proxy` → `curl -x socks5h://u:p@127.0.0.1:1085 https://api.ipify.org` shows the exit; without credentials the proxy refuses (Review Focus 5).
- [ ] Commit — `feat(cli): Docker image (distroless, multi-arch) pushed by the release`

### Task 7: apt and dnf repositories

**Files:** `cli/packaging/repo/build-repos.sh` (containers), `cli/packaging/repo/ghostlane-repo.gpg.asc`, `cli/packaging/repo/KEY.md`, `cli/packaging/repo/sources/ghostlane.list`, `ghostlane.repo`, `.github/workflows/release.yml` (job `publish-cli-repo`: needs build-cli + publish; checks out `gh-pages` or creates it orphan; runs the script with the secret key; pushes with `RELEASE_TOKEN`), `docs/cli.md`.

- [ ] Generate the GPG key on DATA (`gpg --batch --gen-key` with no passphrase, RSA 4096, uid "Ghostlane CLI repository <ghostlane-project@users.noreply.github.com>", 2 y expiry); export the public key to `packaging/repo/ghostlane-repo.gpg.asc`; keep the private key at `/root/.ghostlane/cli-repo-gpg.asc` 0600; the owner adds it as `CLI_REPO_GPG_KEY`.
- [ ] `build-repos.sh <dist dir with packages> <repo dir> <private key file>`: apt via `reprepro` in `debian:bookworm` (conf/distributions: Codename stable, Architectures amd64 arm64 armhf, Components main, SignWith key id); rpm via `createrepo_c` in `rockylinux:9` (`rpm --addsign` with the key, `createrepo_c --update`, `gpg --detach-sign --armor repodata/repomd.xml`); idempotent over an existing repo dir.
- [ ] Local verification: build repos from `dist/` (0.0.3), then `debian:bookworm`: add the key to `/etc/apt/keyrings`, `deb [signed-by=…] file:///repo/apt stable main`, `apt-get update && apt-get install ghostlane-cli` → `ghostlane version`; `rockylinux:9`: `.repo` with `baseurl=file:///repo/rpm`, `gpgcheck=1`, `repo_gpgcheck=1`, `dnf install ghostlane-cli` → version.
- [ ] Commit — `feat(cli): apt and dnf repositories on GitHub Pages, built and signed by the release`

### Task 8: Docs, notes, verification, PR

- [ ] `docs/cli.md`: repositories (the two snippets), Docker, OpenRC, kill switch, crypt1; `cli/README.md`; `docs/release-notes/pending.md` paragraph; `THIRD_PARTY_NOTICES.md` unchanged (no new linked code except stdlib crypto).
- [ ] Full verification (vet, lint, race suite, end-to-ends, netns incl. kill switch, `make dist sign`, container smokes incl. apk/OpenRC, Docker, repos), branch `feat/linux-cli-stage-c`, PR, fresh review, fix pass, merge. Owner actions listed in the PR: `CLI_REPO_GPG_KEY` secret, enable Pages on `gh-pages`, run a release.
