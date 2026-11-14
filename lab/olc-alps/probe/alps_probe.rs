//! Lab only (ghostlane lab/olc-alps), copied into an xray-rust checkout as
//! `olc/olc-net/examples/alps_probe.rs`: the olcRTC client's own dials,
//! the Chrome-shaped TLS of `olc_net::shaped_tls`, against the lab's
//! BoringSSL server on loopback.
//!
//!   alps_probe <name> <fetch-port> <websocket-port>
//!
//! A `fetch` (ALPN h2, http/1.1, then one GET in what the server picked)
//! and a WebSocket's own dial (ALPN http/1.1, then one HTTP/1.1 GET). The
//! server's certificate is self-signed, so the check is off
//! (`allow_insecure`); nothing else differs from the product's dial.
//! Prints one RESULT line per dial; exits 1 when either fails.

use std::time::Duration;

use olc_net::fetch::request_h2;
use olc_net::http::HttpRequest;
use olc_net::shaped_tls::{client_config, FINGERPRINT};
use olc_net::stream::Offer;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use xray_transport::TlsConnector;

const LIMIT: Duration = Duration::from_secs(15);

async fn fetch(tls: &TlsConnector, name: &str, port: u16) -> Result<String, String> {
    let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .map_err(|e| format!("tcp: {e}"))?;
    let mut cfg = client_config(name.to_owned(), Offer::Fetch, FINGERPRINT);
    cfg.allow_insecure = true;
    let (stream, alpn) = tls
        .connect_stream_with_alpn(Box::new(tcp), &cfg)
        .await
        .map_err(|e| format!("tls: {e}"))?;
    let alpn = alpn.map(|p| String::from_utf8_lossy(&p).into_owned());
    if alpn.as_deref() != Some("h2") {
        return Err(format!("alpn {alpn:?}, not h2"));
    }
    let authority = format!("{name}:{port}");
    let req = HttpRequest {
        method: "GET",
        host: &authority,
        path: "/",
        headers: &[],
        body: b"",
        max_body: 1024,
        timeout: Duration::from_secs(10),
    };
    let resp = request_h2(stream, &req)
        .await
        .map_err(|e| format!("h2: {e}"))?;
    Ok(format!(
        "alpn=h2 status={} body={:?}",
        resp.status,
        String::from_utf8_lossy(&resp.body)
    ))
}

async fn websocket(tls: &TlsConnector, name: &str, port: u16) -> Result<String, String> {
    let tcp = tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .map_err(|e| format!("tcp: {e}"))?;
    let mut cfg = client_config(name.to_owned(), Offer::WebSocket, FINGERPRINT);
    cfg.allow_insecure = true;
    let (mut stream, alpn) = tls
        .connect_stream_with_alpn(Box::new(tcp), &cfg)
        .await
        .map_err(|e| format!("tls: {e}"))?;
    let alpn = alpn.map(|p| String::from_utf8_lossy(&p).into_owned());
    let head = format!("GET /ws HTTP/1.1\r\nHost: {name}:{port}\r\nConnection: close\r\n\r\n");
    stream
        .write_all(head.as_bytes())
        .await
        .map_err(|e| format!("write: {e}"))?;
    let mut buf = vec![0u8; 512];
    let n = stream
        .read(&mut buf)
        .await
        .map_err(|e| format!("read: {e}"))?;
    let first = String::from_utf8_lossy(&buf[..n]);
    let line = first.lines().next().unwrap_or("").to_owned();
    Ok(format!("alpn={alpn:?} reply={line:?}"))
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 4 {
        eprintln!("usage: alps_probe <name> <fetch-port> <websocket-port>");
        std::process::exit(2);
    }
    let name = &args[1];
    let fetch_port: u16 = args[2].parse().expect("fetch port");
    let ws_port: u16 = args[3].parse().expect("websocket port");
    let tls = TlsConnector::system().expect("connector");
    let mut ok = true;
    for (what, r) in [
        ("fetch", tokio::time::timeout(LIMIT, fetch(&tls, name, fetch_port)).await),
        ("websocket", tokio::time::timeout(LIMIT, websocket(&tls, name, ws_port)).await),
    ] {
        match r {
            Ok(Ok(s)) => println!("RESULT probe {what} ok {s}"),
            Ok(Err(e)) => {
                ok = false;
                println!("RESULT probe {what} FAILED {e}");
            }
            Err(_) => {
                ok = false;
                println!("RESULT probe {what} FAILED timeout");
            }
        }
    }
    std::process::exit(i32::from(!ok));
}
