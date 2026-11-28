//! A client for the interop driver (scripts/rust-core-interop.py): the
//! command line of the core's own CLI, `run -config <file>`, served through
//! this program's engine child instead.
//!
//! The file is the core.json the app's builder wrote for a link. It is
//! started as the core half of a session of `ghostlane-engine`, the way the
//! CLI starts a line, with the directory of the file as the session's
//! geodata directory (a routing mode's list lies beside the config). The
//! program serves until SIGTERM or SIGINT, then stops the engine; it exits 1
//! when the engine ends by itself or does not start, with the engine's
//! sentence on stderr.
//!
//! `GHOSTLANE_ENGINE` names the engine.

use std::path::Path;
use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use ghostlane_cli::daemon::Engine;
use ghostlane_cli::engine::{self, Options};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let [command, flag, file] = args.as_slice() else {
        eprintln!("usage: engine_client run -config <core.json>");
        return ExitCode::from(2);
    };
    if command != "run" || flag != "-config" {
        eprintln!("usage: engine_client run -config <core.json>");
        return ExitCode::from(2);
    }
    match serve(Path::new(file)) {
        Ok(code) => code,
        Err(why) => {
            eprintln!("engine_client: {why}");
            ExitCode::from(1)
        }
    }
}

fn serve(file: &Path) -> Result<ExitCode, String> {
    let text = std::fs::read_to_string(file).map_err(|e| format!("{}: {e}", file.display()))?;
    let config = std::fs::canonicalize(file).map_err(|e| format!("{}: {e}", file.display()))?;
    let beside = config
        .parent()
        .ok_or("the config has no directory")?
        .to_path_buf();
    let program = engine::locate_here()?;
    let version = engine::check_version(&program)?;
    eprintln!(
        "engine_client: ghostlane-engine {} at {} ({})",
        version.engine, version.pin, version.target
    );

    let term = Arc::new(AtomicBool::new(false));
    for signal in [signal_hook::consts::SIGTERM, signal_hook::consts::SIGINT] {
        signal_hook::flag::register(signal, Arc::clone(&term)).map_err(|e| e.to_string())?;
    }

    let mut options = Options::new(program, beside.join("engine-run"));
    options.log = Arc::new(|line| eprintln!("{line}"));
    let running = engine::start_core(&options, &text, None, Some(&beside), None)?;
    eprintln!("engine_client: serving on {}", running.socks_addr());
    while !term.load(Ordering::SeqCst) {
        if running.state() != "running" {
            let said = running
                .last_end()
                .map_or_else(|| "the engine is gone".to_string(), |e| e.sentence());
            eprintln!("engine_client: {said}");
            return Ok(ExitCode::from(1));
        }
        thread::sleep(Duration::from_millis(50));
    }
    running.stop()?;
    Ok(ExitCode::SUCCESS)
}
