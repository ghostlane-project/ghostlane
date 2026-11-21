use std::io::{self, Write as _};
use std::process::ExitCode;

fn main() -> ExitCode {
    // an argument that is not UTF-8 names nothing this program knows
    let args: Vec<String> = std::env::args_os()
        .skip(1)
        .map(|arg| arg.to_string_lossy().into_owned())
        .collect();
    let code = ghostlane_cli::commands::run(&args, &mut io::stdout(), &mut io::stderr());
    let _ = io::stdout().flush();
    ExitCode::from(code)
}
