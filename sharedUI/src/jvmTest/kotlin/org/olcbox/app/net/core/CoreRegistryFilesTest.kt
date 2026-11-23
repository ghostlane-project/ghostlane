package org.olcbox.app.net.core

import org.olcbox.app.net.IosCoreLine
import org.olcbox.app.net.core.CoreRegistry.Rule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only: the registry of kinds ([CoreRegistry]) against the files in the
 * tree. A kind of line that came without its fixture, its surface fragment or
 * a reference config fails here, each under its own name, and so does a
 * reference config no case writes.
 */
class CoreRegistryFilesTest {
    private val references = File("src/jvmTest/resources/reference-configs")
    private val lines = File("src/commonMain/kotlin/org/olcbox/app/net/core/lines")
    private val beside = File("src/commonTest/kotlin/org/olcbox/app/net/core/lines")

    private fun problems(rule: Rule): List<String> {
        val names = references.list().orEmpty().filter { CoreRegistry.REFERENCE_FILE.matches(it) }.map { it.removeSuffix(".json") }.toSet()
        assertTrue(names.isNotEmpty(), "no reference configs under ${references.path}: the test does not run where it thinks")
        return CoreRegistry.problems(references = names).filter { it.rule == rule }.map { it.text }
    }

    private fun kotlinFiles(dir: File, ending: String = ""): List<String> =
        dir.list().orEmpty().filter { it.endsWith("$ending.kt") }.map { it.removeSuffix(".kt") }.sorted()

    @Test fun everyKindHasItsFixture() = assertEquals(emptyList<String>(), problems(Rule.Fixture))

    @Test fun everyKindHasItsSurfaceFragment() = assertEquals(emptyList<String>(), problems(Rule.Fragment))

    @Test fun everyCaseHasItsReferenceConfigs() = assertEquals(emptyList<String>(), problems(Rule.Reference))

    @Test fun everyReferenceConfigIsACases() = assertEquals(emptyList<String>(), problems(Rule.Stray))

    @Test fun everyCaseIsItsKindsOwnLink() = assertEquals(emptyList<String>(), problems(Rule.Case))

    @Test fun everyShapeIsOneKinds() = assertEquals(emptyList<String>(), problems(Rule.Shape))

    /** The lists the build makes are the directories: each file is one object, and no object is listed that has no file. */
    @Test fun theListsAreTheDirectories() {
        assertTrue(kotlinFiles(lines).isNotEmpty(), "no kind under ${lines.path}: the test does not run where it thinks")
        assertEquals(kotlinFiles(lines), CoreLines.all.map { CoreRegistry.name(it) }.sorted())
        assertEquals(kotlinFiles(beside, "Fixture"), CoreFixtures.all.map { it::class.simpleName.orEmpty() }.sorted())
        assertEquals(kotlinFiles(beside, "Surface"), CoreRegistry.fragments.map { it::class.simpleName.orEmpty() }.sorted())
    }

    /** A kind's fixture and fragment are the files named after it. */
    @Test fun aKindsFilesAreNamedAfterIt() {
        for (fixture in CoreFixtures.all) assertEquals("${CoreRegistry.name(fixture.line)}Fixture", fixture::class.simpleName)
        for (fragment in CoreRegistry.fragments) assertEquals("${CoreRegistry.name(fragment.line)}Surface", fragment::class.simpleName)
    }

    /** The iPhone's six lines are shapes of the kinds here, each with the case and the references the iPhone's own tests read. */
    @Test fun theIphonesLinesAreAmongTheShapes() {
        val shapes = CoreLines.all.flatMap { it.shapes }
        for (line in IosCoreLine.entries) {
            assertTrue(line.slug in shapes, "${line.slug} is no kind's shape")
            assertEquals("ios-core-${line.slug}", CoreFixtures.case(line.slug).reference)
            assertTrue(File(references, "ios-core-${line.slug}.json").isFile, line.slug)
            assertTrue(File(references, "bypass-ios-core-${line.slug}.json").isFile, line.slug)
        }
    }
}
