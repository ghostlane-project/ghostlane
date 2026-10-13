package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A line's program that the Mac's tunnel helper runs prints into a file of the
 * app's, and the app reads that file as it reads a process's output.
 */
class HelperLineTest {
    @Test fun whatIsAddedWhileTheWriterLivesIsReadAsItComes() {
        val file = Files.createTempFile("followed", ".out")
        val alive = AtomicBoolean(true)
        val lines = CopyOnWriteArrayList<String>()
        val reader = thread {
            FollowedFile(file, alive::get, pollMs = 5).bufferedReader().useLines { lines += it }
        }
        Files.writeString(file, "one\n", StandardOpenOption.APPEND)
        Thread.sleep(60)
        assertEquals(listOf("one"), lines.toList(), "read before the writer is gone")
        Files.writeString(file, "two\nthr", StandardOpenOption.APPEND)
        Thread.sleep(40)
        Files.writeString(file, "ee\n", StandardOpenOption.APPEND)
        alive.set(false)
        reader.join(2_000)
        assertFalse(reader.isAlive, "the end is said once the writer is gone")
        assertEquals(listOf("one", "two", "three"), lines.toList())
        Files.deleteIfExists(file)
    }

    // What a program prints as it dies is what says why it died.
    @Test fun theLastWordsOfAWriterThatIsGoneAreNotLost() {
        val file = Files.createTempFile("followed", ".out")
        Files.writeString(file, "client: failed to connect link\n")
        val read = FollowedFile(file, alive = { false }, pollMs = 5).bufferedReader().readLines()
        assertEquals(listOf("client: failed to connect link"), read)
        Files.deleteIfExists(file)
    }

    @Test fun aClosedFileEndsItsReader() {
        val file = Files.createTempFile("followed", ".out")
        val followed = FollowedFile(file, alive = { true }, pollMs = 5)
        val reader = thread { runCatching { followed.read(ByteArray(8)) } }
        Thread.sleep(30)
        followed.close()
        reader.join(2_000)
        assertFalse(reader.isAlive)
        Files.deleteIfExists(file)
    }

    @Test fun aLineIsEndedAsAnyProcessOfTheUsersIs() {
        val child = ProcessBuilder("sleep", "30").start()
        try {
            val line = HelperLine(child.pid(), "".byteInputStream())
            assertTrue(line.isAlive)
            assertEquals(child.pid(), line.pid())
            assertTrue(line.info().command().isPresent, "the program's path is what a tun is told to let out")
            line.destroy()
            assertEquals(HelperLine.EXIT_NOT_KNOWN, line.waitFor())
            assertFalse(line.isAlive)
            assertEquals(HelperLine.EXIT_NOT_KNOWN, line.exitValue())
        } finally {
            child.destroyForcibly()
        }
    }

    @Test fun aLineThatIsGoneBeforeItIsHeldIsNotAlive() {
        val child = ProcessBuilder("true").start()
        child.waitFor()
        val line = HelperLine(child.pid(), "".byteInputStream())
        assertFalse(line.isAlive)
        assertEquals(HelperLine.EXIT_NOT_KNOWN, line.waitFor())
    }
}
