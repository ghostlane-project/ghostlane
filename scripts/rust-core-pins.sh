#!/usr/bin/env bash
# shellcheck disable=SC2034 # sourced: the scripts that source it read every variable
# The one version of the Rust core, xray-rust, the app builds, in one place.
#
# xray-rust (MPL-2.0) is the Rust client core that is to run Reality,
# WebTunnel, Hysteria2 and XHTTP in the iPhone's tunnel extension with no Go
# runtime loaded (OpenSpec change ghostlane-rust-core, D5). It is built from
# our fork, never from upstream directly: the fork is where the patches the
# app needs (hev's UDP, logs, network reset, memory) land, on its `ghostlane`
# branch. Its TLS is shaped-rustls, named by commit in its Cargo.toml's
# [patch.crates-io]; at the pin below that is aimalygin/shaped-rustls
# ca91d48c, the commit our fork ghostlane-project/shaped-rustls holds on its
# `ghostlane` branch, and the build fetches it from GitHub.
#
# Sourced by build-rust-core-ios.sh, rust-core-checkout.sh,
# fetch-rust-core-ios.sh, test-fetch-rust-core-ios.sh and
# .github/workflows/rust-core.yml, which reads it
# in a step and keys its caches on that step's outputs, so there is no second
# copy of these to drift. Bump XRAY_RUST_COMMIT here and the checkout, the
# cache keys, the release tag and the fetch all move together.

# ghostlane-project/xray-rust, a fork of aimalygin/xray-rust.
XRAY_RUST_REPO="${XRAY_RUST_REPO:-ghostlane-project/xray-rust}"

# A full commit, never a branch: the build fetches exactly this and refuses a
# checkout whose HEAD is anything else.
#
# 4a61b057e970: branch `ghostlane` of the fork = upstream main, the second
# 0.10.0 candidate ("release: prepare the second 0.10.0 core candidate",
# upstream CI green). Moves as our patches land on `ghostlane`.
XRAY_RUST_COMMIT="${XRAY_RUST_COMMIT:-4a61b057e97042396ec1a03fa8a0699919c42d64}"

# The channel the pinned commit's rust-toolchain.toml asks for. Exact, as the
# Go toolchain is in cores-pins.sh; the checkout refuses a tree that asks for
# another.
XRAY_RUST_TOOLCHAIN="${XRAY_RUST_TOOLCHAIN:-1.96.0}"

# The release tag the workflow publishes XrayRust.xcframework to and
# fetch-rust-core-ios.sh downloads from, and the asset's name. Derived, never
# spelled anywhere else.
RUST_CORE_IOS_TAG="rust-core-ios-${XRAY_RUST_COMMIT:0:12}"
RUST_CORE_IOS_ASSET="XrayRust-ios.zip"
RUST_CORE_IOS_URL="https://github.com/ghostlane-project/ghostlane/releases/download/${RUST_CORE_IOS_TAG}/${RUST_CORE_IOS_ASSET}"

# sha256 of that asset, written here once the workflow has published it (its
# summary prints the line to paste). A zip is not reproducible byte for byte,
# so this pins the one build that was published, and the workflow never
# overwrites a published asset. Empty: nothing published for this commit yet,
# and fetch-rust-core-ios.sh refuses to download.
RUST_CORE_IOS_SHA256=""
