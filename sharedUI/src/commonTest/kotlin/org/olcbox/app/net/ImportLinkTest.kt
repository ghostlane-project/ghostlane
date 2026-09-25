package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportLinkTest {
    private val list = "https://proofkit.org/sub/abc123?transport=auto"

    @Test fun theSchemeLinkCarriesTheListInTheQuery() {
        val link = ImportLink.schemeLink(list)
        assertEquals("proofkit://add?url=https%3A%2F%2Fproofkit.org%2Fsub%2Fabc123%3Ftransport%3Dauto", link)
        assertEquals(list, ImportLink.payloadOf(link))
    }

    @Test fun theWebLinkKeepsTheListInTheFragmentSoNoServerSeesIt() {
        val link = ImportLink.webLink(list)
        assertEquals("https://proofkit.org/add#https%3A%2F%2Fproofkit.org%2Fsub%2Fabc123%3Ftransport%3Dauto", link)
        assertEquals(list, ImportLink.payloadOf(link))
    }

    @Test fun otherShapesPeopleWillTypeStillParse() {
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("PROOFKIT://add#olcrtc%3A%2F%2Fcrypt1%2Fabc"))
        assertEquals("happ://crypt5/xyz", ImportLink.payloadOf("https://www.proofkit.org/add?url=happ%3A%2F%2Fcrypt5%2Fxyz"))
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("  proofkit://add?url=olcrtc%3A%2F%2Fcrypt1%2Fabc \n"))
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("https://proofkit.org/add/#olcrtc%3A%2F%2Fcrypt1%2Fabc"))
    }

    @Test fun aRawListPastedIntoTheFragmentStillComesOut() {
        // Percent-decoding an unencoded payload must not eat it.
        assertEquals("https://proofkit.org/sub/abc?transport=auto", ImportLink.payloadOf("https://proofkit.org/add#https://proofkit.org/sub/abc?transport=auto"))
    }

    @Test fun theGhostlaneSchemeTakesEveryShapeTheOlderOneDoes() {
        assertEquals(list, ImportLink.payloadOf(ImportLink.schemeLink(list).replaceFirst("proofkit://", "ghostlane://")))
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("ghostlane://add#olcrtc%3A%2F%2Fcrypt1%2Fabc"))
        assertEquals("olcrtc://crypt1/abc", ImportLink.payloadOf("GHOSTLANE://import?url=olcrtc%3A%2F%2Fcrypt1%2Fabc"))
    }

    // What Remnawave-style subscription pages write for each client: the list
    // after add/ (or import/), raw with its own query and fragment, or encoded.
    @Test fun thePathShapeProviderPagesWriteCarriesTheListWhole() {
        val raw = "https://sub.example.net/s/Abc%2Bd?format=raw#My VPN"
        assertEquals(raw, ImportLink.payloadOf("ghostlane://add/$raw"))
        assertEquals(raw, ImportLink.payloadOf("ghostlane://import/$raw"))
        assertEquals(raw, ImportLink.payloadOf("proofkit://add/$raw"))
        assertEquals(
            "https://sub.example.net/s/abc?format=raw",
            ImportLink.payloadOf("ghostlane://add/https%3A%2F%2Fsub.example.net%2Fs%2Fabc%3Fformat%3Draw")
        )
    }

    // On the web the path is sent to the server, and the list is a credential.
    @Test fun thePathShapeIsNotTakenOnTheWeb() {
        assertNull(ImportLink.payloadOf("https://proofkit.org/add/https://sub.example.net/s/abc"))
    }

    @Test fun anythingElseIsNotAnImportLink() {
        assertNull(ImportLink.payloadOf("https://proofkit.org/"))
        assertNull(ImportLink.payloadOf("https://proofkit.org/adder#x"))
        assertNull(ImportLink.payloadOf("https://example.org/add#x"))
        assertNull(ImportLink.payloadOf("proofkit://add"))
        assertNull(ImportLink.payloadOf("proofkit://add?url="))
        assertNull(ImportLink.payloadOf("proofkit://other?url=x"))
        assertNull(ImportLink.payloadOf("ghostlane://add"))
        assertNull(ImportLink.payloadOf("ghostlane://add/"))
        assertNull(ImportLink.payloadOf("ghostlane://adder/https://x.example/s"))
        assertNull(ImportLink.payloadOf("ghostlane://imports/https://x.example/s"))
        assertNull(ImportLink.payloadOf(""))
    }
}
