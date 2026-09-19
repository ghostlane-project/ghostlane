package org.olcbox.app.net

import multiplatform_app.sharedui.generated.resources.Res

/**
 * The sing-box rule-sets the app ships, and what the configs call them.
 *
 * Three binary rule-sets from SagerNet's `rule-set` branches: the lists v2fly
 * publishes as `geosite:category-ru`, `geosite:tld-ru` and `geoip:ru`, compiled
 * to sing-box's format. The TLD list is a file of its own because SagerNet's
 * build of category-ru leaves the bare TLDs out despite the `include:tld-ru`
 * in the v2fly source — without it `sberbank.ru` is matched and `ozon.ru` is
 * not, which is not a list anyone would recognise as "Russia".
 *
 * Bundled rather than downloaded. The networks this mode exists for are the
 * ones where github.com answers slowly or not at all, and a list that arrives
 * after the first connection is a first connection with no bypass in it.
 * `scripts/update-rule-sets.sh` refreshes them and records what it fetched in
 * `scripts/rule-sets.lock`; [RuleSetsTest] refuses a bundle whose bytes do not
 * hash to the values pinned here.
 */
object RuleSets {
    class File(val name: String, val tag: String, val sha256: String)

    val GEOSITE_RU = File(
        name = "geosite-category-ru.srs",
        tag = "geosite-ru",
        sha256 = "c36e157adf86edf7b722b51f3acb93bbb2a7f8083932dae29b4b5ef2c1ced870"
    )
    val GEOSITE_TLD_RU = File(
        name = "geosite-tld-ru.srs",
        tag = "geosite-tld-ru",
        sha256 = "ba979268102429754bdbbf306890f91ac4ee0cf1d2f30eb1fff5eba65e0f9e66"
    )
    val GEOIP_RU = File(
        name = "geoip-ru.srs",
        tag = "geoip-ru",
        sha256 = "1a8115af741918ff24b37b87d3c6da21eccabc58f1eec059e461dca8bac16ff7"
    )

    val GEOSITE_CATEGORY_IR = File(name = "geosite-category-ir.srs", tag = "geosite-category-ir", sha256 = "5e4ef5289e0b4f73018854730b2ab049860ec1e87e1a601fdbe976bcb6c669b6")
    val GEOSITE_CN = File(name = "geosite-cn.srs", tag = "geosite-cn", sha256 = "a32d727f1a71b2f9c67627ea4ae489d8d6cfb010f8ec2d6800538493fe5a77ae")
    val GEOSITE_TLD_CN = File(name = "geosite-tld-cn.srs", tag = "geosite-tld-cn", sha256 = "246d4792b8d1959854d485420e4f85ea906f85562860c08a34bfd20b1a33c630")
    val GEOIP_IR = File(name = "geoip-ir.srs", tag = "geoip-ir", sha256 = "c88af3372f71234f6d015d0452ba472f26b0d9d62e82ae5167d066c1df24b8f5")
    val GEOIP_CN = File(name = "geoip-cn.srs", tag = "geoip-cn", sha256 = "ebee603fdf402314b44b9f653cdcf6d9cc9c41e84e2b3515e3123c5c920a93bc")
    val GEOSITE_CATEGORY_ADS_ALL = File(name = "geosite-category-ads-all.srs", tag = "geosite-category-ads-all", sha256 = "d2d51c2e8df3c2b1391136f78ca2c8f69220346d20895b524a587f6face77508")

    /** Everything the route rules match on. */
    val all: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU, GEOIP_RU)

    /** The name lists, which is what a DNS rule can match. An IP list has no names. */
    val domains: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU)

    /**
     * Where iOS keeps them, relative to libbox's working directory.
     *
     * Relative because only the extension knows the App Group's absolute path,
     * and libbox resolves a relative `rule_set.path` against the working path
     * it was set up with (`filemanager.BasePath`). The app writes the files
     * there through the Swift bridge; the config never needs the full path.
     */
    const val IOS_RELATIVE_DIR = "rules"


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

    fun selected(routing: Routing.Rules): List<File> = regional(routing.region) +
        if (routing.blockAds) listOf(GEOSITE_CATEGORY_ADS_ALL) else emptyList()

    suspend fun bytes(file: File): ByteArray = Res.readBytes("files/rules/${file.name}")
}
