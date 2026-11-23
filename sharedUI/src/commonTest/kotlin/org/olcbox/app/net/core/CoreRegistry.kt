package org.olcbox.app.net.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.olcbox.app.net.CoreSurfaceFragment
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.XrayRustSurface
import org.olcbox.app.net.core.index.coreSurfaceIndex

/**
 * What a kind of line is made of, and whether every kind has all of it.
 *
 * A kind is files and nothing else, so that several can be added at a time
 * without one of them editing a list another edits:
 *
 *  - `net/core/lines/<Kind>.kt` (commonMain): `object <Kind> : CoreLine`;
 *  - `net/core/lines/<Kind>Fixture.kt` (commonTest): `object <Kind>Fixture :
 *    CoreLineFixture`, a link for each shape the kind writes;
 *  - `net/core/lines/<Kind>Surface.kt` (commonTest): `object <Kind>Surface :
 *    CoreSurfaceFragment`, what the kind's outbound may hold;
 *  - for each of the fixture's cases, `reference-configs/<reference>.json`
 *    and `bypass-<reference>.json` (jvmTest resources), the writer's config
 *    as the core's parser was asked about it.
 *
 * The build lists the three kinds of object from the two directories, and
 * [problems] says what is missing: a kind that came without its fixture, its
 * fragment or a reference fails, and so does a reference left behind.
 */
object CoreRegistry {
    /**
     * The one reference that is no case's: the iPhone's Reality line under
     * Bypass Russia with the bundled lists and not the stand-ins, so that a
     * refresh of the lists is asked about too.
     */
    const val BUNDLED_LISTS = "bypass-ios-core-bundled-lists"

    /** What a reference config's file is named like: the parse script and the PR scope read the same pattern. */
    val REFERENCE_FILE = Regex(".*core-.*\\.json")

    enum class Rule {
        /** A kind's shapes: it names none, or one that another kind names. */
        Shape,

        /** A kind's fixture: none, more than one, one of no kind, or a shape with no link. */
        Fixture,

        /** A kind's surface fragment: none, more than one, one of no kind, or one that leaves the kind's own outbound unchecked. */
        Fragment,

        /** A fixture case: a name used twice, a link that is not the kind's own or that the core would refuse. */
        Case,

        /** A case with no reference config. */
        Reference,

        /** A reference config that is no case's. */
        Stray,
    }

    data class Problem(val rule: Rule, val text: String) {
        override fun toString(): String = text
    }

    /** Every kind's surface fragment. */
    val fragments: List<CoreSurfaceFragment> get() = coreSurfaceIndex

    /** A kind by the name of its file. */
    fun name(line: CoreLine): String = line::class.simpleName ?: line.shapes.joinToString("/")

    private fun count(n: Int, what: String): String = if (n == 0) "no $what" else "$n ${what}s"

