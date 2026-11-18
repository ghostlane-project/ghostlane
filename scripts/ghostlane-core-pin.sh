# shellcheck shell=bash
# The one pin of ghostlane-core, the Rust library the iPhone's packet tunnel
# extension links (xray-rust's xray-ffi and the olcRTC client's olc-ffi, one
# runtime, one allocator, one byte budget). Sourced by
# build-ghostlane-core-ios.sh. Moving the core is a change of this one line
# (the UniFFI Swift is generated from the library at each build).
#
# Now: ghostlane-project/xray-rust fa91beb6 (gl/ffi-tls-lab-1): ghostlane
# f9db68bb (step B: olc/, ghostlane-runtime, ghostlane-core, the TUN memory
# fix) + olc-ffi installing the signalling's TLS at a start (1.0.473 on an
# iPhone: "no TLS connector installed for a TLS URL" on every carrier).
GHOSTLANE_CORE_REPO="${GHOSTLANE_CORE_REPO:-ghostlane-project/xray-rust}"
GHOSTLANE_CORE_COMMIT="${GHOSTLANE_CORE_COMMIT:-fa91beb60eb881b2c1b61e286b6b84306cb34c9e}"
GHOSTLANE_CORE_TOOLCHAIN="${GHOSTLANE_CORE_TOOLCHAIN:-1.96.0}"
