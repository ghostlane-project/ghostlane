package org.olcbox.app.vpn.desktop

import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Path

/** Which processes carry a line that is not an olcRTC room. */
internal enum class CoreShape {
    /** One sing-box: every transport but XHTTP. */
    SingBox,

    /** One Xray: XHTTP, which sing-box does not speak. */
    Xray,

    /** XHTTP under rules: Xray does not route, so a sing-box that does stands in front of it. */
    XrayBehindSingBox
}

/** The configs of a core line, one for each process it runs. */
internal data class CoreConfigs(val singBox: String?, val xray: String?)

/**
 * How a line is started so that it is the session's endpoint
 * ([SessionEndpoint]), kept apart from the manager so that it can be read and
 * tested without a desktop.
 *
 * With no login and no resolver given, every config here is the one a connect
 * has always written. The first line of a session defines the endpoint, so it
 * fits it as it stands; what is given belongs to a line that comes later, when
 * another location is chosen inside the session.
 */
internal object LineConfigs {
    /** [spec] as the XHTTP line it is, or null: XHTTP is Xray's, everything else sing-box's. */
    fun xhttpOf(spec: OutboundSpec): OutboundSpec.Vless? =
        (spec as? OutboundSpec.Vless)?.takeIf { it.transport is TransportSpec.Xhttp }

    fun shapeOf(spec: OutboundSpec, routing: Routing): CoreShape = when {
        xhttpOf(spec) == null -> CoreShape.SingBox
        routing is Routing.Rules -> CoreShape.XrayBehindSingBox
        else -> CoreShape.Xray
    }

    /**
     * The cores of a line that listens on [port].
     *
     * [login] is demanded by whatever listens there: the one core, or under
     * [CoreShape.XrayBehindSingBox] the front, behind which Xray keeps a port
     * of its own, [xrayPort], and no login, as it always has. The tun's
     * outbound and the system's proxy setting were given the endpoint's login
     * when the session started and can be given no other, so the line asks
     * for exactly that one, and for none where the endpoint has none.
     *
     * [serverResolver] is for sing-box only. Xray asks the system's resolver
     * for its server's name and has no part to tell otherwise.
     */
    fun core(
        spec: OutboundSpec,
        port: Int,
        xrayPort: Int,
        routing: Routing,
        verboseLogs: Boolean,
        login: SocksLogin? = null,
        serverResolver: DirectDns? = null,
        autoDetectInterface: Boolean = false,
        bindInterface: String? = null
    ): CoreConfigs {
        val xhttp = xhttpOf(spec)
            ?: return CoreConfigs(
                singBox = SingBoxConfig.build(
                    spec,
                    socksPort = port,
                    routing = routing,
                    verboseLogs = verboseLogs,
                    login = login,
                    serverResolver = serverResolver,
                    autoDetectInterface = autoDetectInterface
                ),
                xray = null
            )
        if (routing !is Routing.Rules) {
            return CoreConfigs(
                singBox = null,
                xray = XrayConfig.buildXhttp(
                    xhttp,
                    socksPort = port,
                    verboseLogs = verboseLogs,
                    login = login,
                    bindInterface = bindInterface
                )
            )
        }
        return CoreConfigs(
            singBox = SingBoxConfig.buildSocksChain(
                xrayPort,
                socksPort = port,
                routing = routing,
                verboseLogs = verboseLogs,
                login = login
            ),
            xray = XrayConfig.buildXhttp(
                xhttp,
                socksPort = xrayPort,
                verboseLogs = verboseLogs,
                bindInterface = bindInterface
            )
        )
    }

    /**
     * The sing-box front before an olcRTC engine, so that the rules see every
     * connection before the room does. The engine keeps its own port and its
     * own login behind it ([enginePort], [engineUsername], [enginePassword]);
     * the front listens on [port] and demands [login].
     */
    fun front(
        enginePort: Int,
        engineUsername: String,
        enginePassword: String,
        port: Int,
        routing: Routing.Rules,
        verboseLogs: Boolean,
        login: SocksLogin? = null
    ): String = SingBoxConfig.buildSocksChain(
        upstreamPort = enginePort,
        socksPort = port,
        username = engineUsername,
        password = enginePassword,
        routing = routing,
        verboseLogs = verboseLogs,
        login = login
    )

    /**
     * The settings an engine is started with so that the engine itself is
     * [endpoint]. It takes its port and its login from the settings object it
     * is handed and from nowhere else, so it is handed a copy that names the
     * endpoint's. A blank username is no login, which is how a room is entered
     * in a session that started on a core.
     */
    fun engineSettings(settings: DesktopSocksProxySettings, endpoint: SessionEndpoint): DesktopSocksProxySettings =
        settings.copy(
            port = endpoint.port,
            username = endpoint.login?.username.orEmpty(),
            password = endpoint.login?.password.orEmpty()
        )

    /**
     * What a tun's process rule names for [binaries], beside the paths the
     * system reports for the processes already [running].
     *
     * Each binary in both spellings a system may know its process by: the path
     * it is started with, and that path with its links resolved. Which of the
     * two the rule is matched against depends on the system and on how the
     * path was reached, and a rule that names the wrong one lets nothing out
     * and says nothing.
     */
    fun processPaths(running: List<String>, binaries: List<Path>): List<String> =
        (running + binaries.flatMap { binary ->
            listOfNotNull(binary.toString(), runCatching { binary.toRealPath().toString() }.getOrNull())
        }).distinct()
}
