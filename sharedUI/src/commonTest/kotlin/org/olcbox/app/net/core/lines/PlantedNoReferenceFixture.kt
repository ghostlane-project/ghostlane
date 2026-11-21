package org.olcbox.app.net.core.lines

import org.olcbox.app.net.core.CoreCase
import org.olcbox.app.net.core.CoreLine
import org.olcbox.app.net.core.CoreLineFixture

/** RED plant, removed by the commit after it: the planted kind's one link, whose reference configs were not added. */
object PlantedNoReferenceFixture : CoreLineFixture {
    override val line: CoreLine = PlantedNoReference

    override val cases: List<CoreCase> = listOf(
        CoreCase(PlantedNoReference.SLUG, "trojan://secret@${PlantedNoReference.HOST}:443#planted"),
    )
}
