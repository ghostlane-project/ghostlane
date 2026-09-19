package org.olcbox.app.net

import multiplatform_app.sharedui.generated.resources.Res

/**
 * The Bypass Russia lists in the form Xray takes them: text, one rule per
 * line, in the syntax Xray's routing and dns accept inline (`domain:`,
 * `full:`, `keyword:`, `regexp:` for names; `a.b.c.d/n` for addresses).
 *
 * The same three lists [RuleSets] bundles for sing-box — v2fly's
 * `geosite:category-ru`, `geosite:tld-ru` and `geoip:ru` — trimmed out of
 * v2fly's own releases by `tools/xray-geodata.sh`, which records what it
 * fetched in `tools/xray-geodata.lock`. [XrayGeodataTest] refuses a bundle
 * whose bytes do not hash to the values pinned here.
 *
 * Inline rather than `geosite.dat`/`geoip.dat` next to the core, which is how
 * Xray usually reads them: the core finds those files through the
 * `xray.location.asset` environment variable or beside its executable, and
 * the iOS tunnel extension can set neither — libXray 1.260711 refuses any
 * apiVersion with an `env` map, and the extension bundle is laid out by
 * Xcode. Rules in the config need no file, cost the same memory once parsed,
 * and are checked by `xray -test` like the rest of the document. The IPv6
 * prefixes are left out: the iOS tunnel claims no IPv6 route, so no IPv6
 * destination ever reaches these rules, and they were more bytes than
 * everything else together.
 */
object XrayGeodata {
    class File(val name: String, val sha256: String)

    val GEOSITE_RU = File(
        name = "geosite-category-ru.txt",
        sha256 = "44bef1835244a7862c932d5b8eb6b1a0ed3a155d9b3e4a8bc3cd0e6a385319f9"
    )
    val GEOSITE_TLD_RU = File(
        name = "geosite-tld-ru.txt",
        sha256 = "725c5c554ef946f8e050751231af9486f22d15b839bf4cd90048bfef5a5a3f79"
    )
    val GEOIP_RU = File(
        name = "geoip-ru.txt",
        sha256 = "124350e02f701693ca81709e2e9fccac1bf7681122b2a0ac373517f40ffe55ea"
    )

    val GEOSITE_CATEGORY_IR = File(name = "geosite-category-ir.txt", sha256 = "5d49b6d202b10129bbb4d18d64be7ab661d4d53ca82b28dbfea9e5e6dd8b35d9")
    val GEOSITE_CN = File(name = "geosite-cn.txt", sha256 = "726522a3d53a9461b714a9201c59627058a3ad1e3000d8d47751fc4fc12eb72e")
    val GEOSITE_TLD_CN = File(name = "geosite-tld-cn.txt", sha256 = "4ee36a855e07257c29b4e5ac5b28a75b897db9a942b1bbca43c0a42d963122d3")
    val GEOIP_IR = File(name = "geoip-ir.txt", sha256 = "869c45611bb50ee6128107916e178cbd2b9533bda3c2c158839d34ab0b5119bc")
    val GEOIP_CN = File(name = "geoip-cn.txt", sha256 = "c867901e8c8a2c50ccbf6f58d08e7ebaf718261549254570ccf1ab4b09bb3ece")
    val GEOSITE_CATEGORY_ADS_ALL = File(name = "geosite-category-ads-all.txt", sha256 = "c4439949675bcc8ee90336cd73fb0dcbcf36838bfacaebc2c1293b6a45207462")

    /** Everything the route rules match on. */
    val all: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU, GEOIP_RU)

    /** The name lists, which is what a DNS rule can match. An IP list has no names. */
    val domains: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU)


    /** Bundled data: available offline before the first connection. */
    val bundled: List<File> = all + listOf(GEOSITE_CATEGORY_IR, GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_IR, GEOIP_CN, GEOSITE_CATEGORY_ADS_ALL)

    fun regional(region: String?): List<File> = when (region) {
        null -> emptyList()
        "ru" -> all
        "ir" -> listOf(GEOSITE_CATEGORY_IR, GEOIP_IR)
        "cn" -> listOf(GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_CN)
        else -> error("Unknown routing region: $region")
    }

    fun regionalDomains(region: String?): List<File> = regional(region).filter { it.name.startsWith("geosite-") }

    suspend fun bytes(file: File): ByteArray = Res.readBytes("files/xray/${file.name}")

    /**
     * What [XrayConfig.buildXhttp] puts into the config under Bypass Russia:
     * the name rules of both geosite lists, in order, and the address rules.
     */
    class Lists(val domains: List<String>, val cidrs: List<String>, val blockedDomains: List<String> = emptyList())

    suspend fun lists(region: String? = "ru", blockAds: Boolean = false): Lists = Lists(
        domains = regionalDomains(region).flatMap { parse(bytes(it).decodeToString()) },
        cidrs = regional(region).filter { it.name.startsWith("geoip-") }.flatMap { parse(bytes(it).decodeToString()) },
        blockedDomains = if (blockAds) parse(bytes(GEOSITE_CATEGORY_ADS_ALL).decodeToString()) else emptyList()
    )

    /** One rule per line; blank lines and `#` comments are not rules. */
    fun parse(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
}
