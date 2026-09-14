import Cores
import Foundation
import os

/// Xray-core, running inside the tunnel extension for one transport only.
///
/// sing-box has no xhttp transport — the config builder now refuses to pretend
/// otherwise — so xhttp locations run Xray as a local SOCKS server and sing-box
/// becomes the tun front-end that feeds it. Every other transport is a native
/// sing-box outbound and never loads this.
///
/// libXray exposes a single entry point, `LibXrayInvoke`, taking and returning
/// JSON. That is the whole API surface; the request envelope below is its
/// contract.
enum XrayEngine {

    private static let log = Logger(subsystem: "org.proofkit.app", category: "xray")

    /// libXray rejects anything it does not recognise here, so it is sent
    /// explicitly rather than left to default.
    ///
    /// Still 1, and that decides how geodata reaches the core. libXray
    /// 1.260711 refuses apiVersion 2 and carries no `env` in its request, so
    /// there is no way from here to set `xray.location.asset`, which is how
    /// Xray would find a `geosite.dat`/`geoip.dat`; its other lookup is the
    /// directory of the executable, which is this extension's bundle, laid
    /// out by Xcode. So Bypass Russia's lists travel inside the config
    /// instead, as inline rules (`XrayGeodata`, `XrayConfig.buildXhttp`), and
    /// this bridge never has to know where a file is.
    private static let apiVersion = 1

    private struct Response: Decodable {
        let success: Bool
        let error: String?
    }

    /// The HTTP/2 receive windows of the xhttp client, in bytes, per stream
    /// and per connection.
    ///
    /// x/net's defaults are 4 MB per stream and 1 GB per connection, and a
    /// speed test's download phase fills them: every connection is a stream,
    /// the server sends as far ahead as the window allows, and the tun path
    /// on a phone drains slower than the link. That is the 1.0.424 death -
    /// heap 14 to 29 MB in five seconds, footprint 30 to 49.8, killed. On
    /// the harness with the phone's collector settings, sixteen stalled
    /// downloads took an unbounded client to 104 MB and one with these
    /// windows to 46 (live heap 10 MB instead of 68). The cost is per-stream
    /// rate, window over round trip: 512 KB at 60 ms is ~70 Mbit/s for one
    /// stream, and a speed test spreads over eight or more. The per-connection
    /// window bounds the streams xmux puts on one h2 connection together.
    ///
    /// Read by the Cores build's patched Xray (scripts/patches/
    /// xray-core-h2-window.patch) through Go's own environment: a C setenv
    /// from here is invisible to Go, which copies the environment once, so
    /// the value goes through CoresSetenv, i.e. os.Setenv, before Xray starts.
    static let h2StreamWindow = 524_288
    static let h2ConnWindow = 1_048_576

    /// Starts Xray with a complete config. Throws with libXray's own message,
    /// which names the offending field when a config is wrong.
    static func start(configJSON: String) throws {
        try CoresSetenv("XRAY_XHTTP_H2_STREAM_WINDOW", String(h2StreamWindow))
        try CoresSetenv("XRAY_XHTTP_H2_CONN_WINDOW", String(h2ConnWindow))
        try invoke(method: "runXrayFromJson", payload: ["configJSON": configJSON])
        log.info("xray started, config \(configJSON.count, privacy: .public) bytes, h2 windows \(h2StreamWindow, privacy: .public)/\(h2ConnWindow, privacy: .public)")
    }

    static func stop() {
        // Stopping a core that never started is not an error worth surfacing:
        // this runs on every teardown, including teardowns of tunnels that
        // never involved Xray at all.
        try? invoke(method: "stopXray", payload: [:])
    }

    static var isRunning: Bool {
        guard let data = rawInvoke(method: "getXrayState", payload: [:]),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let payload = object["data"] as? [String: Any]
        else { return false }
        return payload["running"] as? Bool ?? false
    }

    private static func invoke(method: String, payload: [String: String]) throws {
        guard let data = rawInvoke(method: method, payload: payload) else {
            throw failure("xray \(method): no answer")
        }
        let response = try JSONDecoder().decode(Response.self, from: data)
        guard response.success else {
            throw failure("xray \(method): \(response.error ?? "refused without a reason")")
        }
    }

    private static func rawInvoke(method: String, payload: [String: String]) -> Data? {
        let request: [String: Any] = [
            "apiVersion": apiVersion,
            "method": method,
            "payload": payload,
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: request),
              let text = String(data: body, encoding: .utf8)
        else { return nil }
        return LibXrayInvoke(text).data(using: .utf8)
    }

    private static func failure(_ reason: String) -> NSError {
        NSError(domain: "org.proofkit.tunnel", code: 11,
                userInfo: [NSLocalizedDescriptionKey: reason])
    }
}
