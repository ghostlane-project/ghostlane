# shellcheck shell=bash
# The one pin of ghostlane-core, the Rust library the iPhone's packet tunnel
# extension links (xray-rust's xray-ffi and the olcRTC client's olc-ffi, one
# runtime, one allocator, one byte budget). Sourced by
# build-ghostlane-core-ios.sh. Moving the core is a change of this one line,
# plus the regenerated UniFFI Swift in iosApp/PacketTunnel/GhostlaneCore/,
# which the build refuses to run without.
#
# Now: ghostlane-project/xray-rust gl/olc-ffi dddb2adf (ghostlane 1d60607f +
# olc-client + olc-ffi + ghostlane-runtime + ghostlane-core). After the olc
# integration (step B) this becomes that integration's commit.
GHOSTLANE_CORE_REPO="${GHOSTLANE_CORE_REPO:-ghostlane-project/xray-rust}"
GHOSTLANE_CORE_COMMIT="${GHOSTLANE_CORE_COMMIT:-dddb2adf7d16b253f20d0f5b9711ba0484f057a2}"
GHOSTLANE_CORE_TOOLCHAIN="${GHOSTLANE_CORE_TOOLCHAIN:-1.96.0}"
