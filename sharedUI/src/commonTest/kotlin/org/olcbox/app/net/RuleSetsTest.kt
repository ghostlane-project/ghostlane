package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import org.olcbox.app.crypt.PlatformCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleSetsTest {
    @Test fun bundledFilesAreThePinnedBuilds() = runTest {
        // The hashes are what scripts/update-rule-sets.sh fetched; a bundle whose
        // bytes differ is either an unrecorded refresh or a corrupted resource, and
        // either one ships a bypass list nobody reviewed.
        for (file in RuleSets.bundled) {
            val bytes = RuleSets.bytes(file)
            assertTrue(bytes.isNotEmpty(), "${file.name} is empty")
            assertEquals(file.sha256, PlatformCrypto.sha256(bytes).toHex(), "${file.name} is not the pinned build")
        }
    }

    @Test fun tagsAndNamesAreDistinct() {
        assertEquals(RuleSets.all.size, RuleSets.all.map { it.tag }.toSet().size)
        assertEquals(RuleSets.all.size, RuleSets.all.map { it.name }.toSet().size)
    }

    @Test fun domainListsAreASubsetOfAll() {
        assertTrue(RuleSets.all.containsAll(RuleSets.domains))
        assertTrue(RuleSets.GEOIP_RU !in RuleSets.domains, "an IP list has no names for a DNS rule to match")
    }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
