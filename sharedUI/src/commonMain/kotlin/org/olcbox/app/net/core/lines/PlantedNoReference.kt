package org.olcbox.app.net.core.lines

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.core.CoreLine

/**
 * RED plant, removed by the commit after it: a kind with its fixture and its
 * surface fragment and no reference config. It takes one link, a Trojan link
 * to a documentation address, that nothing else is written for.
 */
internal object PlantedNoReference : CoreLine {
    const val SLUG = "planted-no-reference"
    const val HOST = "192.0.2.77"

    override val shapes: List<String> = listOf(SLUG)

    override fun shape(spec: OutboundSpec): String? = if (spec is OutboundSpec.Trojan && spec.host == HOST) SLUG else null

    override fun refusal(spec: OutboundSpec): String? = null

    override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec = spec

    override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
        put("protocol", "freedom")
    }
}
