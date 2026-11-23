# shellcheck shell=bash
# The one pin of ghostlane-core, the Rust library the iPhone's packet tunnel
# extension links (xray-rust's xray-ffi and the olcRTC client's olc-ffi, one
# runtime, one allocator, one byte budget). Sourced by
# build-ghostlane-core-ios.sh. Moving the core is a change of this one line
# (the UniFFI Swift is generated from the library at each build).
#
# Now: ghostlane-project/xray-rust add3245c (p6/integ, pin move PM2): a46c8ed0 +
# the engine's front, a core before the room in one process (F4c), and bound
# sockets on macOS (F5); the iPhone's closure is the same.
# Before it: a46c8ed0 (p6/integ, pin move PM1): fac14597 +
# the runtime and the counting allocator shared by one process (F2), two core
# handles in one process (F3), ghostlane-engine with rooms, one-shots and the
# Linux interface binding (F4a, F4b, F4h), allowInsecure only when the link sets
# it (F8), the first payload never claimed written while held, which stalled
# gRPC and XHTTP over h2 until the stream ended (FG), `ext:` lists (F7), the
# musl fixes, url 2.5.9 (RUSTSEC-2026-0592) and the testing partners in the
# README.
# Before it: fac14597 (ghostlane): a8e95224 + a REALITY hello nobody answers in
# 4 s is retried once with the short hello (issue 166) + the olcRTC client joins
# Jitsi rooms over WebSocket and BOSH (issue 182) + the README.
# Before that: fa91beb6
# (step B + olc-ffi installing the signalling's TLS) + offers numbering their
# lines 0, 1, 2 (1.0.475: MTS Link refused "can't parse mid 7GO") + a peer's
# end logged at info + ghostlane-core's `boring` feature (1.0.476: VK Calls'
# SFU refused str0m's own DTLS hello with handshake_failure) + the VK, MTS and DTLS diagnostics at info (1.0.477: VK's hello unanswered; 1.0.478: no data channel opened, audio only; 1.0.479: none still) + sctp-proto vendored, reading the INIT ACK's Unrecognized Parameter VK's SFU sends (the channels open) + a VK signalling socket silent for 30 s is ended and the call rejoined; the debugging diagnostics back at debug (store build).
GHOSTLANE_CORE_REPO="${GHOSTLANE_CORE_REPO:-ghostlane-project/xray-rust}"
GHOSTLANE_CORE_COMMIT=add3245c407f4749812fec63eb799fd4e4907fa1
GHOSTLANE_CORE_TOOLCHAIN="${GHOSTLANE_CORE_TOOLCHAIN:-1.96.0}"
