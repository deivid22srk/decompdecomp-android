package com.deivid22srk.decompdecomp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps decompdecomp's Python server alive while the app is in the background.
 *
 * The Python runtime is started here (Chaquopy, in this process), the HTTP server binds to
 * 127.0.0.1 and the WebView talks to it. A foreground notification and a partial wake lock keep
 * Android from killing a long import or a running agent.
 */
class ServerService : Service() {

    data class Ready(val port: Int, val secret: String)

    companion object {
        private const val TAG = "decompdecomp"
        private const val CHANNEL_ID = "decompdecomp"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.deivid22srk.decompdecomp.action.STOP"

        @Volatile
        var ready: Ready? = null
            private set

        @Volatile
        var failure: String? = null
            private set

        private val readyListeners = CopyOnWriteArrayList<(Ready) -> Unit>()
        private val failureListeners = CopyOnWriteArrayList<(String) -> Unit>()

        /** Calls [listener] now if the server is up, otherwise as soon as it is. */
        fun whenReady(listener: (Ready) -> Unit) {
            val current = ready
            if (current != null) listener(current) else readyListeners.add(listener)
        }

        fun whenFailed(listener: (String) -> Unit) {
            val current = failure
            if (current != null) listener(current) else failureListeners.add(listener)
        }

        fun removeReady(listener: (Ready) -> Unit) {
            readyListeners.remove(listener)
        }

        fun removeFailed(listener: (String) -> Unit) {
            failureListeners.remove(listener)
        }

        fun reset() {
            ready = null
            failure = null
            readyListeners.clear()
            failureListeners.clear()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Iniciando o servidor Python…"))
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "decompdecomp:server").apply {
            setReferenceCounted(false)
            acquire()
        }
        startPython()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("decompmobile").callAttr("stop")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stop() failed", t)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startPython() {
        val current = ready
        if (current != null) {
            notifyReady(current)
            return
        }
        if (worker?.isAlive == true) return
        worker = Thread({
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val home = File(filesDir, "Decomp")
                home.mkdirs()
                val result = Python.getInstance().getModule("decompmobile")
                    .callAttr("start", home.absolutePath, 6464, applicationInfo.nativeLibraryDir)
                val obj = JSONObject(result.toString())
                val newReady = Ready(obj.getInt("port"), obj.getString("secret"))
                ready = newReady
                updateNotification("Pronto · toque para abrir")
                notifyReady(newReady)
                Log.i(TAG, "server ready on 127.0.0.1:${newReady.port}")
            } catch (t: Throwable) {
                Log.e(TAG, "Python server failed", t)
                val message = readableError(t)
                failure = message
                updateNotification("Falhou ao iniciar · toque para ver")
                main.post {
                    failureListeners.toList().forEach { it(message) }
                    failureListeners.clear()
                }
            }
        }, "decomp-python").apply { isDaemon = true }
        worker?.start()
    }

    private fun readableError(t: Throwable): String {
        val text = t.message?.trim().orEmpty().ifEmpty { t.toString() }
        // Chaquopy prefixes PyException with the Java class; the Python traceback is what helps.
        return text.takeLast(4000)
    }

    private fun notifyReady(newReady: Ready) {
        main.post {
            readyListeners.toList().forEach { it(newReady) }
            readyListeners.clear()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIcon = Icon.createWithResource(this, R.drawable.ic_stat_decomp)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_decomp)
            .setContentTitle("decompdecomp")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(stopIcon, "Parar", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }
}
