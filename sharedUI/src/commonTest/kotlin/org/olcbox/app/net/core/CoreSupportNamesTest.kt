package org.olcbox.app.net.core

import org.olcbox.app.net.RoutingDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `net/core` holds of its own that other files hold too. The twin it
 * had in the sing-box builder is gone, and the answers it was held to stay
 * pinned here.
 */
class CoreSupportNamesTest {
    @Test fun theDirectResolverAddressesAreTheRoutingModelsOwn() {
        // The iPhone's extension replaces the placeholder by its text, whichever writer wrote it.
        assertEquals(RoutingDefaults.DIRECT_DNS_PLACEHOLDER, CoreDns.DIRECT_DNS_PLACEHOLDER)
        assertEquals(RoutingDefaults.DIRECT_DNS_FALLBACK, CoreDns.DIRECT_DNS_FALLBACK)
    }

    @Test fun aLinksFingerprintIsReadAsTheNearestNameTheCoreKnows() {
        val expected = mapOf(
            "chrome" to "chrome",
            "firefox" to "firefox",
            "edge" to "edge",
            "safari" to "safari",
            "360" to "360",
            "qq" to "qq",
            "ios" to "ios",
            "android" to "android",
            "random" to "random",
            "randomized" to "randomized",
            // The chrome variants are the only names of their own the core lacks: they are read as chrome.
            "chrome_pq" to "chrome",
            "Chrome" to "chrome",
            " FIREFOX " to "firefox",
            "hellochrome_133" to "chrome",
            "HelloChrome_Auto" to "chrome",
            "chrome_auto" to "chrome",
            "hellofirefox_148" to "firefox",
            "hellosafari_26_3" to "safari",
            "helloios_14" to "ios",
            "helloedge_85" to "edge",
            "hello360_7_5" to "360",
            "helloqq_11_1" to "qq",
            "helloandroid_11_okhttp" to "android",
            "randomizednoalpn" to "randomized",
            "randomizedalpn" to "randomized",
            "unsafe" to "chrome",
            "nonsense" to "chrome",
            "" to "chrome",
            null to "chrome",
        )
        for ((fp, want) in expected) assertEquals(want, CoreSupport.helloName(fp), "fp=$fp")
    }
}
