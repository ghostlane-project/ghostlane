#!/usr/bin/env bash
# shellcheck disable=SC2034 # sourced: the scripts that source it read every variable
# The one version of the Rust core, xray-rust, the app builds, in one place.
#
# xray-rust (MPL-2.0) is the Rust client core that is to run Reality,
# WebTunnel, Hysteria2 and XHTTP in the iPhone's tunnel extension with no Go
# runtime loaded (OpenSpec change ghostlane-rust-core, D5). It is built from
# our fork, never from upstream directly: the fork is where the patches the
# app needs (hev's UDP, logs, network reset, memory, the hello) land, on its
# `ghostlane` branch. Its TLS is shaped-rustls, taken from our fork of it,
# SHAPED_RUSTLS_REPO below, through the fork's Cargo.toml ([patch.crates-io])
# and Cargo.lock; rust-core-checkout.sh refuses a pinned tree that takes it
# from anywhere else, so this cannot drift back to upstream's repository.
#
# Sourced by rust-core-checkout.sh, build-rust-core-ios.sh,
# build-rust-core-cli.sh, fetch-rust-core-ios.sh, the two test-*.sh of those,
# and .github/workflows/rust-core.yml, which reads it in a step, so there is
# no second copy of these to drift. Bump XRAY_RUST_COMMIT here and the
# checkout, the CLI's cache key, the release tag, the fetch, the upstream CI
# and the fork's guards the workflow runs (read from the pinned commit's own
# ci.yml and ghostlane-guards.yml) all move together.

# ghostlane-project/xray-rust, a fork of aimalygin/xray-rust.
XRAY_RUST_REPO="${XRAY_RUST_REPO:-ghostlane-project/xray-rust}"

# A full commit, never a branch: the build fetches exactly this and refuses a
# checkout whose HEAD is anything else.
#
# 1d60607f3df8: the fork's `ghostlane` after its third integration
# (4a081163, 2026-11-14) and its CI fixes (2026-11-15). Upstream's second
# 0.10.0 candidate (4a61b057) with the reviewed topic branches merged in
# this order:
#  - first integration (eb19e285): gl/reset-network (C ABI 1.18,
#    xray_core_reset_network), gl/logs (C ABI 1.19, the log callback),
#    gl/hev-udp (hev's FWD_UDP, UDP in the control connection, with a
#    login), gl/hy2-memory (Hysteria2 windows per connection),
#    gl/xhttp-upload-memory, gl/chrome-156-hello (the Chrome 156 hello);
#    then rustls from ghostlane-project/shaped-rustls at b0dc21a7, that
#    fork's `ghostlane` (its Chrome 156 hello and the deframer shrink), the
#    only git source deny.toml allows for it;
#  - second integration (e8503168): gl/dns-forward (the DNS outbound's
#    `forward` rule, queries other than A/AAAA through a named outbound,
#    which core.json writes), gl/gitleaks-olc-vectors (a gitleaks exception
#    for the olcRTC port's synthetic key vectors, on their path only, so
#    upstream's secrets job passes over every branch of the fork) and
#    gl/fork-scope (AGENTS.md names the fork's write scope);
#  - third integration (4a081163): gl/lean-futures-r2, gl/byte-budget-relay,
#    gl/h2-read-buffer-r2, gl/byte-budget-windows-r3, and a Vision buffer fix:
#     - gl/lean-futures-r2: boxed long-lived futures, one UDP scratch buffer
#       per worker, the iOS runtime at one worker and four blocking threads,
#       release size guards and one fork-only size-lint step in ci.yml;
#     - gl/byte-budget-relay: one process-wide byte budget for relay buffers
#       and unflushed writes, FIFO waiters, a 4 KiB floor per flow, a 2 ms
#       flush deadline;
#     - gl/h2-read-buffer-r2: the vendored h2 reads each frame into an
#       allocation of its own size, XHTTP's header templates and its upload
#       sent without a joined copy;
#     - gl/byte-budget-windows-r3: HTTP/2 receive queues (XHTTP H2
#       responses, gRPC downlinks, HTTP/2 CONNECT, DoH) within the budget, a
#       settler for held-back credit, and the vendored quinn-proto's grown
#       window paying its shrink debt first (Hysteria2's windows unchanged);
#     - the Vision fix: a written frame's buffer is dropped whatever its
#       capacity reads, so a flow no longer keeps ~62 KB for its life;
#     - CI fixes (4ce0786b..1d60607f): the fork-only size lints and release
#       memory guards in a workflow of their own, ghostlane-guards.yml, with
#       ci.yml as upstream wrote it; the quinn-proto patch's shrink test
#       checks each read's ShouldTransmit; a gitleaks exception for str0m
#       0.25.0's example key files (rule and paths only); the iOS relay pool
#       spare said as up to 32 KiB.
#
# Pin only commits on the fork's `ghostlane`. That branch moves by merges and
# fast-forwards alone, never by a rebase or a force push, so a pinned commit
# (and the source of every asset published from it) stays reachable without
# a tag.
XRAY_RUST_COMMIT="${XRAY_RUST_COMMIT:-1d60607f3df8fcf18db2f00df7da6b722cf7fb7b}"

# The channel the pinned commit's rust-toolchain.toml asks for. Exact, as the
# Go toolchain is in cores-pins.sh; the checkout refuses a tree that asks for
# another.
XRAY_RUST_TOOLCHAIN="${XRAY_RUST_TOOLCHAIN:-1.96.0}"

# Where the pinned tree must take rustls from: the only git source its
# Cargo.lock may name for it, checked by rust-core-checkout.sh.
SHAPED_RUSTLS_REPO="${SHAPED_RUSTLS_REPO:-ghostlane-project/shaped-rustls}"

# Bumped when XrayRust.xcframework's shape changes while the commit does not
# (a slice, the deployment target, the header layout, a cargo flag), as
# HEV_BUILD is in hev-pins.sh: a packaging fix gets a tag of its own, so it can
# be published without moving the core.
RUST_CORE_IOS_BUILD="${RUST_CORE_IOS_BUILD:-1}"

# The release tag the workflow publishes XrayRust.xcframework to and
# fetch-rust-core-ios.sh downloads from, and the asset's name. Derived, never
# spelled anywhere else.
RUST_CORE_IOS_TAG="rust-core-ios-${XRAY_RUST_COMMIT:0:12}-b${RUST_CORE_IOS_BUILD}"
RUST_CORE_IOS_ASSET="XrayRust-ios.zip"
RUST_CORE_IOS_URL="https://github.com/ghostlane-project/ghostlane/releases/download/${RUST_CORE_IOS_TAG}/${RUST_CORE_IOS_ASSET}"

# sha256 of that asset, written here once the workflow has published it (its
# summary prints the line to paste). A zip is not reproducible byte for byte,
# so this pins the one build that was published, and the workflow never
# overwrites a published asset. Empty: nothing published for this tag yet,
# and fetch-rust-core-ios.sh refuses to download (a framework built here with
# build-rust-core-ios.sh is used only with RUST_CORE_IOS_ALLOW_LOCAL=1).
RUST_CORE_IOS_SHA256=""
