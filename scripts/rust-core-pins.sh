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
# checkout, the CLI's cache key, the release tag, the fetch and the upstream
# CI the workflow runs (read from the pinned commit's own ci.yml) all move
# together.

# ghostlane-project/xray-rust, a fork of aimalygin/xray-rust.
XRAY_RUST_REPO="${XRAY_RUST_REPO:-ghostlane-project/xray-rust}"

# A full commit, never a branch: the build fetches exactly this and refuses a
# checkout whose HEAD is anything else.
#
# eb19e285d909: the fork's `ghostlane` after its first integration
# (2026-11-14). Upstream's second 0.10.0 candidate (4a61b057) with the
# reviewed topic branches merged in this order: gl/reset-network (C ABI
# 1.18, xray_core_reset_network), gl/logs (C ABI 1.19, the log callback),
# gl/hev-udp (hev's UDP ASSOCIATE with a login), gl/hy2-memory (Hysteria2
# windows per connection), gl/xhttp-upload-memory, gl/chrome-156-hello (the
# Chrome 156 hello); then rustls from ghostlane-project/shaped-rustls at
# b0dc21a7, that fork's `ghostlane` (its Chrome 156 hello and the deframer
# shrink), the only git source deny.toml allows for it.
#
# Pin only commits on the fork's `ghostlane`. That branch moves by merges and
# fast-forwards alone, never by a rebase or a force push, so a pinned commit
# (and the source of every asset published from it) stays reachable without
# a tag.
XRAY_RUST_COMMIT="${XRAY_RUST_COMMIT:-eb19e285d909771e70833b37b26d0f72c2c13c98}"

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
