package org.olcbox.app.data.datasource

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// A file that is there and cannot be read yet is asked for again, a bounded
// number of times, before it is answered as unreadable: on a phone that is the
// stored bundle between a restart and the first unlock.
class ReadWhenReadableTest {
    /** Text that cannot be read for the first [unreadableReads] reads, with what was asked of it. */
    private class Text(private val unreadableReads: Int) {
        var reads = 0
        var pauses = 0

        fun read(): String? = if (++reads > unreadableReads) "text" else null
    }

    @Test
    fun whatIsReadableAtOnceIsReadOnceWithNoPause() = runTest {
        val text = Text(unreadableReads = 0)

        assertEquals("text", readWhenReadable(pause = { text.pauses++ }) { text.read() })

        assertEquals(1, text.reads)
        assertEquals(0, text.pauses)
    }

    @Test
    fun whatIsReadableOnTheThirdReadTakesThreeReadsAndTwoPauses() = runTest {
        val text = Text(unreadableReads = 2)

        assertEquals("text", readWhenReadable(pause = { text.pauses++ }) { text.read() })

        assertEquals(3, text.reads)
        assertEquals(2, text.pauses)
    }

    // Never readable: none, after exactly the bound, and with no pause after
    // the last read, which nothing would follow.
    @Test
    fun whatIsNeverReadableIsNoneAfterExactlyTheBound() = runTest {
        val text = Text(unreadableReads = Int.MAX_VALUE)

        assertNull(readWhenReadable(attempts = 5, pause = { text.pauses++ }) { text.read() })

        assertEquals(5, text.reads)
        assertEquals(4, text.pauses)
    }

    // The bound as it is when nothing else is asked for: sixty reads half a
    // second apart, read off the test's own clock, which no real time passes on.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theWaitIsSixtyReadsHalfASecondApartUnlessToldOtherwise() = runTest {
        val text = Text(unreadableReads = Int.MAX_VALUE)

        assertNull(readWhenReadable { text.read() })

        assertEquals(60, text.reads)
        assertEquals(59 * 500L, currentTime)
    }

    // ---- a stored file, by its three answers --------------------------------
    // No file, a file that is there and cannot be read yet, and a file whose
    // text is not what was stored are three answers, and the iOS store gave
    // one for all of them: none. With the middle one taken for none, a phone
    // started before its first unlock read as a fresh install's: the intro
    // again, and an empty list of servers. Which answer is which is decided
    // here, in the shared code, so that these tests hold the iOS store to it.

    /** What was asked of a stored file while it was being read: how often its text, how often a pause, and what was decoded. */
    private class Asked {
        var reads = 0
        var pauses = 0
        val decoded = mutableListOf<String>()

        fun decode(text: String): Int {
            decoded += text
            return text.toInt()
        }
    }

    // A fresh install has no file, and waits for nothing: not one read, not
    // one pause, although the read would never answer.
    @Test
    fun noFileIsNoneAtOnceWithNothingReadAndNoPause() = runTest {
        val asked = Asked()

        val stored = readStoredFile(
            path = null,
            read = { asked.reads++; null },
            decode = asked::decode,
            pause = { asked.pauses++ }
        )

        assertNull(stored)
        assertEquals(listOf(0, 0), listOf(asked.reads, asked.pauses))
        assertEquals(emptyList(), asked.decoded)
    }

    // There and unreadable is not none: it is read again, and what it holds
    // once it can be read is the answer.
    @Test
    fun aFileThatCannotBeReadYetIsWaitedForAndDecoded() = runTest {
        val asked = Asked()

        val stored = readStoredFile(
            path = "bundle",
            read = { path -> if (++asked.reads > 2) "42".takeIf { path == "bundle" } else null },
            decode = asked::decode,
            pause = { asked.pauses++ }
        )

        assertEquals(42, stored)
        assertEquals(listOf(3, 2), listOf(asked.reads, asked.pauses))
        assertEquals(listOf("42"), asked.decoded)
    }

    // Text that was read and is not what was stored is none, as it always
    // was, and at once: there is nothing in it to wait for.
    @Test
    fun textThatDoesNotDecodeIsNoneAfterOneReadAndNoPause() = runTest {
        val asked = Asked()

        val stored = readStoredFile(
            path = "bundle",
            read = { asked.reads++; "not a number" },
            decode = asked::decode,
            pause = { asked.pauses++ }
        )

        assertNull(stored)
        assertEquals(listOf(1, 0), listOf(asked.reads, asked.pauses))
        assertEquals(listOf("not a number"), asked.decoded)
    }

    // A file that stays unreadable to the end of the wait is none, as before
    // there was a wait, and nothing is handed to the decoder in its place.
    @Test
    fun aFileThatStaysUnreadableIsNoneAfterTheBound() = runTest {
        val asked = Asked()

        val stored = readStoredFile(
            path = "bundle",
            read = { asked.reads++; null },
            decode = asked::decode,
            attempts = 5,
            pause = { asked.pauses++ }
        )

        assertNull(stored)
        assertEquals(listOf(5, 4), listOf(asked.reads, asked.pauses))
        assertEquals(emptyList(), asked.decoded)
    }

    // Left to itself the wait for a stored file is the one of any other read:
    // sixty reads half a second apart.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aStoredFileIsWaitedForAsLongAsAnyOtherRead() = runTest {
        val asked = Asked()

        assertNull(readStoredFile(path = "bundle", read = { asked.reads++; null }, decode = asked::decode))

        assertEquals(60, asked.reads)
        assertEquals(59 * 500L, currentTime)
    }
}
