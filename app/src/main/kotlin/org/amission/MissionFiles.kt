package org.amission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import org.amission.msp.MissionFileException
import org.amission.msp.MissionXml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reading and writing mission files through Android's document picker, so the same code works for
 * phone storage, Google Drive, pCloud and any other app that provides documents, plus sharing.
 * Everything here blocks on IO: call it from a background dispatcher.
 */
object MissionFiles {
    private const val MIME = "application/octet-stream" // keeps the ".mission" extension untouched

    fun suggestedName(now: Date = Date()): String =
        "amission-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now) + ".mission"

    fun write(context: Context, uri: Uri, xml: String) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the file for writing")
        out.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
    }

    /** Reads at most [MissionXml.MAX_CHARS] bytes; anything larger is not a mission and is refused. */
    fun read(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file")
        input.use {
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MissionXml.MAX_CHARS) throw MissionFileException("File is too large to be a mission")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    fun displayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
    } catch (e: Exception) {
        uri.lastPathSegment ?: "file"
    }

    /** A chooser that offers the mission as an attachment to any app (Drive, pCloud, mail, messengers...). */
    fun shareChooser(context: Context, xml: String): Intent {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the file being shared is kept
        val file = File(dir, suggestedName()).apply { writeText(xml) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share mission")
    }

    const val CREATE_MIME = MIME
}
