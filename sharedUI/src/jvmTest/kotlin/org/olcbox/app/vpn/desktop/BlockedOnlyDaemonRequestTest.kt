package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.runBlocking
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.RuleSets
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.toRules
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

class BlockedOnlyDaemonRequestTest {
    /**
     * A start under "only blocked sites through the tunnel" carries the blocked lists
     * to the root daemon in base64. Daemons before the 4 MiB reader stop reading at
     * 1 MiB (desktopApp/tunneldaemon/main.swift), and a Mac can run one for a while
     * after the app updates: the request has to fit there, with room to spare, until
     * those daemons are gone. A refreshed list that breaks this needs a new plan, not
     * a bigger number here.
     */
    @Test fun aBlockedOnlyStartFitsTheOlderDaemonsReader() = runBlocking {
        val routing = RoutingSettings(RoutingMode.BlockedOnly, tunnelRules = listOf("example.org"))
            .toRules(TunnelDaemonProtocol.RULES_DIR, DirectDns.System)
        val files = RuleSets.selected(routing).associate {
            it.name to Base64.getEncoder().encodeToString(RuleSets.bytes(it))
        }
        val config = SingBoxConfig.buildDesktopTun(corePort = 10810, verifyPort = 10811, routing = routing, bindInterface = "en0")
        val size = TunnelDaemonProtocol.startRequest(config, files).toByteArray().size
        assertTrue(size < 1_048_576 - 64 * 1024, "a BlockedOnly start is $size bytes")
    }
}
