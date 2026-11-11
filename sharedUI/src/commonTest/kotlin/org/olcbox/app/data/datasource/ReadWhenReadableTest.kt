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
}
