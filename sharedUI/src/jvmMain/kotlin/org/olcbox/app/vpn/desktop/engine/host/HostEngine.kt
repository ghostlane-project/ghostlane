package org.olcbox.app.vpn.desktop.engine.host

import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.vpn.desktop.engine.DesktopEngine
import org.olcbox.app.vpn.desktop.engine.DesktopEngineHost
import org.olcbox.app.vpn.desktop.engine.GoEngines
import org.olcbox.app.vpn.desktop.engine.LineEngine
import org.olcbox.app.vpn.desktop.engine.LineRequest
import org.olcbox.app.vpn.desktop.engine.LineStarted
import java.nio.file.Path

/** The desktop's engine on the Rust core. Not written yet: its line starts nothing. */
internal class HostEngine(
    host: DesktopEngineHost,
    binary: () -> Path = HostAssets::resolve,
    serverAddress: suspend (String, DirectDns?) -> String = HostServerNames::address,
    defaultInterface: () -> String? = { null },
    sessionParent: Path? = null,
) : DesktopEngine by GoEngines(host) {
    private val go = GoEngines(host)

    override val line: LineEngine = object : LineEngine by go.line {
        override suspend fun start(request: LineRequest): LineStarted = error(NOT_YET)
    }
}

private const val NOT_YET = "ghostlane-engine's host is not written yet"

internal object HostSession {
    class Line(val spec: OutboundSpec, val core: String, val bind: String?, val pinned: Boolean)

    const val REFUSED = "ghostlane-engine cannot carry this line"

    suspend fun line(
        request: LineRequest,
        serverAddress: suspend (String, DirectDns?) -> String,
        defaultInterface: () -> String?,
    ): Line = error(NOT_YET)

    fun text(core: String?, room: String? = null, bind: String? = null): String = "{}"
}

internal object HostServerNames {
    suspend fun address(host: String, resolver: DirectDns?): String = error(NOT_YET)

    fun pick(answers: List<String>): String? = null

    internal fun ask(server: String, host: String, port: Int = 53): List<String> = emptyList()
}
