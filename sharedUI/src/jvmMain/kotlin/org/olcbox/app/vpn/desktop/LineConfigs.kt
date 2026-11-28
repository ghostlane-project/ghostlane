package org.olcbox.app.vpn.desktop

import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.vpn.DesktopSocksProxySettings
import java.nio.file.Path

/**
 * How a line is started so that it is the session's endpoint
 * ([SessionEndpoint]), kept apart from the manager so that it can be read and
 * tested without a desktop.
 *
 * The first line of a session defines the endpoint, so it fits it as it
 * stands; what is given belongs to a line that comes later, when another
 * location is chosen inside the session.
 */
internal object LineConfigs {
    /** [spec] as the XHTTP line it is, or null. */
    fun xhttpOf(spec: OutboundSpec): OutboundSpec.Vless? =
        (spec as? OutboundSpec.Vless)?.takeIf { it.transport is TransportSpec.Xhttp }

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
