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
# 7259d7b2199f: "deps: take shaped-rustls from the ghostlane-project fork",
# the first commit of the fork's gl/chrome-156-hello branch. It is the fork's
# `ghostlane` (4a61b057, upstream's second 0.10.0 candidate) with one change:
# rustls comes from ghostlane-project/shaped-rustls at db373dbd, that fork's
# gl/chrome-156-hello (its `ghostlane`, ca91d48c, the commit upstream pins,
# plus a named error for a server that picks a suite the hello only
# advertised), and deny.toml allows that source. No core code moves.
# `ghostlane` itself still names aimalygin/shaped-rustls; the pin moves to
# `ghostlane` once that branch takes this commit.
#
# When the pin moves, tag the new commit in the fork (gl-pin-<commit12>), so
# a rebase of the branch it came from cannot leave a published asset's source
# reachable only by GitHub's grace. 7259d7b2 has no such tag yet: it is
# reachable from gl/chrome-156-hello alone.
XRAY_RUST_COMMIT="${XRAY_RUST_COMMIT:-7259d7b2199fb22ceb5910a8e3a01cf1ef33f88e}"

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
