package org.olcbox.app.net.core.lines

import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreFixtures
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/**
 * Reality's three shapes, each by the link the iPhone ships it with, and the
 * raw TCP line with the two hellos the core has no REALITY form of (android
 * and 360), which it is given as Chrome's.
 */
object RealityFixture : CoreLineFixture {
    override val line: CoreLine = Reality

    private fun hello(fp: String) =
        "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:443" +
            "?security=reality&encryption=none&pbk=${CoreFixtures.PUBLIC_KEY}" +
            "&sid=ab12cd34&fp=$fp&sni=www.microsoft.com&flow=xtls-rprx-vision&type=tcp#DE-reality-$fp"

    override val cases: List<CoreCase> = listOf(
        CoreCase.shipped(IosCoreLine.RealityVision),
        CoreCase.shipped(IosCoreLine.RealityGrpc),
        CoreCase.shipped(IosCoreLine.XhttpReality),
        CoreCase("reality-android", hello("android")),
        CoreCase("reality-360", hello("360")),
    )
}
