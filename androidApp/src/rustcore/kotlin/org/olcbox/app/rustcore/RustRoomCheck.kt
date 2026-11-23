package org.olcbox.app.rustcore

import org.ghostlane.olcrtc.OlcrtcRuntime
import org.olcbox.app.vpn.RoomCheckBackend
import org.olcbox.app.vpn.RoomRequest

/**
 * The app's room check and probe on ghostlane-core's olcRTC client (the UniFFI
 * bindings), as iOS's SwiftOlcRtcManager does them: each call has a runtime of its
 * own, with the request's handshake profile, and leaves its room before it returns.
 * The SOCKS port is the system's choice (port 0), so no port is picked here and then
 * raced for. The settings are the core's defaults, as gomobile's runtime gave them.
 * The signalling's TLS is put in by the core's first probe, so a check before any
 * start works.
 *
 * Found by name ([org.olcbox.app.vpn.OlcRtcConnectionChecker]); present only in a
 * build with `-PghostlaneCoreDir`, as [RustCoreEngine] is.
 */
object RustRoomCheck : RoomCheckBackend {
    override fun check(request: RoomRequest): Long = probe(request) { runtime ->
        runtime.check(
            request.provider,
            request.transport,
            request.room,
            request.deviceId,
            request.keyHex,
            0L,
            request.timeoutMs,
            request.vp8Fps.toLong(),
            request.vp8Batch.toLong()
        )
    }

    override fun ping(request: RoomRequest): Long = probe(request) { runtime ->
        runtime.ping(
            request.provider,
            request.transport,
            request.room,
            request.deviceId,
            request.keyHex,
            0L,
            request.timeoutMs,
            request.pingUrl,
            request.vp8Fps.toLong(),
            request.vp8Batch.toLong()
        )
    }

    private fun probe(request: RoomRequest, run: (OlcrtcRuntime) -> Long): Long =
        OlcrtcRuntime().let { runtime ->
            try {
                runtime.setDtlsProfile(request.dtlsProfile)
                run(runtime)
            } finally {
                runtime.close()
            }
        }
}
