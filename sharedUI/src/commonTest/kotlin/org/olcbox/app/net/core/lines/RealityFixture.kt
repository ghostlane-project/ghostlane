package org.olcbox.app.net.core.lines

import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/** Reality's three shapes, each by the link the iPhone ships it with. */
object RealityFixture : CoreLineFixture {
    override val line: CoreLine = Reality

    override val cases: List<CoreCase> = listOf(
        CoreCase.shipped(IosCoreLine.RealityVision),
        CoreCase.shipped(IosCoreLine.RealityGrpc),
        CoreCase.shipped(IosCoreLine.XhttpReality),
    )
}
