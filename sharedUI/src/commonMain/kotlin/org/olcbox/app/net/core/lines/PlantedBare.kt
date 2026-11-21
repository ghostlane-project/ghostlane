package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.core.CoreLine

/**
 * RED plant, removed by the commit after it: a kind that came as its own file
 * alone, with no fixture and no surface fragment. It takes no link.
 */
internal object PlantedBare : CoreLine {
    override val shapes: List<String> = listOf("planted-bare")

    override fun shape(spec: OutboundSpec): String? = null

    override fun refusal(spec: OutboundSpec): String? = null

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec = spec

    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        put("protocol", "freedom")
    }
}
