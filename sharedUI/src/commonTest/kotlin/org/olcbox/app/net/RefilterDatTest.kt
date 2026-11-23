package org.olcbox.app.net

import kotlinx.coroutines.test.runTest
import org.olcbox.app.crypt.PlatformCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bundled refilter.dat ([XrayGeodata.REFILTER]): Re:filter's list as the
 * Rust core reads it, read here as the core reads it (crates/xray-config's
 * geodata.rs): a run of entries, each one byte the core skips, a length and a
 * message whose field 1 is its code; a code a rule names is looked up in
 * upper case; a GeoSite's field 2 is a name (type and value), a GeoIP's an
 * address and its prefix.
 */
class RefilterDatTest {
    @Test fun theBundleIsThePinnedMake() = runTest {
        // The hash is what tools/xray-geodata/refilter.py made from the
        // release scripts/rule-sets.lock pins; a bundle whose bytes differ was
        // edited by hand or refreshed without its pin, and either ships a list
        // nobody reviewed.
        val bytes = XrayGeodata.bytes(XrayGeodata.REFILTER)
        assertEquals(XrayGeodata.REFILTER.sha256, PlatformCrypto.sha256(bytes).toHex(), "refilter.dat is not the pinned make")
    }

    @Test fun itHoldsTheNamesAndTheAddressesUnderTheCodesTheRulesName() = runTest {
        val entries = entries(XrayGeodata.bytes(XrayGeodata.REFILTER))
        assertEquals(XrayGeodata.REFILTER_CODES.map { it.uppercase() }, entries.map { it.first })

        val names = entries.single { it.first == XrayGeodata.REFILTER_NAMES.uppercase() }.second.map(::domain)
        assertTrue(names.size in 80_000..200_000, "Re:filter is over 80,000 names and the core takes 200,000 a code, got ${names.size}")
        // Each a name and every name under it, as the .srs the Go cores read
        // has each as a domain and a domain suffix.
        assertTrue(names.all { it.first == ROOT_DOMAIN }, "a name of another type than a domain")
        val values = names.map { it.second }.toSet()
        assertEquals(names.size, values.size, "a name listed twice")
        assertTrue(values.all { it.isNotEmpty() && it == it.lowercase() && it.none(Char::isWhitespace) }, "a name the rules cannot match")
        for (blocked in listOf("linkedin.com", "rutracker.org")) assertTrue(blocked in values, "$blocked is blocked in Russia")
        for (open in listOf("gosuslugi.ru", "ya.ru", "example.com")) assertFalse(open in values, "$open is not")

        val prefixes = entries.single { it.first == XrayGeodata.REFILTER_ADDRESSES.uppercase() }.second.map(::cidr)
        assertTrue(prefixes.size in 20_000..500_000, "Re:filter is over 20,000 prefixes and the core takes 500,000 a code, got ${prefixes.size}")
        for ((address, prefix) in prefixes) {
            assertTrue(address.size == 4 && prefix in 1..32, "not an IPv4 prefix: ${address.toList()}/$prefix")
        }
    }

    private companion object {
        const val ROOT_DOMAIN = 2L

        /** (code, the bodies of its field 2) of each entry, in the file's order. */
        fun entries(file: ByteArray): List<Pair<String, List<ByteArray>>> =
            fields(file).map { (field, entry) ->
                assertEquals(1, field, "a top-level field that is no entry")
                val parts = fields(entry as ByteArray)
                val code = parts.single { it.first == 1 }.second as ByteArray
                code.decodeToString() to parts.filter { it.first == 2 }.map { it.second as ByteArray }
            }

        fun domain(body: ByteArray): Pair<Long, String> {
            val parts = fields(body)
            assertTrue(parts.all { it.first == 1 || it.first == 2 }, "a name with more than a type and a value")
            val type = parts.singleOrNull { it.first == 1 }?.second as Long? ?: 0L
            return type to (parts.single { it.first == 2 }.second as ByteArray).decodeToString()
        }

        fun cidr(body: ByteArray): Pair<ByteArray, Long> {
            val parts = fields(body)
            return (parts.single { it.first == 1 }.second as ByteArray) to (parts.singleOrNull { it.first == 2 }?.second as Long? ?: 0L)
        }

        /** The fields of one protobuf message: a Long for a varint, a ByteArray for a length-delimited field. */
        fun fields(data: ByteArray): List<Pair<Int, Any>> {
            val out = mutableListOf<Pair<Int, Any>>()
            var i = 0
            fun varint(): Long {
                var value = 0L
                var shift = 0
                while (true) {
                    val byte = data[i++].toInt() and 0xff
                    value = value or ((byte and 0x7f).toLong() shl shift)
                    if (byte and 0x80 == 0) return value
                    shift += 7
                }
            }
            while (i < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> out += field to varint()
                    2 -> {
                        val size = varint().toInt()
                        out += field to data.copyOfRange(i, i + size)
                        i += size
                    }
                    else -> error("wire type ${key and 7}, which no geodata field has")
                }
            }
            return out
        }

        fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
