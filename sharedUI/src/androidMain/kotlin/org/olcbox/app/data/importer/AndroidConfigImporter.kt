package org.olcbox.app.data.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader

class AndroidConfigImporter(private val context: Context) : ConfigImporter {
    override fun getFromClipboard(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        // No toast of its own when there is nothing: the import that asked says so,
        // in the user's language, and a toast here said it a second time in English.
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).text?.toString()
        }
        return null
    }

    override fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Ghostlane Locations", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Config copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override suspend fun readTextFromSource(source: Any): String? {
        if (source is Uri) {
            return try {
                context.contentResolver.openInputStream(source)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.readText()
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }
}
