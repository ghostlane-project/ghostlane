package org.olcbox.app.data.datasource

import kotlinx.coroutines.delay

/** How many times something that is there and cannot be read is asked for before it is answered as unreadable. */
internal const val READ_WHEN_READABLE_ATTEMPTS = 60

/** The pause between two of those reads: sixty reads half a second apart wait for half a minute. */
internal const val READ_WHEN_READABLE_PAUSE_MS = 500L

/**
 * [read] asked until it answers: at most [attempts] times, with [pause]
 * between two reads and none after the last. The first answer that is not
 * null is returned at once; null when every read gave none.
 *
 * For what is there and cannot be read yet, where "cannot be read" must not
 * be taken for "is not there": a phone's files between a restart and the
 * first unlock. Whether it is there at all is the caller's to answer before
 * coming here, so that nothing waits for a file that will never appear.
 */
internal suspend fun <T : Any> readWhenReadable(
    attempts: Int = READ_WHEN_READABLE_ATTEMPTS,
    pause: suspend () -> Unit = { delay(READ_WHEN_READABLE_PAUSE_MS) },
    read: suspend () -> T?
): T? {
    // Read once at the least, whatever the bound was given as.
    val bound = attempts.coerceAtLeast(1)
    for (attempt in 1..bound) {
        read()?.let { return it }
        if (attempt < bound) pause()
    }
    return null
}

/**
 * What a stored file holds, by the three answers a store must keep apart.
 * [path] is the file that is there, or null when there is none; [read] gives
 * its text, or null when the text cannot be read; [decode] makes of the text
 * what was stored, and throws when it is not that.
 *
 * No file is none at once, with nothing read and no waiting: a fresh install
 * waits for nothing.
 *
 * A file that is there and whose text cannot be read is not none. It is read
 * again for a while ([readWhenReadable]), and none is the answer only when
 * the text stays unreadable to the end of that wait. Taken for none at once,
 * as the iOS store took it, a phone started before its first unlock read as
 * a fresh install's.
 *
 * Text that was read and does not decode is none with no waiting: there is
 * nothing in it to wait for.
 */
internal suspend fun <T : Any> readStoredFile(
    path: String?,
    read: suspend (String) -> String?,
    decode: (String) -> T,
    attempts: Int = READ_WHEN_READABLE_ATTEMPTS,
    pause: suspend () -> Unit = { delay(READ_WHEN_READABLE_PAUSE_MS) }
): T? {
    val file = path ?: return null
    val text = readWhenReadable(attempts, pause) { read(file) } ?: return null
    return runCatching { decode(text) }.getOrNull()
}
