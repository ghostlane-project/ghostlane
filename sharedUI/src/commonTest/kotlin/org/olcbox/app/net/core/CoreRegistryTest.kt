package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.SurfaceCheck
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.CoreRegistry.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The registry of kinds ([CoreRegistry]): the kinds in the tree are whole, and
 * each thing a kind can come without is found. The second half is made of
 * kinds that exist only here, so the proof does not wait for somebody to
 * forget a file.
 */
class CoreRegistryTest {
    /** A kind of line that takes a Trojan link to [host] and writes [protocol]. */
    private class FakeLine(
        override val shapes: List<String>,
        private val host: String,
        private val protocol: String = "freedom",
        private val refuses: String? = null,
    ) : CoreLine {
        override fun shape(spec: OutboundSpec): String? =
            if (spec is OutboundSpec.Trojan && spec.host == host) shapes.firstOrNull() else null
        override fun refusal(spec: OutboundSpec): String? = refuses
        override fun pinHost(spec: OutboundSpec, address: String): OutboundSpec = spec
        override fun JsonObjectBuilder.putOutbound(spec: OutboundSpec) {
            put("protocol", protocol)
        }
    }

    private class FakeFixture(override val line: CoreLine, override val cases: List<CoreCase>) : CoreLineFixture

    private class FakeFragment(
        override val line: CoreLine,
        override val protocols: Map<String, SurfaceCheck> = emptyMap(),
        override val stream: Map<String, SurfaceCheck> = emptyMap(),
    ) : CoreSurfaceFragment

    private val host = "192.0.2.1"
    private fun link(to: String = host) = "trojan://secret@$to:443#fake"

    private val kind = FakeLine(listOf("fake"), host)
    private val fixture = FakeFixture(kind, listOf(CoreCase("fake", link())))
    private val fragment = FakeFragment(kind)
    private val files = setOf("core-fake", "bypass-core-fake")

    private fun rules(
        lines: List<CoreLine> = listOf(kind),
        fixtures: List<CoreLineFixture> = listOf(fixture),
        fragments: List<CoreSurfaceFragment> = listOf(fragment),
        references: Set<String>? = files,
    ): List<Rule> = CoreRegistry.problems(lines, fixtures, fragments, references, extraReferences = emptySet()).map { it.rule }

    @Test fun theKindsInTheTreeAreWhole() {
        // Everything but the reference files, which only the JVM can list (CoreRegistryFilesTest).
        assertEquals(emptyList<String>(), CoreRegistry.problems().map { it.text })
    }

    @Test fun aKindWithAllItsFilesHasNoProblem() {
        assertEquals(emptyList<Rule>(), rules())
    }

    @Test fun aKindWithNoFixtureIsFound() {
        // Its references are then no case's, which is said too.
        assertEquals(listOf(Rule.Fixture, Rule.Stray, Rule.Stray), rules(fixtures = emptyList()))
        assertEquals(listOf(Rule.Fixture), rules(fixtures = emptyList(), references = null))
    }

    @Test fun aKindWithNoSurfaceFragmentIsFound() {
        assertEquals(listOf(Rule.Fragment), rules(fragments = emptyList()))
    }

    @Test fun aKindWithNoReferenceConfigIsFound() {
        assertEquals(listOf(Rule.Reference, Rule.Reference), rules(references = emptySet()))
        assertEquals(listOf(Rule.Reference), rules(references = setOf("core-fake")))
        assertEquals(listOf(Rule.Reference), rules(references = setOf("bypass-core-fake")))
    }

    @Test fun aReferenceConfigOfNoCaseIsFound() {
        assertEquals(listOf(Rule.Stray), rules(references = files + "core-left-behind"))
    }

    @Test fun aReferenceThatIsNoCasesHasToBeThere() {
        val problems = CoreRegistry.problems(listOf(kind), listOf(fixture), listOf(fragment), files, extraReferences = setOf("bypass-core-lists"))
        assertEquals(listOf(Rule.Reference), problems.map { it.rule })
        assertEquals(
            emptyList<Rule>(),
            CoreRegistry.problems(listOf(kind), listOf(fixture), listOf(fragment), files + "bypass-core-lists", extraReferences = setOf("bypass-core-lists"))
                .map { it.rule },
        )
    }

    @Test fun twoFixturesOrTwoFragmentsOfOneKindAreFound() {
        val second = FakeFixture(kind, listOf(CoreCase("fake-again", link())))
        assertEquals(
            listOf(Rule.Fixture),
            rules(fixtures = listOf(fixture, second), references = files + setOf("core-fake-again", "bypass-core-fake-again")),
        )
        assertEquals(listOf(Rule.Fragment), rules(fragments = listOf(fragment, FakeFragment(kind))))
    }

    @Test fun aFixtureOrAFragmentOfNoKindIsFound() {
        val stranger = FakeLine(listOf("stranger"), "192.0.2.2")
        assertEquals(listOf(Rule.Fixture), rules(fixtures = listOf(fixture, FakeFixture(stranger, emptyList()))))
        assertEquals(listOf(Rule.Fragment), rules(fragments = listOf(fragment, FakeFragment(stranger))))
    }

