package org.olcbox.app.net.core.lines

import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/**
 * Hysteria2 by the link the iPhone ships it with: Salamander and a published
 * pin; and a link that waives certificate checks with no pin.
 */
object Hysteria2Fixture : CoreLineFixture {
    override val line: CoreLine = Hysteria2

    override val cases: List<CoreCase> = listOf(
        CoreCase.shipped(IosCoreLine.Hysteria2),
        CoreCase(
            "hysteria2-insecure",
            "hysteria2://PASSWORD123@127.0.0.1:443?sni=www.microsoft.com&obfs=salamander&obfs-password=OBFSPW&insecure=1#RU-hy2-insecure",
        ),
    )
}
