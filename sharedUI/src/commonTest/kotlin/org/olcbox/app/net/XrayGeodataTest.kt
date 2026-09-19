package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import org.olcbox.app.crypt.PlatformCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrayGeodataTest {
    @Test fun bundledFilesAreThePinnedBuilds() = runTest {
        // The hashes are what tools/xray-geodata.sh produced from the pinned
        // v2fly releases; a bundle whose bytes differ is either an unrecorded
        // refresh or a corrupted resource, and either one ships a bypass list
        // nobody reviewed.
        for (file in XrayGeodata.bundled) {
            val bytes = XrayGeodata.bytes(file)
            assertTrue(bytes.isNotEmpty(), "${file.name} is empty")
            assertEquals(file.sha256, PlatformCrypto.sha256(bytes).toHex(), "${file.name} is not the pinned build")
        }
    }

    @Test fun everyLineIsARuleXrayAcceptsInline() = runTest {
        val lists = XrayGeodata.lists()
        assertTrue(lists.domains.size > 1000, "category-ru alone is over a thousand names, got ${lists.domains.size}")
        assertTrue(lists.cidrs.size > 10_000, "geoip:ru is over ten thousand IPv4 prefixes, got ${lists.cidrs.size}")
        val prefixes = listOf("domain:", "full:", "keyword:", "regexp:")
        for (rule in lists.domains) {
            assertTrue(prefixes.any { rule.startsWith(it) }, "not a name rule: $rule")
        }
        val cidr = Regex("""^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$""")
        for (rule in lists.cidrs) {
            assertTrue(cidr.matches(rule), "not an IPv4 prefix: $rule")
        }
        // The bare TLDs come from their own list: without them sberbank.ru is
        // matched and ozon.ru is not.
        assertTrue("domain:ru" in lists.domains)
        assertTrue("domain:xn--p1ai" in lists.domains)
    }

    @Test fun parseSkipsBlanksAndComments() {
        assertEquals(
            listOf("domain:a.ru", "1.2.3.0/24"),
            XrayGeodata.parse("# a comment\n\n domain:a.ru \n1.2.3.0/24\n")
        )
    }

    @Test fun namesAreASubsetOfAll() {
        assertTrue(XrayGeodata.all.containsAll(XrayGeodata.domains))
        assertTrue(XrayGeodata.GEOIP_RU !in XrayGeodata.domains, "an IP list has no names for a DNS rule to match")
        assertEquals(XrayGeodata.all.size, XrayGeodata.all.map { it.name }.toSet().size)
    }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
