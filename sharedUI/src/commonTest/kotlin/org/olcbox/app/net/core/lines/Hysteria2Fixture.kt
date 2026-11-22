package org.olcbox.app.net.core.lines

import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/** Hysteria2 by the link the iPhone ships it with: Salamander and a published pin. */
object Hysteria2Fixture : CoreLineFixture {
    override val line: CoreLine = Hysteria2

    override val cases: List<CoreCase> = listOf(CoreCase.shipped(IosCoreLine.Hysteria2))
}
