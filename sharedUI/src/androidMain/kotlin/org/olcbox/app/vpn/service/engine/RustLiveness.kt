package org.olcbox.app.vpn.service.engine

import org.olcbox.app.vpn.service.RustCoreBackend

/**
 * What the watchdog and the log are told about the Rust core.
 *
 * Today the engine is alive when it holds a core or an olcRTC client, which a
 * core that died behind its handle still does, and the diagnostics are the Go
 * cores' (no core process runs, and they say so).
 */
internal object RustLiveness {
    fun isRunning(backend: RustCoreBackend?): Boolean =
        backend?.let { it.isCoreRunning() || it.isOlcRunning() } == true

    fun diagnostics(go: EngineSession): String = go.diagnostics()
}
