package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import org.olcbox.app.crypt.PlatformCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun russiaByRegionIsWhatXrayInlines() = runTest {
        // iOS inlines lists() into Xray and hands the same rules to the
        // engine; asking by region must not change a byte of either.
        val byRegion = XrayGeodata.lists("ru")
        val plain = XrayGeodata.lists()
        assertEquals(plain.domains, byRegion.domains)
        assertEquals(plain.cidrs, byRegion.cidrs)
    }

    @Test fun iranAndChinaCarryTheirOwnLists() = runTest {
        val ir = XrayGeodata.lists("ir")
        assertTrue(ir.domains.size > 150, "category-ir is over 150 names, got ${ir.domains.size}")
        assertTrue(ir.cidrs.size > 1_500, "geoip:ir is over 1500 IPv4 prefixes, got ${ir.cidrs.size}")
        assertTrue("domain:ir" in ir.domains)
        assertTrue("domain:digikala.com" in ir.domains)
        val cn = XrayGeodata.lists("cn")
        assertTrue(cn.domains.size > 6_000, "cn and tld-cn are over 6000 names, got ${cn.domains.size}")
        assertTrue(cn.cidrs.size > 8_000, "geoip:cn is over 8000 IPv4 prefixes, got ${cn.cidrs.size}")
        assertTrue("domain:cn" in cn.domains)
        assertTrue("domain:baidu.com" in cn.domains)
        val cidr = Regex("""^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$""")
        val prefixes = listOf("domain:", "full:", "keyword:", "regexp:")
        for ((region, lists) in listOf("ir" to ir, "cn" to cn)) {
            for (rule in lists.domains) assertTrue(prefixes.any { rule.startsWith(it) }, "$region: not a name rule: $rule")
            for (rule in lists.cidrs) assertTrue(cidr.matches(rule), "$region: not an IPv4 prefix: $rule")
        }
    }

    @Test fun anUnknownRegionIsAnError() {
        assertFailsWith<IllegalStateException> { XrayGeodata.regional("future") }
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
