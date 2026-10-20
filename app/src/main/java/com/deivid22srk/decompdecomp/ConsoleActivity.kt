package com.deivid22srk.decompdecomp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.RandomAccessFile

/**
 * The "terminal": decompdecomp's live log, the same file the desktop app writes.
 *
 * There is no shell here on purpose. decompdecomp has no TUI; its interface is the web page. This
 * screen shows what the server and the jobs print (splat, the importer, agent errors), which is
 * what a terminal would show in the desktop app, and lets the user share it in a bug report.
 */
class ConsoleActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var follow: CheckBox
    private val main = Handler(Looper.getMainLooper())
    private var reading = false

    private val logFile: File by lazy { File(filesDir, "Decomp/.decomp/logs/decomp.log") }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        refresh()
        main.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E0B"))
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.console_title)
            setTextColor(Color.parseColor("#F6D43A"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(8))
        })
        column.addView(TextView(this).apply {
            text = logFile.absolutePath
            setTextColor(Color.parseColor("#77746B"))
            textSize = 10f
            setPadding(dp(16), 0, dp(16), dp(8))
        })
        output = TextView(this).apply {
            setTextColor(Color.parseColor("#D8D5CC"))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        scroll = ScrollView(this).apply { addView(output) }
        column.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1F1E18"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        follow = CheckBox(this).apply {
            text = getString(R.string.console_follow)
            isChecked = true
            setTextColor(Color.parseColor("#E8E6E1"))
        }
        row.addView(follow)
        row.addView(button(getString(R.string.console_refresh)) { refresh() })
        row.addView(button(getString(R.string.console_share)) { shareLog() })
        row.addView(button(getString(R.string.console_close)) { finish() })
        val bottom = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        column.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        return column
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun refresh() {
        if (reading) return
        reading = true
        Thread({
            val text = readTail()
            main.post {
                reading = false
                if (!isFinishing && !isDestroyed) {
                    output.text = text
                    if (follow.isChecked) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }, "console-tail").apply {
            isDaemon = true
            start()
        }
    }

    private fun readTail(): String {
        if (!logFile.isFile) return getString(R.string.console_empty)
        return try {
            RandomAccessFile(logFile, "r").use { file ->
                val length = file.length()
                val start = (length - 192 * 1024).coerceAtLeast(0)
                file.seek(start)
                val bytes = ByteArray((length - start).toInt())
                file.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                if (start > 0) text = text.substringAfter('\n', text)
                text.ifBlank { getString(R.string.console_empty) }
            }
        } catch (t: Throwable) {
            getString(R.string.console_read_error, t.message ?: t.toString())
        }
    }

    private fun shareLog() {
        val text = output.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.console_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "decompdecomp — log")
            putExtra(Intent.EXTRA_TEXT, text.takeLast(120_000))
        }
        startActivity(Intent.createChooser(send, getString(R.string.console_share)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
