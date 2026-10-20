package com.deivid22srk.decompdecomp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Streams a ROM chosen with the Storage Access Framework into decompdecomp's own import endpoint.
 *
 * The file is never loaded in memory: bytes go from the content Uri to the local HTTP socket in
 * 256 KiB chunks. That endpoint identifies the game and starts the split as a job, which this class
 * follows in /api/job, reporting the last line of the job's log to the dialog.
 */
class RomImporter(
    private val context: Context,
    private val server: ServerService.Ready,
    private val uri: Uri,
    private val listener: Listener
) {
    interface Listener {
        fun onProgress(read: Long, total: Long, message: String)
        fun onJob(state: String, message: String)
        fun onDone(slug: String)
        fun onError(message: String)
    }

    @Volatile
    private var cancelled = false
    private var upload: HttpURLConnection? = null
    private var thread: Thread? = null

    fun start() {
        if (thread?.isAlive == true) return
        thread = Thread({ run() }, "rom-import").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        cancelled = true
        try {
            upload?.disconnect()
        } catch (_: Throwable) {
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", "decompdecomp-android decompdecomp-window/${server.secret}")
        connection.setRequestProperty("X-Decomp", "1")
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        return connection
    }

    private fun run() {
        try {
            val name = displayName() ?: "rom.bin"
            val total = displaySize()
            val encoded = URLEncoder.encode(name, "UTF-8")
            val connection = open("/api/import?confirmed=1&name=$encoded", "POST")
            upload = connection
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            if (total > 0) {
                connection.setFixedLengthStreamingMode(total)
            } else {
                connection.setChunkedStreamingMode(256 * 1024)
            }
            listener.onProgress(0, total, "Enviando $name…")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("não foi possível abrir o arquivo escolhido")
            input.use { source ->
                connection.outputStream.use { sink ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    var lastReport = 0L
                    while (!cancelled) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150) {
                            lastReport = now
                            listener.onProgress(read, total, "Enviando $name…")
                        }
                    }
                    sink.flush()
                }
            }
            if (cancelled) return
            listener.onProgress(total.coerceAtLeast(0), total, "Identificando o jogo…")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException(errorFrom(body, "o servidor recusou a ROM (HTTP $code)"))
            }
            val job = JSONObject(body).optString("job")
            if (job.isEmpty()) throw IOException("o servidor não devolveu o identificador da importação")
            follow(job)
        } catch (e: IOException) {
            if (!cancelled) listener.onError("Falha ao importar: ${e.message ?: e}")
        } catch (e: Throwable) {
            Log.e("decompdecomp", "import failed", e)
            if (!cancelled) listener.onError("Falha ao importar: ${e.message ?: e}")
        } finally {
            upload = null
        }
    }

    private fun follow(job: String) {
        while (!cancelled) {
            val connection = open("/api/job?id=$job", "GET")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException(errorFrom(body, "o servidor perdeu a importação (HTTP $code)"))
            }
            val obj = JSONObject(body)
            val state = obj.optString("state", "running")
            val log = obj.optJSONArray("log")
            val last = if (log != null && log.length() > 0) log.getString(log.length() - 1) else ""
            listener.onJob(state, last)
            when (state) {
                "done" -> {
                    val slug = obj.optJSONObject("result")?.optString("slug").orEmpty()
                    if (slug.isEmpty()) {
                        listener.onError("A importação terminou sem criar um projeto.")
                    } else {
                        listener.onDone(slug)
                    }
                    return
                }
                "error", "failed" -> {
                    listener.onError(errorFrom(body, if (last.isEmpty()) "a importação falhou" else last))
                    return
                }
            }
            Thread.sleep(600)
        }
    }

    private fun errorFrom(body: String, fallback: String): String {
        return try {
            val obj = JSONObject(body)
            obj.optString("error").ifEmpty { obj.optString("message") }.ifEmpty { fallback }
        } catch (_: Throwable) {
            body.take(1000).ifBlank { fallback }
        }
    }

    private fun displayName(): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun displaySize(): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1
    }
}
