package org.olcbox.app.data.exporter

import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.logs_share_chooser
import multiplatform_app.sharedui.generated.resources.logs_share_opened
import multiplatform_app.sharedui.generated.resources.logs_share_subject
import org.jetbrains.compose.resources.getString
import android.content.Context
import android.content.Intent
import android.net.Uri

class AndroidLogExporter(private val context: Context) : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> {
        val uri = target as? Uri
            ?: return Result.failure(IllegalArgumentException("Android log export target must be a Uri"))

        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Cannot open selected file")
            "Logs saved"
        }
    }

    override suspend fun shareLogs(content: String): Result<String> {
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(Res.string.logs_share_subject))
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, getString(Res.string.logs_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            getString(Res.string.logs_share_opened)
        }
    }
}
