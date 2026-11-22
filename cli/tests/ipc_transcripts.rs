//! The control socket. Each file under `testdata/go/ipc` is one exchange as
//! the Go program writes it: the request line, then the answer line. The
//! first two are the exchanges of the Go program's own test
//! (`internal/ipc/ipc_test.go`); the others carry every field of its types
//! once. They are replayed both ways: this client against a peer that only
//! knows the bytes, and a peer that only knows the bytes against this daemon.

mod common;

use std::collections::HashMap;
use std::fs;
use std::io::{BufRead as _, BufReader, Read as _, Write as _};
use std::net::Shutdown;
use std::os::unix::fs::PermissionsExt as _;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::thread;

use common::{fixture_text, FakeDaemon, Scratch};
use ghostlane_cli::ipc::{self, CallError, Request, Response, Server};

const TRANSCRIPTS: [&str; 7] = [
    "status",
    "unknown-verb",
    "version",
    "connect-refused",
    "list",
    "status-full",
    "add",
];

/// The request line and the answer line of an exchange, each with its newline.
fn transcript(name: &str) -> (String, String) {
    let text = fixture_text(&format!("ipc/{name}.txt"));
    let mut lines = text.split_inclusive('\n');
    let request = lines.next().expect("a request line").to_string();
    let answer = lines.next().expect("an answer line").to_string();
    assert!(lines.next().is_none(), "{name}: an exchange is two lines");
    (request, answer)
}

fn as_line<T: serde::Serialize>(value: &T) -> String {
    String::from_utf8(gocompat::json::encode_line(value).expect("a value that can be written"))
        .expect("JSON is UTF-8")
}

/// A peer that takes one connection, reads a line and answers with `reply`;
/// the thread's value is the line it read.
fn answer_once(listener: UnixListener, reply: String) -> thread::JoinHandle<String> {
    thread::spawn(move || {
        let (stream, _) = listener.accept().expect("a client");
        let mut request = String::new();
        BufReader::new(&stream)
            .read_line(&mut request)
            .expect("a request");
        (&stream)
            .write_all(reply.as_bytes())
            .expect("the answer is sent");
        request
    })
}

/// Sends `request` as it is and returns all the daemon says before it hangs up.
fn exchange(socket: &Path, request: &[u8], then_close: bool) -> String {
    let mut stream = UnixStream::connect(socket).expect("the daemon's socket");
    stream.write_all(request).expect("the request is sent");
    if then_close {
        stream
            .shutdown(Shutdown::Write)
            .expect("the sending side is closed");
    }
    let mut answer = String::new();
    stream.read_to_string(&mut answer).expect("an answer");
    answer
}

#[test]
fn this_client_writes_the_go_clients_bytes_and_reads_the_go_daemons() {
    for name in TRANSCRIPTS {
        let (request_line, answer_line) = transcript(name);
        let scratch = Scratch::create();
        let socket = scratch.join("s.sock");
        let peer = answer_once(
            UnixListener::bind(&socket).expect("a socket"),
            answer_line.clone(),
        );
        let request: Request =
            serde_json::from_str(&request_line).unwrap_or_else(|err| panic!("{name}: {err}"));
        let response = ipc::call(&socket, &request).unwrap_or_else(|err| panic!("{name}: {err}"));
        assert_eq!(
            peer.join().expect("the peer"),
            request_line,
            "{name}: the request on the wire"
        );
        // nothing of the answer was lost in the reading: written again, it is the same line
        assert_eq!(
            as_line(&response),
            answer_line,
            "{name}: the answer as it was understood"
        );
    }
}

