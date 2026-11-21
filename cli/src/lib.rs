//! The Linux CLI's skeleton: the command line, the state files and the
//! control socket, as the Go program in this directory has them.
//!
//! The program is the Go CLI rewritten around one Rust core: the same
//! commands and flags, the same files under the state directory, the same
//! lines on the control socket, so that either program can be put in place
//! of the other. What connects a line (the engine, the tun, the daemon's
//! state machine, the list parser) comes in its own crates under `crates/`.

pub mod commands;
pub mod ipc;
pub mod store;
