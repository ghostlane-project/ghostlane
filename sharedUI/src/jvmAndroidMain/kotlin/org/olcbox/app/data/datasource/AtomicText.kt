package org.olcbox.app.data.datasource

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces a file's text in one step.
 *
 * `writeText` empties the file and then fills it, so a reader that comes in
 * between sees half a file. For the location bundle that reader gets nothing
 * it can parse, takes the store for empty, and the next thing it saves is that
 * emptiness: every server list gone. Here the text goes into a file beside the
 * target and is renamed over it, which leaves a reader with the old text or
 * the new one, whole.
 *
 * The rename replaces an existing file wherever rename(2) does, which is
 * Android. The desktop has its own step for this
 * ([java.nio.file.Files.move]), because on Windows this one refuses.
 */
object AtomicText {
    fun write(target: File, text: String) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("${target.name} has no directory")
        dir.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                // On the disk before it takes the name: a rename that outlives a
                // power cut must not point at a file that did not.
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("could not replace ${target.name}")
        } finally {
            // Already gone when the rename went through.
            temp.delete()
        }
    }
}