#[test]
fn this_daemon_reads_the_go_clients_bytes_and_writes_the_go_daemons() {
    let mut answers: HashMap<String, Response> = HashMap::new();
    for name in TRANSCRIPTS {
        let (request_line, answer_line) = transcript(name);
        let response =
            serde_json::from_str(&answer_line).unwrap_or_else(|err| panic!("{name}: {err}"));
        answers.insert(request_line, response);
    }
    // the daemon answers by what it understood, written as the Go client writes it
    let daemon = FakeDaemon::start(move |request: Request| {
        let understood = as_line(&request);
        answers
            .get(&understood)
            .cloned()
            .unwrap_or_else(|| ipc::fail("unexpected", &understood))
    });
    for name in TRANSCRIPTS {
        let (request_line, answer_line) = transcript(name);
        assert_eq!(
            exchange(&daemon.socket_path(), request_line.as_bytes(), false),
            answer_line,
            "{name}"
        );
        // a client that sends no newline, as nothing in the protocol asks for one
        let bare = request_line.trim_end_matches('\n');
        assert_eq!(
            exchange(&daemon.socket_path(), bare.as_bytes(), false),
            answer_line,
            "{name}, no newline"
        );
    }
}

/// `TestRoundTrip` of the Go program, with this client and this daemon.
#[test]
fn a_round_trip() {
    let daemon = FakeDaemon::start(|request: Request| {
        if request.verb == "status" {
            let status = ipc::Status {
                state: "up".to_string(),
                selector: request.selector,
                ..ipc::Status::default()
            };
            return Response {
                ok: true,
                status: Some(status),
                ..Response::default()
            };
        }
        ipc::fail("bad_verb", &format!("unknown verb {}", request.verb))
    });
    let asked = Request {
        verb: "status".to_string(),
        selector: "DE".to_string(),
        ..Request::default()
    };
    let response = ipc::call(&daemon.socket_path(), &asked).expect("an answer");
    let status = response.status.expect("a status");
    assert!(response.ok);
    assert_eq!(
        (status.state.as_str(), status.selector.as_str()),
        ("up", "DE")
    );
    let asked = Request {
        verb: "nope".to_string(),
        ..Request::default()
    };
    let response = ipc::call(&daemon.socket_path(), &asked).expect("an answer");
    assert!(!response.ok);
    assert_eq!(
        (response.error.as_str(), response.message.as_str()),
        ("bad_verb", "unknown verb nope")
    );
}

#[test]
fn no_daemon_is_said_in_the_go_programs_words() {
    let scratch = Scratch::create();
    let missing = scratch.join("missing.sock");
    let asked = Request {
        verb: "status".to_string(),
        ..Request::default()
    };
    let refused = ipc::call(&missing, &asked).expect_err("nobody listens");
    assert!(matches!(refused, CallError::DaemonDown(_)), "{refused:?}");
    let want = format!(
        "the ghostlane daemon is not running (systemctl start ghostlane, or run `ghostlane run` where there is no systemd): dial unix {}: connect: no such file or directory",
        missing.display()
    );
    assert_eq!(refused.to_string(), want);
    // a socket file nobody listens on any more
    drop(UnixListener::bind(&missing).expect("a socket"));
    let refused = ipc::call(&missing, &asked).expect_err("nobody listens");
    assert!(
        refused
            .to_string()
            .ends_with(": connect: connection refused"),
        "{refused}"
    );
}

#[test]
fn a_daemon_that_hangs_up_answered_nothing() {
    let scratch = Scratch::create();
    let socket = scratch.join("s.sock");
    let listener = UnixListener::bind(&socket).expect("a socket");
    let peer = thread::spawn(move || {
        let (stream, _) = listener.accept().expect("a client");
        let mut request = String::new();
        BufReader::new(&stream)
            .read_line(&mut request)
            .expect("a request");
    });
    let asked = Request {
        verb: "status".to_string(),
        ..Request::default()
    };
    let refused = ipc::call(&socket, &asked).expect_err("no answer");
    peer.join().expect("the peer");
    assert!(matches!(refused, CallError::Exchange(_)), "{refused:?}");
    assert_eq!(refused.to_string(), "daemon answered nothing: EOF");
}

