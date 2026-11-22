package org.olcbox.app.net.core.lines

import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/** WebTunnel's two shapes, each by the link the iPhone ships it with. */
object WebTunnelFixture : CoreLineFixture {
    override val line: CoreLine = WebTunnel

    override val cases: List<CoreCase> = listOf(
        CoreCase.shipped(IosCoreLine.WebTunnelWs),
        CoreCase.shipped(IosCoreLine.WebTunnelHttpUpgrade),
    )
}
