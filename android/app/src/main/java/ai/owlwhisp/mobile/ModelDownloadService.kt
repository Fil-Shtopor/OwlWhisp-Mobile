package ai.owlwhisp.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** Protects an explicit model transfer while activities are recreated or backgrounded. */
class ModelDownloadService : Service() {
    private val app get() = application as OwlWhispApp
    private var expectedStop = false
    private var modelName = ""
    private var lastNotification = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val renewWakeLock = object : Runnable {
        override fun run() {
            wakeLock?.acquire(10 * 60 * 1000L)
            main.postDelayed(this, 5 * 60 * 1000L)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val token = intent.getLongExtra("token", -1)
                if (!app.acceptsDownload(token)) { stopSelf(startId); return START_NOT_STICKY }
                if (wakeLock != null) return START_NOT_STICKY
                modelName = app.downloadingModelID?.let(app.guide::name).orEmpty()
                try {
                    val notification = notification(0)
                    if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    else startForeground(ID, notification)
                    wakeLock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OwlWhisp:model-download")
                        .apply { setReferenceCounted(false) }
                    renewWakeLock.run()
                    app.beginModelDownload(this, token)
                } catch (error: Exception) {
                    app.cancelModelDownload(error.message ?: "Could not start model download. Tap Download to retry.")
                    app.beginModelDownload(this, token)
                    finish()
                }
            }
            CANCEL -> { app.cancelModelDownload() }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    private fun notification(percent: Int, stopping: Boolean = false): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java)
            .putExtra("tab", 2).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 21, Intent(this, ModelDownloadService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (stopping) "Cancelling download" else "Downloading " + modelName)
            .setContentText(if (stopping) "Finishing the current file operation" else percent.toString() + "% - tap to view Models")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, percent, stopping)
            .apply { if (!stopping) addAction(Notification.Action.Builder(null, "Cancel", cancel).build()) }.build()
    }
    fun progress(percent: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification >= 1000 || percent == 100) {
            lastNotification = now
            getSystemService(NotificationManager::class.java).notify(ID, notification(percent))
        }
    }
    fun cancelling() { getSystemService(NotificationManager::class.java).notify(ID, notification(0, true)) }
    fun finish() {
        expectedStop = true
        main.removeCallbacks(renewWakeLock)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.cancelModelDownload("Android stopped the long download. Tap Download to retry; verified files are kept.")
        finish()
    }
    override fun onDestroy() {
        if (!expectedStop) app.modelDownloadServiceLost(this)
        finish()
        super.onDestroy()
    }
    companion object {
        const val START = "ai.owlwhisp.mobile.START_MODEL_DOWNLOAD"
        const val CANCEL = "ai.owlwhisp.mobile.CANCEL_MODEL_DOWNLOAD"
        private const val CHANNEL = "model-downloads"
        private const val ID = 20
    }
}
