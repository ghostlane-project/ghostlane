//! What the tests share: a directory of the test's own, the files of the Go
//! tests, a daemon that answers what a test tells it to, and the command
//! line run in place.

#![allow(dead_code)]

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::thread;

use ghostlane_cli::ipc::{Request, Response, Server, Stopper};

static NEXT: AtomicU32 = AtomicU32::new(0);

/// A directory of the test's own, removed when the test is done with it.
pub struct Scratch(PathBuf);

impl Scratch {
    pub fn create() -> Scratch {
        let name = format!(
            "ghostlane-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::SeqCst)
        );
        let dir = std::env::temp_dir().join(name);
        fs::create_dir_all(&dir).expect("a directory for the test");
        Scratch(dir)
    }

    pub fn path(&self) -> &Path {
        &self.0
    }

    pub fn join(&self, name: &str) -> PathBuf {
        self.0.join(name)
    }

    /// The path of a file in it, as an argument.
    pub fn arg(&self, name: &str) -> String {
        self.join(name)
            .to_str()
            .expect("a path that is text")
            .to_string()
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// Where a file of the Go tests is: `testdata/go/<name>`.
pub fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("testdata/go")
        .join(name)
}

pub fn fixture_text(name: &str) -> String {
    let path = fixture(name);
    fs::read_to_string(&path).unwrap_or_else(|err| panic!("{}: {err}", path.display()))
}

/// A state directory with the Go tests' cached lists and last-good file in
/// it: a copy, since a reader may remove what it cannot read.
pub fn state_dir() -> Scratch {
    let scratch = Scratch::create();
    fs::create_dir_all(scratch.join("lists")).expect("the lists directory");
    let lists = fs::read_dir(fixture("lists")).expect("the Go tests' cached lists");
    for entry in lists {
        let entry = entry.expect("a cached list");
        fs::copy(entry.path(), scratch.join("lists").join(entry.file_name()))
            .expect("a copy of a cached list");
    }
    fs::copy(fixture("last-good.json"), scratch.join("last-good.json"))
        .expect("a copy of last-good.json");
    scratch
}

/// A daemon that answers with what a test hands it, on a socket of its own,
/// for as long as the test holds it.
pub struct FakeDaemon {
    scratch: Scratch,
    stopper: Stopper,
}

impl FakeDaemon {
    pub fn start<H>(handler: H) -> FakeDaemon
    where
        H: Fn(Request) -> Response + Send + Sync + 'static,
    {
        let scratch = Scratch::create();
        let server =
            Server::bind(&scratch.join("s.sock"), 0o600).expect("the test's control socket");
        let stopper = server.stopper();
        thread::spawn(move || {
            let _ = server.serve(handler);
        });
        FakeDaemon { scratch, stopper }
    }

    pub fn socket(&self) -> String {
        self.scratch.arg("s.sock")
    }

    pub fn socket_path(&self) -> PathBuf {
        self.scratch.join("s.sock")
    }
}

impl Drop for FakeDaemon {
    fn drop(&mut self) {
        self.stopper.stop();
    }
}

/// What a command line left behind.
#[derive(Debug)]
pub struct Outcome {
    pub code: u8,
    pub stdout: String,
    pub stderr: String,
}

/// Runs a command line in this process, as `main` does.
pub fn ghostlane(args: &[&str]) -> Outcome {
    let args: Vec<String> = args.iter().map(|arg| (*arg).to_string()).collect();
    let (mut stdout, mut stderr) = (Vec::new(), Vec::new());
    let code = ghostlane_cli::commands::run(&args, &mut stdout, &mut stderr);
    Outcome {
        code,
        stdout: String::from_utf8(stdout).expect("what is printed is text"),
        stderr: String::from_utf8(stderr).expect("what is printed is text"),
    }
}