#[test]
fn what_is_not_a_request_is_answered_as_a_bad_request() {
    let daemon = FakeDaemon::start(|_| Response {
        ok: true,
        ..Response::default()
    });
    let socket = daemon.socket_path();
    // the two the Go daemon words the same way: nothing at all, and a value cut short
    assert_eq!(
        exchange(&socket, b"", true),
        "{\"ok\":false,\"error\":\"bad_request\",\"message\":\"EOF\"}\n"
    );
    assert_eq!(
        exchange(&socket, b" \n", true),
        "{\"ok\":false,\"error\":\"bad_request\",\"message\":\"EOF\"}\n"
    );
    assert_eq!(
        exchange(&socket, b"{\"verb\":\"sta", true),
        "{\"ok\":false,\"error\":\"bad_request\",\"message\":\"unexpected EOF\"}\n"
    );
    for wrong in ["{not json}\n", "[1]\n", "{\"verb\":5}\n"] {
        let answer = exchange(&socket, wrong.as_bytes(), false);
        assert!(
            answer.starts_with("{\"ok\":false,\"error\":\"bad_request\",\"message\":\"")
                && answer.ends_with("\"}\n"),
            "{wrong:?}: {answer}"
        );
    }
    // a key this program does not know is passed over, as the Go program passes it over
    assert_eq!(
        exchange(
            &socket,
            b"{\"verb\":\"status\",\"new_key\":[1,{\"a\":null}]}\n",
            false
        ),
        "{\"ok\":true}\n"
    );
}

#[test]
fn an_answer_with_keys_of_a_later_version_is_read() {
    let scratch = Scratch::create();
    let socket = scratch.join("s.sock");
    let reply = "{\"ok\":true,\"later\":{\"a\":[1]},\"status\":{\"state\":\"idle\",\"later\":1},\"status_note\":null}\n";
    let peer = answer_once(
        UnixListener::bind(&socket).expect("a socket"),
        reply.to_string(),
    );
    let asked = Request {
        verb: "status".to_string(),
        ..Request::default()
    };
    let response = ipc::call(&socket, &asked).expect("an answer");
    assert_eq!(peer.join().expect("the peer"), "{\"verb\":\"status\"}\n");
    assert_eq!(
        response.status.map(|status| status.state),
        Some("idle".to_string())
    );
}

#[test]
fn the_socket_has_the_mode_it_is_given_and_replaces_a_dead_one() {
    let scratch = Scratch::create();
    let socket = scratch.join("run").join("ghostlane").join("s.sock");
    let first = Server::bind(&socket, 0o660).expect("the socket, its directories made");
    let mode = fs::metadata(&socket)
        .expect("the socket file")
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o660);
    drop(first);
    // the file of a daemon that is gone is in nobody's way
    assert!(socket.exists());
    let second = Server::bind(&socket, 0o600).expect("the socket again");
    let mode = fs::metadata(&socket)
        .expect("the socket file")
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o600);
    let stopper = second.stopper();
    let serving = thread::spawn(move || {
        second.serve(|_| Response {
            ok: true,
            ..Response::default()
        })
    });
    assert_eq!(
        exchange(&socket, b"{\"verb\":\"x\"}\n", false),
        "{\"ok\":true}\n"
    );
    stopper.stop();
    serving
        .join()
        .expect("the serving thread")
        .expect("a clean stop");
    assert!(
        !socket.exists(),
        "the socket file is removed when the daemon stops"
    );
}

#[test]
fn a_socket_that_cannot_be_made_says_why() {
    let scratch = Scratch::create();
    fs::write(scratch.join("blocker"), "").expect("a file");
    let refused = Server::bind(&scratch.join("blocker").join("s.sock"), 0o660)
        .err()
        .expect("a file is no directory");
    assert_eq!(
        refused.to_string(),
        format!(
            "mkdir {}: not a directory",
            scratch.join("blocker").display()
        )
    );
}