    /**
     * Everything the registry of [lines], [fixtures] and [fragments] lacks.
     * [references] is the name of every reference config there is, without
     * `.json`; null where the files cannot be listed (anywhere but the JVM),
     * and then the files are not asked about. [extraReferences] are the
     * references that are no case's.
     */
    fun problems(
        lines: List<CoreLine> = CoreLines.all,
        fixtures: List<CoreLineFixture> = CoreFixtures.all,
        fragments: List<CoreSurfaceFragment> = this.fragments,
        references: Set<String>? = null,
        extraReferences: Set<String> = setOf(BUNDLED_LISTS),
    ): List<Problem> {
        val found = mutableListOf<Problem>()
        fun say(rule: Rule, text: String) {
            found += Problem(rule, text)
        }

        for (line in lines) {
            if (line.shapes.isEmpty()) say(Rule.Shape, "${name(line)} names no shape")
        }
        lines.flatMap { line -> line.shapes.map { it to line } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .forEach { (slug, owners) ->
                say(Rule.Shape, "the shape $slug is named by ${owners.joinToString { name(it) }}: a shape is one kind's")
            }

        for (line in lines) {
            val ownFixtures = fixtures.count { it.line === line }
            if (ownFixtures != 1) {
                say(Rule.Fixture, "${name(line)} has ${count(ownFixtures, "fixture")}: a kind has one, lines/${name(line)}Fixture.kt beside the tests")
            }
            val ownFragments = fragments.count { it.line === line }
            if (ownFragments != 1) {
                say(Rule.Fragment, "${name(line)} has ${count(ownFragments, "surface fragment")}: a kind has one, lines/${name(line)}Surface.kt beside the tests")
            }
        }
        for (fixture in fixtures) {
            if (lines.none { it === fixture.line }) say(Rule.Fixture, "the fixture ${fixture::class.simpleName} is of a kind that is not under lines/")
        }
        for (fragment in fragments) {
            if (lines.none { it === fragment.line }) say(Rule.Fragment, "the surface fragment ${fragment::class.simpleName} is of a kind that is not under lines/")
        }

        val cases = fixtures.flatMap { fixture -> fixture.cases.map { fixture to it } }
        cases.groupBy { it.second.name }.filterValues { it.size > 1 }.keys.forEach {
            say(Rule.Case, "the case name $it is used more than once")
        }
        val named = cases.map { it.second.reference }
        named.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            say(Rule.Case, "the reference config $it.json is named by more than one case")
        }
        named.toSet().intersect(cases.map { it.second.bypassReference }.toSet()).forEach {
            say(Rule.Case, "the reference config $it.json is one case's under Global and another's under Bypass Russia")
        }
        for ((fixture, case) in cases) {
            val line = fixture.line
            val spec = LinkParser.parse(case.link)
            if (spec == null) {
                say(Rule.Case, "the case ${case.name} of ${name(line)} is no link the app reads")
                continue
            }
            val takers = lines.filter { it.shape(spec) != null }
            if (takers.size != 1 || takers[0] !== line) {
                val who = if (takers.isEmpty()) "no kind" else takers.joinToString { name(it) }
                say(Rule.Case, "the case ${case.name} is ${name(line)}'s and its link is taken by $who: a link is one kind's, whatever the order they are asked in")
                continue
            }
            val shape = line.shape(spec)
            if (shape !in line.shapes) say(Rule.Shape, "${name(line)} answers the shape $shape for its case ${case.name} and does not name it among its shapes")
            val refusal = CoreRefusal.of(spec, lines)
            if (refusal != null) {
                say(Rule.Case, "the case ${case.name} of ${name(line)} is refused ($refusal): a reference config is of a link the core takes")
                continue
            }
            // The kind's own fragment alone, on the kind's own outbound: it leans on no other kind's.
            val fragment = fragments.singleOrNull { it.line === line } ?: continue
            val outbound = buildJsonObject { with(line) { putOutbound(spec) } }
            val unchecked = XrayRustSurface.violations(JsonObject(mapOf("outbounds" to JsonArray(listOf(outbound)))), listOf(fragment))
            if (unchecked.isNotEmpty()) {
                say(Rule.Fragment, "${name(line)}'s surface fragment does not account for what its case ${case.name} writes: ${unchecked.joinToString()}")
            }
        }
        for (line in lines) {
            val fixture = fixtures.singleOrNull { it.line === line } ?: continue
            val written = fixture.cases.mapNotNull { case -> LinkParser.parse(case.link)?.let { line.shape(it) } }.toSet()
            for (shape in line.shapes - written) {
                say(Rule.Fixture, "${name(line)} writes the shape $shape and its fixture has no link of it")
            }
        }

        if (references != null) {
            val wanted = cases.flatMap { (fixture, case) ->
                listOf(case.reference, case.bypassReference).map { it to "the case ${case.name} of ${name(fixture.line)}" }
            } + extraReferences.map { it to "every build" }
            for ((reference, owner) in wanted) {
                if (reference !in references) {
                    say(Rule.Reference, "no reference config $reference.json for $owner: copy the writer's dump and ask the core's parser about it")
                }
            }
            val known = wanted.map { it.first }.toSet()
            for (stray in (references - known).sorted()) {
                say(Rule.Stray, "the reference config $stray.json is no fixture case's: the core is asked about a config nothing writes")
            }
        }
        return found
    }
}
