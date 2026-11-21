package org.olcbox.app.vpn.service.engine

import org.olcbox.app.net.TransportProbe

/**
 * Probes of a candidate line (smart connect, failover) while the Rust core
 * runs the session.
 *
 * Not built on the Rust core yet: a probe still starts the candidate's own Go
 * core, alone, beside the session, as it does with the switch off.
 */
internal object RustProbe {
    fun starter(go: EngineSession): TransportProbe.Starter = go.probeStarter
}
