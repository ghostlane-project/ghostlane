package org.olcbox.app.ui.components.kit

import org.olcbox.app.AppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PkVersionLineTest {
    @Test
    fun formatsBrandVersionAndBuild() {
        assertEquals(
            "GHOSTLANE · v1.0.209 · 9f3c1ab",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.209", build = "9f3c1ab"))
        )
    }

    @Test
    fun stripsLeadingVIfAlreadyPresent() {
        assertEquals(
            "GHOSTLANE · v2.0.0 · 9f3c1ab",
            pkVersionLine(AppInfo(name = "olcbox", version = "v2.0.0", build = "9f3c1ab"))
        )
    }

    @Test
    fun aBuildWithNoIdLeavesNoDanglingSeparator() {
        assertEquals(
            "GHOSTLANE · v1.0.209",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.209", build = "  "))
        )
    }

    @Test
    fun aDirtyTreeIsPartOfTheBuildId() {
        assertEquals(
            "GHOSTLANE · v1.0.273 · 9f3c1ab*",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.273", build = "9f3c1ab*"))
        )
    }

    @Test
    fun masksTheSubscriptionToken() {
        assertEquals(
            "https://proofkit.org/sub/c0e79f…17e95",
            pkMaskSubscriptionUrl(
                "https://proofkit.org/sub/c0e79fc61f942fe0e7b2032fd273967f4d3b807d1ba11dfd8f9a8d17ad817e95"
            )
        )
    }

    @Test
    fun maskingKeepsShortPathsReadable() {
        // nothing secret to hide, and truncating would only make it harder to read
        assertEquals("https://example.com/sub", pkMaskSubscriptionUrl("https://example.com/sub"))
    }

    @Test
    fun maskingHidesQueryParameters() {
        assertEquals(
            "https://proofkit.org/sub/c0e79f…17e95?…",
            pkMaskSubscriptionUrl(
                "https://proofkit.org/sub/c0e79fc61f942fe0e7b2032fd273967f4d3b807d1ba11dfd8f9a8d17ad817e95?crypt=1"
            )
        )
    }

    @Test
    fun masksATokenThatIsNotTheLastSegment() {
        // Our own subscription URL. The last segment is the literal "olcrtc", so
        // the old mask returned the string untouched and the row ellipsised it —
        // which looks like masking and is not.
        assertEquals(
            "https://proofkit.org/sub/c0e79f…17e95/olcrtc?…",
            pkMaskSubscriptionUrl(
                "https://proofkit.org/sub/c0e79fc61f942fe0e7b2032fd273967f4d3b807d1ba11dfd8f9a8d17ad817e95" +
                    "/olcrtc?crypt=1"
            )
        )
    }

    @Test
    fun maskingNeverTouchesTheHost() {
        // A host is longer than 12 characters often enough that masking by length
        // alone would mangle it into something nobody can identify.
        assertEquals(
            "https://sub.reviewassistant.org/sub/j7k9e/54vnsk…w2y1j?…",
            pkMaskSubscriptionUrl("https://sub.reviewassistant.org/sub/j7k9e/54vnskyn4srw2y1j?x=1")
        )
    }
}