    @Test fun aLinkTwoKindsTakeIsFound() {
        // The second kind takes the first one's link: whichever is asked first would win.
        val other = FakeLine(listOf("other"), host)
        val problems = rules(
            lines = listOf(kind, other),
            fixtures = listOf(fixture, FakeFixture(other, listOf(CoreCase("other", link())))),
            fragments = listOf(fragment, FakeFragment(other)),
            references = files + setOf("core-other", "bypass-core-other"),
        )
        assertEquals(listOf(Rule.Case, Rule.Case), problems.filter { it == Rule.Case })
    }

    @Test fun aCaseThatIsNotItsKindsLinkIsFound() {
        val elsewhere = FakeFixture(kind, listOf(CoreCase("fake", link("192.0.2.9"))))
        assertEquals(listOf(Rule.Case, Rule.Fixture), rules(fixtures = listOf(elsewhere)))
        val noLink = FakeFixture(kind, listOf(CoreCase("fake", "not a link")))
        assertEquals(listOf(Rule.Case, Rule.Fixture), rules(fixtures = listOf(noLink)))
    }

    @Test fun aCaseTheCoreWouldRefuseIsFound() {
        val refusing = FakeLine(listOf("fake"), host, refuses = "it is refused")
        assertEquals(
            listOf(Rule.Case),
            rules(lines = listOf(refusing), fixtures = listOf(FakeFixture(refusing, fixture.cases)), fragments = listOf(FakeFragment(refusing))),
        )
        // A server named by a hostname is refused for every kind.
        val named = FakeLine(listOf("fake"), "server.example.com")
        assertEquals(
            listOf(Rule.Case),
            rules(
                lines = listOf(named),
                fixtures = listOf(FakeFixture(named, listOf(CoreCase("fake", link("server.example.com"))))),
                fragments = listOf(FakeFragment(named)),
            ),
        )
    }

    @Test fun aCaseNameOrAReferenceUsedTwiceIsFound() {
        val twice = FakeFixture(kind, listOf(CoreCase("fake", link()), CoreCase("fake", link())))
        assertEquals(listOf(Rule.Case, Rule.Case), rules(fixtures = listOf(twice)))
        val oneFile = FakeFixture(kind, listOf(CoreCase("fake", link()), CoreCase("fake-two", link(), reference = "core-fake")))
        assertEquals(listOf(Rule.Case), rules(fixtures = listOf(oneFile)))
        // One case's reference under Bypass Russia is the other's under Global.
        val crossed = FakeFixture(kind, listOf(CoreCase("fake", link()), CoreCase("fake-two", link(), reference = "bypass-core-fake")))
        assertEquals(listOf(Rule.Case), rules(fixtures = listOf(crossed), references = files + "bypass-bypass-core-fake"))
    }

    @Test fun aShapeWithNoLinkIsFound() {
        val two = FakeLine(listOf("fake", "fake-second"), host)
        assertEquals(
            listOf(Rule.Fixture),
            rules(lines = listOf(two), fixtures = listOf(FakeFixture(two, fixture.cases)), fragments = listOf(FakeFragment(two))),
        )
    }

    @Test fun aShapeOfTwoKindsOrOfNoneIsFound() {
        val same = FakeLine(listOf("fake"), "192.0.2.2")
        val problems = rules(
            lines = listOf(kind, same),
            fixtures = listOf(fixture, FakeFixture(same, listOf(CoreCase("same", link("192.0.2.2"))))),
            fragments = listOf(fragment, FakeFragment(same)),
            references = files + setOf("core-same", "bypass-core-same"),
        )
        assertEquals(listOf(Rule.Shape), problems)
        val none = FakeLine(emptyList(), "192.0.2.3")
        assertEquals(
            listOf(Rule.Shape),
            rules(lines = listOf(kind, none), fixtures = listOf(fixture, FakeFixture(none, emptyList())), fragments = listOf(fragment, FakeFragment(none))),
        )
    }

    @Test fun aFragmentThatLeavesItsKindsOutboundUncheckedIsFound() {
        // The kind writes a protocol its fragment does not name.
        val trojan = FakeLine(listOf("fake"), host, protocol = "trojan")
        val problems = CoreRegistry.problems(
            listOf(trojan), listOf(FakeFixture(trojan, fixture.cases)), listOf(FakeFragment(trojan)), files, extraReferences = emptySet(),
        )
        assertEquals(listOf(Rule.Fragment), problems.map { it.rule })
        assertEquals(
            "FakeLine's surface fragment does not account for what its case fake writes: " +
                "$.outbounds[0].protocol trojan is not checked here",
            problems.single().text,
        )
        // Named by the fragment, with the fields it writes, it passes.
        val named = FakeFragment(trojan, protocols = mapOf("trojan" to XrayRustSurface.fields(setOf("servers"))))
        assertEquals(emptyList<Rule>(), rules(lines = listOf(trojan), fixtures = listOf(FakeFixture(trojan, fixture.cases)), fragments = listOf(named)))
    }
}
