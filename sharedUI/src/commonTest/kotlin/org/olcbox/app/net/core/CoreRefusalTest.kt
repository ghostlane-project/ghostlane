package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.OutboundSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [CoreRefusal] holds what is refused for every kind; what a kind refuses of
 * its own links is asked of that kind, which a kind made up here shows: the
 * shared file answers with words it has never held.
 */
class CoreRefusalTest {
    /** A kind that takes every Trojan link and refuses the ones whose password is `no`. */
    private object Picky : CoreLine {
        const val SENTENCE = "a sentence only this kind knows"
        override val shapes: List<String> = listOf("picky")
        override fun shape(spec: OutboundSpec): String? = if (spec is OutboundSpec.Trojan) "picky" else null
        override fun refusal(spec: OutboundSpec): String? = if ((spec as OutboundSpec.Trojan).password == "no") SENTENCE else null
        override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec = (spec as OutboundSpec.Trojan).copy(host = address)
        override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
            put("protocol", "freedom")
        }
    }

    private fun spec(link: String): OutboundSpec = requireNotNull(LinkParser.parse(link))

    @Test fun aKindsOwnRefusalIsItsFiles() {
        assertEquals(Picky.SENTENCE, CoreRefusal.of(spec("trojan://no@192.0.2.1:443#t"), listOf(Picky)))
        assertNull(CoreRefusal.of(spec("trojan://yes@192.0.2.1:443#t"), listOf(Picky)))
    }

    @Test fun aHostnameIsRefusedForEveryKindBeforeTheKindIsAsked() {
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(spec("trojan://no@server.example.com:443#t"), listOf(Picky)))
        assertEquals(CoreRefusal.HOSTNAME, CoreRefusal.of(spec("trojan://yes@server.example.com:443#t"), listOf(Picky)))
    }

    @Test fun aLinkNoKindWritesIsRefusedWithASentence() {
        val trojan = spec("trojan://yes@192.0.2.1:443#t")
        assertEquals("the Rust core has no Trojan line of this shape", CoreRefusal.of(trojan, emptyList()))
        // And by its shape before its hostname: pinning an address would not help it.
        assertEquals(CoreRefusal.noLine(trojan), CoreRefusal.of(spec("trojan://yes@server.example.com:443#t"), emptyList()))
    }

    @Test fun everyKindInTheTreeAnswersForItsOwnLinks() {
        // Each kind's fixture link is taken as it is, and refused in the kind's own words once it waives certificate checks.
        for (fixture in CoreFixtures.all) for (case in fixture.cases) {
            assertNull(CoreRefusal.of(case.spec()), case.name)
            val waived = when (val spec = case.spec()) {
                is OutboundSpec.Vless -> spec.copy(insecure = true)
                is OutboundSpec.Hysteria2 -> spec.copy(insecure = true, certPinSha256 = null)
                else -> continue
            }
            if (fixture.line.refusal(waived) == null) continue
            assertEquals(fixture.line.refusal(waived), CoreRefusal.of(waived), case.name)
        }
    }
}
