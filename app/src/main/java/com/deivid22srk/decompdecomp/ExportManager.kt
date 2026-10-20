package com.deivid22srk.decompdecomp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import java.io.File
import java.io.IOException

/**
 * Exports a project with decompdecomp's own exporter and zips the tree for the user.
 *
 * The rebuild check needs the game's x86 compiler, which Android cannot run; when it fails the
 * exporter still writes the assembly/source tree, and [decompmobile.export_zip] zips whatever is
 * there. The game image itself is left out of the zip.
 */
object ExportManager {

    fun export(
        context: Context,
        slug: String,
        mode: String,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread({
            try {
                val safe = slug.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val out = File(dir, "$safe-$mode.zip")
                if (out.exists()) out.delete()
                val result = Python.getInstance().getModule("decompmobile")
                    .callAttr("export_zip", slug, mode, out.absolutePath)
                val file = File(result.toString())
                if (!file.isFile || file.length() == 0L) {
                    throw IOException("o arquivo exportado ficou vazio")
                }
                onDone(file)
            } catch (t: Throwable) {
                onError(t.message ?: t.toString())
            }
        }, "project-export").apply {
            isDaemon = true
            start()
        }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartilhar projeto"))
    }

    fun copyTo(context: Context, file: File, target: Uri, onDone: (Boolean) -> Unit) {
        Thread({
            var ok = false
            try {
                context.contentResolver.openOutputStream(target)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
                }
                ok = true
            } catch (t: Throwable) {
                ok = false
            }
            onDone(ok)
        }, "export-save").apply {
            isDaemon = true
            start()
        }
    }
}
