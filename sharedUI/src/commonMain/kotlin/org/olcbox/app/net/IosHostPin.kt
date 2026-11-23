package org.olcbox.app.net

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A line's server named by a hostname, which the iPhone's extension resolves
 * outside the tunnel before the core starts (HostPin.swift): the [server]'s
 * name, and the sentence the start fails with when it does not resolve,
 * [unresolved], which names the line as [IosCore.refused] does.
 *
 * Not the core's config, which `net/core` alone writes: the words the app
 * hands the extension beside it, in a place of the document the core
 * type-checks and ignores ([handOver]).
 */
data class IosHostPin(val server: String, val unresolved: String) {
    /**
     * [config], core.json written for [IosCore.HOST_PIN_PLACEHOLDER], with
     * this server named ahead of everything else, in the root's `meta`:
     *
     *     {"meta":{"hostPin":{"server":"<name>","unresolved":"<sentence>"}},"log":...
     *
     * HostPin.swift reads it from the head of the file, without parsing a
     * document that may carry a region's lists, resolves the name outside the
     * tunnel and writes the address over the placeholder. The rest of
     * [config] follows as it was written.
     */
    fun handOver(config: String): String {
        require(config.startsWith("{\"") && config.endsWith("}")) { "core.json is a JSON object with members" }
        require("\"${IosCore.HOST_PIN_PLACEHOLDER}\"" in config) { "core.json is not written for the placeholder" }
        val meta = buildJsonObject {
            putJsonObject("hostPin") {
                put("server", server)
                put("unresolved", unresolved)
            }
        }
        return "{\"meta\":$meta,${config.substring(1)}"
    }
}
