package org.olcbox.app.net

import multiplatform_app.sharedui.generated.resources.Res

/**
 * The bypass lists in the form Xray takes them: text, one rule per line, in
 * the syntax Xray's routing and dns accept inline (`domain:`, `full:`,
 * `keyword:`, `regexp:` for names; `a.b.c.d/n` for addresses). The olcRTC
 * engine reads the same text ([OlcrtcDirectRules]).
 *
 * The Russian three are the lists [RuleSets] bundles for sing-box — v2fly's
 * `geosite:category-ru`, `geosite:tld-ru` and `geoip:ru` — and what Xray
 * inlines on iOS. The Iranian (`category-ir`, `geoip:ir`) and Chinese (`cn`,
 * `tld-cn`, `geoip:cn`) ones are for the olcRTC engine on Android. All are
 * trimmed out of v2fly's own releases by `tools/xray-geodata.sh`, which
 * records what it fetched in `tools/xray-geodata.lock`. [XrayGeodataTest]
 * refuses a bundle whose bytes do not hash to the values pinned here.
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
    val GEOSITE_CATEGORY_IR = File(
        name = "geosite-category-ir.txt",
        sha256 = "93abdb349b529b51d672134fafa5b44bbd646803d1dd49c518cf6b562934d897"
    )
    val GEOIP_IR = File(
        name = "geoip-ir.txt",
        sha256 = "dff619714e5970d8b175cd10365d5cb93180bb12fd13453c9e367001840e0afe"
    )
    val GEOSITE_CN = File(
        name = "geosite-cn.txt",
        sha256 = "71ee69310fb5939b1bf2a37cd6f4b42b63cdfa919ecbcc761108b1b161fa0ba3"
    )
    val GEOSITE_TLD_CN = File(
        name = "geosite-tld-cn.txt",
        sha256 = "eacf4787727233e709ca4858f3640575adb0a21171a75d773c19b56534455097"
    )
    val GEOIP_CN = File(
        name = "geoip-cn.txt",
        sha256 = "419111c68a2cbbf94236126bc4cd2fd30ac43274b6b65a640e0d2a6f0e05f014"
    )

    /** Everything the Russian route rules match on. */
    val all: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU, GEOIP_RU)

    /** The name lists, which is what a DNS rule can match. An IP list has no names. */
    val domains: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU)

    /** Every list the app bundles, for the hash check and the packaging test. */
    val bundled: List<File> = all + listOf(GEOSITE_CATEGORY_IR, GEOIP_IR, GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_CN)

    /** The files of one bypass region (`ru`, `ir`, `cn`), names first. */
    fun regional(region: String): List<File> = when (region) {
        "ru" -> all
        "ir" -> listOf(GEOSITE_CATEGORY_IR, GEOIP_IR)
        "cn" -> listOf(GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_CN)
        else -> error("Unsupported routing region: $region")
    }

    suspend fun bytes(file: File): ByteArray = Res.readBytes("files/xray/${file.name}")

    /**
     * What [XrayConfig.buildXhttp] puts into the config under Bypass Russia:
     * the name rules of both geosite lists, in order, and the address rules.
     */
    class Lists(val domains: List<String>, val cidrs: List<String>)

    /** The Russian lists: what [XrayConfig.buildXhttp] inlines on iOS. */
    suspend fun lists(): Lists = lists("ru")

    suspend fun lists(region: String): Lists {
        val files = regional(region)
        return Lists(
            domains = files.filter { it.name.startsWith("geosite-") }.flatMap { parse(bytes(it).decodeToString()) },
            cidrs = files.filter { it.name.startsWith("geoip-") }.flatMap { parse(bytes(it).decodeToString()) },
        )
    }

    /** One rule per line; blank lines and `#` comments are not rules. */
    fun parse(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
}
