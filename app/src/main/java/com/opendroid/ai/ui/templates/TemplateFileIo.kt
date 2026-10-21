package com.opendroid.ai.ui.templates

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.opendroid.ai.core.templates.TemplateExport
import com.opendroid.ai.core.templates.TemplateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * File plumbing for C6 template transfer, following the same pattern as the
 * A11 chat export: SAF pickers for save/open (no storage permission — scoped
 * storage safe) and a cache-dir copy shared through the already-declared
 * FileProvider for the share sheet.
 */
object TemplateFileIo {

    /**
     * Reads a picked template file as text. Reads at most
     * [TemplateFormat.MAX_PAYLOAD_CHARS] + 1 bytes so an oversized file is
     * stopped here and then rejected by the codec's size check, instead of
     * being loaded whole.
     */
    suspend fun readTemplateText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the chosen file")
        stream.use { input ->
            val buffer = ByteArray(TemplateFormat.MAX_PAYLOAD_CHARS + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }
    }

    /** Writes an export payload to the SAF destination the user picked. */
    suspend fun writeExport(context: Context, uri: Uri, export: TemplateExport) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(export.json.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Could not open the chosen file for writing")
        }
    }

    /**
     * Writes the payload to `cacheDir/exports/` and opens the system share
     * sheet with a FileProvider URI. Returns false (and shares nothing) when
     * the copy could not be written — a failed write must never look like a
     * successful share of an empty file.
     */
    fun shareExport(context: Context, export: TemplateExport): Boolean {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, export.fileName)
            file.writeText(export.json, Charsets.UTF_8)
            if (!file.exists() || file.length() == 0L) return false
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = TemplateFormat.MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share template"))
            true
        } catch (e: Exception) {
            false
        }
    }
}
