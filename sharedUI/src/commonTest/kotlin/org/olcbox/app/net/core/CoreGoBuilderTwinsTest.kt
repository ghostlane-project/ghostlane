package org.olcbox.app.net.core

import org.olcbox.app.net.SingBoxConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `net/core` holds of its own that the Go builder also holds: `net/core`
 * names nothing of SingBoxConfig, so that the Go builder can leave, and until
 * it does the two must say the same. This test leaves with SingBoxConfig.
 */
class CoreGoBuilderTwinsTest {
    @Test fun theDirectResolverAddressesAreTheGoBuildersOwn() {
        // The iPhone's extension replaces the placeholder by its text, whichever builder wrote it.
        assertEquals(SingBoxConfig.DIRECT_DNS_PLACEHOLDER, CoreDns.DIRECT_DNS_PLACEHOLDER)
        assertEquals(SingBoxConfig.DIRECT_DNS_FALLBACK, CoreDns.DIRECT_DNS_FALLBACK)
    }

    @Test fun aLinksFingerprintIsReadAsTheGoBuilderReadsIt() {
        val names = listOf(
            null, "", " ", "chrome", "Chrome", " firefox ", "edge", "safari", "360", "qq", "ios", "android",
            "random", "randomized", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq",
            "chrome_pq_psk", "chrome_120", "chromium", "hellochrome_133", "hellochrome_auto", "HelloChrome_Auto",
            "hellofirefox_148", "hellosafari_26_3", "helloios_14", "helloedge_106", "hello360_11_0", "helloqq_11_1",
            "helloandroid_11_okhttp", "randomizednoalpn", "randomizedalpn", "unsafe", "none", "golang",
        )
        for (fp in names) {
            // sing-box's chrome variants are the only names of its own the core lacks: they are read as chrome.
            val go = SingBoxConfig.singBoxFingerprint(fp).let { if (it.startsWith("chrome_")) "chrome" else it }
            assertEquals(go, CoreSupport.helloName(fp), "fp=$fp")
        }
    }
}
