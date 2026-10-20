package com.deivid22srk.decompdecomp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * The whole UI: decompdecomp's own web interface in a WebView, with a small native bar for the
 * things a phone needs (pick a ROM, export the result, read the log, change the font, credits).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var overlay: View
    private lateinit var spinner: ProgressBar
    private lateinit var status: TextView
    private lateinit var errorText: TextView
    private lateinit var retry: Button
    private lateinit var root: View

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerService.Ready? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null
    private var importer: RomImporter? = null
    private var importDialog: AlertDialog? = null
    private var pendingExport: File? = null
    private var fontPercent = 100
    private var pageFailed = false

    private val readyListener: (ServerService.Ready) -> Unit = { ready ->
        main.post { onServerReady(ready) }
    }
    private val failedListener: (String) -> Unit = { message ->
        main.post { showStartupError(message) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        root = buildLayout()
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        configureWebView()
        requestNotificationPermission()
        startServer()
        bindServer()
    }

    override fun onDestroy() {
        ServerService.removeReady(readyListener)
        ServerService.removeFailed(failedListener)
        fileChooser?.onReceiveValue(null)
        fileChooser = null
        importer?.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ layout

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val content = FrameLayout(this)
        webView = WebView(this).apply { setBackgroundColor(BG) }
        content.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = buildOverlay()
        content.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(buildToolbar(), LinearLayout.LayoutParams(MATCH, WRAP_CONTENT))
        return column
    }

    private fun buildOverlay(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            isClickable = true
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        box.addView(TextView(this).apply {
            text = "decompdecomp"
            setTextColor(ACCENT)
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.loading_tagline)
            setTextColor(MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(28))
        })
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        box.addView(spinner, LinearLayout.LayoutParams(dp(42), dp(42)))
        status = TextView(this).apply {
            text = getString(R.string.loading)
            setTextColor(TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        box.addView(status)
        errorText = TextView(this).apply {
            setTextColor(Color.parseColor("#FF8A80"))
            textSize = 12f
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
            setTextIsSelectable(true)
        }
        box.addView(errorText)
        retry = Button(this).apply {
            text = getString(R.string.retry)
            visibility = View.GONE
            setOnClickListener { restartServer() }
        }
        box.addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = dp(16)
        })
        return box
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SURFACE)
            setPadding(dp(6), dp(5), dp(6), dp(5))
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(toolButton(getString(R.string.tool_rom)) { confirmRom() })
        bar.addView(toolButton(getString(R.string.tool_export)) { exportCurrent() })
        bar.addView(toolButton(getString(R.string.tool_console)) {
            startActivity(Intent(this, ConsoleActivity::class.java))
        })
        bar.addView(toolButton("A−") { changeFont(-10) })
        bar.addView(toolButton("A+") { changeFont(10) })
        bar.addView(toolButton(getString(R.string.tool_about)) { About.show(this) })
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        return scroll
    }

    private fun toolButton(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    // ------------------------------------------------------------------ server

    private fun startServer() {
        startForegroundService(Intent(this, ServerService::class.java))
    }

    private fun bindServer() {
        ServerService.whenReady(readyListener)
        ServerService.whenFailed(failedListener)
    }

    private fun onServerReady(ready: ServerService.Ready) {
        if (server != null) return
        server = ready
        pageFailed = false
        val base = webView.settings.userAgentString ?: "Mozilla/5.0 (Linux; Android)"
        webView.settings.userAgentString = "$base decompdecomp-window/${ready.secret}"
        status.text = getString(R.string.opening)
        webView.loadUrl("http://127.0.0.1:${ready.port}/")
    }

    private fun showStartupError(message: String) {
        if (server != null) {
            // The server is up but the page did not load (WebView policy, a blocked port...).
            AlertDialog.Builder(this)
                .setTitle(R.string.load_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.retry) { _, _ ->
                    pageFailed = false
                    overlay.visibility = View.GONE
                    webView.reload()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        overlay.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        status.text = ""
        errorText.visibility = View.VISIBLE
        errorText.text = getString(R.string.start_failed, message)
        retry.visibility = View.VISIBLE
    }

    private fun restartServer() {
        overlay.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        status.text = getString(R.string.restarting)
        status.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        retry.visibility = View.GONE
        ServerService.removeReady(readyListener)
        ServerService.removeFailed(failedListener)
        ServerService.reset()
        stopService(Intent(this, ServerService::class.java))
        main.postDelayed({
            startServer()
            bindServer()
        }, 800)
    }

    // ------------------------------------------------------------------ web view

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = fontPercent
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = false
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                if (host == "127.0.0.1" || host == "localhost") return false
                openExternally(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!pageFailed) overlay.visibility = View.GONE
                // The desktop "Quit" item would kill the server process under the app; the update
                // item replaces an install that does not exist on Android.
                view.evaluateJavascript(
                    "(function(){['quitBtn','navUpdate'].forEach(function(id){" +
                        "var e=document.getElementById(id);if(e)e.remove();});})();",
                    null
                )
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageFailed = true
                    showStartupError(error.description?.toString() ?: "erro de rede")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileChooser?.onReceiveValue(null)
                fileChooser = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_WEB_FILE)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooser = null
                    callback.onReceiveValue(null)
                    toast(getString(R.string.no_file_picker))
                    false
                }
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                Log.d("decompdecomp-web", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }

        webView.setDownloadListener { _, _, _, _, _ ->
            toast(getString(R.string.use_export_button))
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_browser))
        }
    }

    private fun changeFont(delta: Int) {
        fontPercent = (fontPercent + delta).coerceIn(70, 200)
        webView.settings.textZoom = fontPercent
        toast(getString(R.string.font_size, fontPercent))
    }

    // ------------------------------------------------------------------ ROM import

    private fun confirmRom() {
        AlertDialog.Builder(this)
            .setTitle(R.string.rom_title)
            .setMessage(R.string.rom_notice)
            .setPositiveButton(R.string.rom_choose) { _, _ -> pickRom() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickRom() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_ROM)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_file_picker))
        }
    }

    private fun importRom(uri: Uri) {
        val ready = server ?: run {
            toast(getString(R.string.server_not_ready))
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        var name = uri.lastPathSegment ?: "rom.bin"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex) ?: name
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size > MAX_DISC) {
            AlertDialog.Builder(this)
                .setTitle(R.string.rom_too_big_title)
                .setMessage(getString(R.string.rom_too_big, size / GIB))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val free = StatFs(filesDir.absolutePath).availableBytes
        val needed = if (size > 0) size + size / 5 + 256L * 1024 * 1024 else 512L * 1024 * 1024
        if (free < needed) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_space_title)
                .setMessage(getString(R.string.no_space, free / MIB, needed / MIB))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        showImportDialog(ready, uri, name, size)
    }

    private fun showImportDialog(ready: ServerService.Ready, uri: Uri, name: String, size: Long) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            this.progress = 0
            isIndeterminate = size <= 0
        }
        val label = TextView(this).apply {
            text = getString(R.string.sending, name)
            setTextColor(TEXT)
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        box.addView(progress)
        box.addView(label)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                importer?.cancel()
                dialog.dismiss()
                toast(getString(R.string.import_cancelled))
            }
        }
        dialog.show()
        importDialog = dialog
        val listener = object : RomImporter.Listener {
            override fun onProgress(read: Long, total: Long, message: String) {
                main.post {
                    if (total > 0) {
                        progress.isIndeterminate = false
                        progress.progress = ((read * 1000) / total).toInt().coerceIn(0, 1000)
                        label.text = getString(
                            R.string.sending_progress, message,
                            read / MIB, total / MIB
                        )
                    } else {
                        progress.isIndeterminate = true
                        label.text = message
                    }
                }
            }

            override fun onJob(state: String, message: String) {
                main.post {
                    progress.isIndeterminate = true
                    label.text = if (message.isBlank()) getString(R.string.analysing) else message
                }
            }

            override fun onDone(slug: String) {
                main.post {
                    dialog.dismiss()
                    importDialog = null
                    importer = null
                    toast(getString(R.string.import_done, slug))
                    val js = "location.hash = " + JSONObject.quote("#/p/$slug")
                    webView.evaluateJavascript(js, null)
                }
            }

            override fun onError(message: String) {
                main.post {
                    dialog.dismiss()
                    importDialog = null
                    importer = null
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.import_failed_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.tool_console) { _, _ ->
                            startActivity(Intent(this@MainActivity, ConsoleActivity::class.java))
                        }
                        .show()
                }
            }
        }
        importer = RomImporter(this, ready, uri, listener).also { it.start() }
    }

    // ------------------------------------------------------------------ export

    private fun exportCurrent() {
        if (server == null) {
            toast(getString(R.string.server_not_ready))
            return
        }
        webView.evaluateJavascript("(typeof S!=='undefined'&&S.slug)?S.slug:''") { value ->
            val slug = parseJsString(value)
            if (slug.isNullOrBlank()) {
                toast(getString(R.string.open_project_first))
            } else {
                chooseExportMode(slug)
            }
        }
    }

    private fun parseJsString(value: String?): String? {
        if (value == null || value == "null") return null
        return try {
            JSONTokener(value).nextValue() as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun chooseExportMode(slug: String) {
        val modes = arrayOf(getString(R.string.export_mode_full), getString(R.string.export_mode_progress))
        AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setItems(modes) { _, which ->
                runExport(slug, if (which == 0) "project" else "progress", modes[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runExport(slug: String, mode: String, label: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        box.addView(ProgressBar(this).apply { isIndeterminate = true })
        box.addView(TextView(this).apply {
            text = getString(R.string.export_running)
            setTextColor(TEXT)
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.export_title)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()
        ExportManager.export(
            this, slug, mode,
            onDone = { file ->
                main.post {
                    dialog.dismiss()
                    pendingExport = file
                    offerSaveOrShare(slug, mode, file, label)
                }
            },
            onError = { message ->
                main.post {
                    dialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.export_failed_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        )
    }

    private fun offerSaveOrShare(slug: String, mode: String, file: File, label: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.export_done_title)
            .setMessage(getString(R.string.export_done, label, file.length() / MIB))
            .setPositiveButton(R.string.export_save) { _, _ -> saveExport(slug, mode, file) }
            .setNeutralButton(R.string.export_share) { _, _ ->
                try {
                    ExportManager.share(this, file)
                } catch (t: Throwable) {
                    toast(getString(R.string.share_failed, t.message ?: ""))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveExport(slug: String, mode: String, file: File) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "$slug-$mode.zip")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SAVE_EXPORT)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_file_picker))
        }
    }

    // ------------------------------------------------------------------ results

    @Deprecated("startActivityForResult is the simplest path here; there is no fragment")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_WEB_FILE -> {
                val uri = data?.data
                val callback = fileChooser
                fileChooser = null
                callback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
            }
            REQ_ROM -> {
                if (resultCode == RESULT_OK) data?.data?.let { importRom(it) }
            }
            REQ_SAVE_EXPORT -> {
                val target = data?.data
                val file = pendingExport
                if (resultCode == RESULT_OK && target != null && file != null) {
                    ExportManager.copyTo(this, file, target) { ok ->
                        main.post {
                            toast(getString(if (ok) R.string.export_saved else R.string.export_save_failed))
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQ_WEB_FILE = 1001
        private const val REQ_ROM = 1002
        private const val REQ_SAVE_EXPORT = 1003
        private const val REQ_NOTIFICATIONS = 1004
        private const val MAX_DISC = 16L * 1024 * 1024 * 1024
        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * 1024L * 1024L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val BG = 0xFF15140F.toInt()
        private const val SURFACE = 0xFF1F1E18.toInt()
        private const val TEXT = 0xFFE8E6E1.toInt()
        private const val MUTED = 0xFF9A978C.toInt()
        private const val ACCENT = 0xFFF6D43A.toInt()
    }
}
