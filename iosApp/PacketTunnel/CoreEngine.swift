import Foundation
import XrayRust
import os

/// xray-rust's core in ghostlane-core, running one line from core.json behind
/// hev-socks5-tunnel: Reality (Vision), Reality gRPC, WebTunnel, Hysteria2 and
/// XHTTP (`XrayConfig.buildIosCore`). It replaced sing-box and Go's Xray in
/// step C of ghostlane-rust-core.
///
/// The C ABI is xray-ffi's (`xray_ffi.h`, module XrayRust), in the same static
/// library as the olcRTC client, on the one Tokio runtime (one worker on iOS)
/// and the one byte budget `MemoryRelief` moves. A handle takes exactly one
/// config, so every start makes a new one and every stop frees it.
enum CoreEngine {

    private static let log = Logger(subsystem: "org.proofkit.app", category: "core")

    /// The running core. Touched by the provider's start and stop and by its
    /// path monitor, which the provider serialises (one tunnel per process).
    nonisolated(unsafe) private static var handle: OpaquePointer?

    /// Starts the core with a complete config. Throws with the core's own
    /// message, which names the offending field when a config is wrong.
    static func start(configJSON: String) throws {
        stop()
        var error: OpaquePointer?
        guard let core = xray_core_new(&error) else {
            throw failure("xray_core_new", error)
        }
        // Before the config is loaded: every socket the core opens, the
        // line's own and its DNS forwards, is pinned to the physical
        // interface before it connects, or it would loop into our own tun.
        // Nonzero is "protected".
        let protect: XraySocketProtectCallback = { fd, _ in
            TunPlatform.pinToPhysicalInterface(fd) ? 1 : 0
        }
        guard xray_core_set_socket_protect_callback(core, protect, nil, &error) == XRAY_STATUS_OK else {
            xray_core_free(core)
            throw failure("protect callback", error)
        }
        let logLine: XrayLogCallback = { level, line, length, _ in
            guard let line else { return }
            let text = String(decoding: UnsafeRawBufferPointer(start: line, count: length), as: UTF8.self)
            if level <= Int32(XRAY_LOG_LEVEL_WARNING.rawValue) {
                CoreEngine.log.error("\(text, privacy: .public)")
            } else {
                CoreEngine.log.info("\(text, privacy: .public)")
            }
        }
        let release: XrayLogRelease = { _ in }
        if xray_core_set_log_callback(core, logLine, release, nil, &error) != XRAY_STATUS_OK {
            // Not fatal: a core without its log still carries the line.
            log.error("core log unavailable: \(message(error), privacy: .public)")
            freeError(&error)
        }
        let loaded = configJSON.withCString { xray_core_load_config_json(core, $0, &error) }
        guard loaded == XRAY_STATUS_OK else {
            xray_core_free(core)
            throw failure("core.json", error)
        }
        guard xray_core_start(core, &error) == XRAY_STATUS_OK else {
            xray_core_free(core)
            throw failure("start", error)
        }
        handle = core
        log.info("core started, config \(configJSON.utf8.count, privacy: .public) bytes")
    }

    /// Stops and frees the core, if one runs. Runs on every teardown,
    /// including those of tunnels that never started it.
    static func stop() {
        guard let core = handle else { return }
        handle = nil
        var error: OpaquePointer?
        if xray_core_stop(core, &error) != XRAY_STATUS_OK {
            log.error("core stop: \(message(error), privacy: .public)")
            freeError(&error)
        }
        xray_core_free(core)
    }

    static var isRunning: Bool { handle != nil }

    /// The network moved under the tunnel: the core closes what it holds on
    /// the old path and dials the next connections afresh through the pin.
    /// Returns the connections it closed.
    @discardableResult
    static func resetNetwork() -> UInt64 {
        guard let core = handle else { return 0 }
        var closed: UInt64 = 0
        var error: OpaquePointer?
        if xray_core_reset_network(core, &closed, &error) != XRAY_STATUS_OK {
            log.error("core reset-network: \(message(error), privacy: .public)")
            freeError(&error)
        }
        return closed
    }

    private static func message(_ error: OpaquePointer?) -> String {
        guard let error, let text = xray_error_message(error) else { return "no reason given" }
        return String(cString: text)
    }

    private static func freeError(_ error: inout OpaquePointer?) {
        if let e = error { xray_error_free(e) }
        error = nil
    }

    private static func failure(_ what: String, _ error: OpaquePointer?) -> NSError {
        var owned = error
        let text = "core \(what): \(message(error))"
        freeError(&owned)
        return NSError(domain: "org.proofkit.tunnel", code: 11,
                       userInfo: [NSLocalizedDescriptionKey: text])
    }
}
