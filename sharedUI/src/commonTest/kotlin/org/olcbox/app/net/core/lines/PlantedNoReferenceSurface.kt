package org.olcbox.app.net.core.lines

import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.core.CoreLine

/** RED plant, removed by the commit after it: the planted kind writes a `freedom` outbound and nothing of its own. */
object PlantedNoReferenceSurface : CoreSurfaceFragment {
    override val line: CoreLine = PlantedNoReference

    override val protocols: Map<String, SurfaceCheck> = emptyMap()

    override val stream: Map<String, SurfaceCheck> = emptyMap()
}
