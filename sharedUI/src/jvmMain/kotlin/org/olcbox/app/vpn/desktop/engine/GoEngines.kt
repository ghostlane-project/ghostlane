package org.olcbox.app.vpn.desktop.engine

import org.olcbox.app.net.DesktopSingBoxController
import org.olcbox.app.net.DesktopXrayController
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.TransportProbe
import org.olcbox.app.vpn.desktop.CoreShape
import org.olcbox.app.vpn.desktop.LineConfigs

/**
 * The engine the desktop has always had: the bundled sing-box and Xray, one
 * process for each config. Moved here from the manager call for call.
 */
internal class GoEngines(host: DesktopEngineHost) : DesktopEngine {
    override val windowsTun: TunCore = GoTunCore(DesktopSingBoxController(onOutput = host.windowsTunOutput))
    override val line: LineEngine = GoLine(host.log)
    override val probes: ProbeEngine = GoProbes()
}

/**
 * sing-box and Xray for vless, hy2 and xhttp locations (the bundled binaries,
 * run as children). The cores' own output goes into the app log: when an
 * outbound fails the core says why on its first lines, and that used to go to
 * a temp file nobody read.
 */
private class GoLine(log: (String) -> Unit) : LineEngine {
    private val singBoxCore = DesktopSingBoxController(
        onOutput = { line -> log(line) }
    )
    private val xrayCore = DesktopXrayController(
        onOutput = { line -> log(line) }
    )

    override suspend fun start(request: LineRequest): LineStarted {
        val spec = request.spec
        val port = request.port
        val xhttp = LineConfigs.xhttpOf(spec)
        // Which processes must be alive once the port answers. A port that
        // answers proves nothing about who answers.
        val shape = LineConfigs.shapeOf(spec, request.routing)
        val alive = shape.alive()
        // Xray does not route; sing-box does, so under rules it goes in front
        // and Xray takes a port of its own behind it.
        val xrayPort = if (shape == CoreShape.XrayBehindSingBox) request.anotherPort(port) else port
        val configs = LineConfigs.core(
            spec,
            port = port,
            xrayPort = xrayPort,
            routing = request.routing,
            verboseLogs = request.verboseLogs,
            login = request.login,
            serverResolver = request.serverResolver,
            autoDetectInterface = request.autoDetectInterface,
            bindInterface = request.bindInterface
        )
        configs.xray?.let { xrayCore.start(it) }
        configs.singBox?.let { singBoxCore.start(it) }
        return LineStarted(
            said = when (shape) {
                CoreShape.XrayBehindSingBox ->
                    "Xray/xhttp core on 127.0.0.1:$xrayPort behind a sing-box front on 127.0.0.1:$port"
                CoreShape.Xray -> "Xray/xhttp core starting on 127.0.0.1:$port"
                CoreShape.SingBox -> "sing-box core (${request.kind}) starting on 127.0.0.1:$port"
            },
            alive = alive,
            exitCode = { if (xhttp != null) xrayCore.exitCodeOrNull() else singBoxCore.exitCodeOrNull() }
        )
    }

    override suspend fun startFront(request: FrontRequest) {
        singBoxCore.start(
            LineConfigs.front(
                enginePort = request.enginePort,
                engineUsername = request.engineUsername,
                enginePassword = request.enginePassword,
                port = request.port,
                routing = request.routing,
                verboseLogs = request.verboseLogs,
                login = request.login
            )
        )
    }

    override fun alive(spec: OutboundSpec, routing: Routing): () -> Boolean =
        LineConfigs.shapeOf(spec, routing).alive()

    override val frontAlive: () -> Boolean = singBoxCore::isRunning

    override fun frontExitCode(): Int? = singBoxCore.exitCodeOrNull()

    override fun anyRunning(): Boolean = singBoxCore.isRunning() || xrayCore.isRunning()

    override fun exitCodeOrNull(): Int? = singBoxCore.exitCodeOrNull() ?: xrayCore.exitCodeOrNull()

    override fun runningProcesses(): List<Process> =
        listOfNotNull(singBoxCore.runningProcess(), xrayCore.runningProcess())

    override fun stopNow() {
        singBoxCore.stopNow()
        xrayCore.stopNow()
    }

    /** Whether every process of a line of this shape runs. */
    private fun CoreShape.alive(): () -> Boolean {
        val singBox = this != CoreShape.Xray
        val xray = this != CoreShape.SingBox
        return { (!singBox || singBoxCore.isRunning()) && (!xray || xrayCore.isRunning()) }
    }
}

private class GoTunCore(private val core: DesktopSingBoxController) : TunCore {
    override suspend fun start(config: String) = core.start(config)
    override fun isRunning(): Boolean = core.isRunning()
    override fun runningProcess(): Process? = core.runningProcess()
    override fun stopNow() = core.stopNow()
}

private class GoProbes : ProbeEngine {
    // Smart connect: the location's core, alone, on its own port and config.
    override val fresh = TransportProbe.Starter { spec, config ->
        if (TransportProbe.usesXray(spec)) {
            val xray = DesktopXrayController()
            xray.start(config)
            object : TransportProbe.Core {
                override fun isRunning(): Boolean = xray.isRunning()
                override suspend fun stop() = xray.stop()
            }
        } else {
            val singBox = DesktopSingBoxController()
            singBox.start(config)
            object : TransportProbe.Core {
                override fun isRunning(): Boolean = singBox.isRunning()
                override suspend fun stop() = singBox.stop()
            }
        }
    }

    private val lookSingBox = DesktopSingBoxController()
    private val lookXray = DesktopXrayController()
    override val kept = TransportProbe.Starter { spec, config ->
        if (TransportProbe.usesXray(spec)) {
            lookXray.start(config)
            object : TransportProbe.Core {
                override fun isRunning(): Boolean = lookXray.isRunning()
                override suspend fun stop() = lookXray.stop()
            }
        } else {
            lookSingBox.start(config)
            object : TransportProbe.Core {
                override fun isRunning(): Boolean = lookSingBox.isRunning()
                override suspend fun stop() = lookSingBox.stop()
            }
        }
    }

    override fun stopKept() {
        lookSingBox.stopNow()
        lookXray.stopNow()
    }
}
