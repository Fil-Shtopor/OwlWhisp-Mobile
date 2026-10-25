package ai.owlwhisp.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Owns the lifetime of an explicitly started dictation, independently of UI surfaces. */
class RecordingService : Service() {
    private val app get() = application as OwlWhispApp
    private var expectedStop = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val renewWakeLock = object : Runnable {
        override fun run() {
            wakeLock?.acquire(10 * 60 * 1000L)
            main.postDelayed(this, 8 * 60 * 1000L)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val token = intent.getLongExtra("generation", -1)
                if (!app.acceptsCapture(token)) { stopSelf(startId); return START_NOT_STICKY }
                expectedStop = false
                try {
                    val notification = notification("Recording", "Tap to open dictation")
                    if (Build.VERSION.SDK_INT >= 30) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(ID, notification)
                    wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OwlWhisp:dictation")
                        .apply { setReferenceCounted(false) }
                    renewWakeLock.run()
                    app.beginCapture(this, token)
                } catch (error: Exception) { app.captureFailed(token, error.message ?: "Could not start recording"); finish() }
            }
            STOP -> if (app.ownsCaptureService(this)) app.stopCurrent() else finish()
            CANCEL -> { if (app.ownsCaptureService(this)) app.cancelCurrent(); finish() }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    private fun notification(title: String, message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Dictation", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("tab", 0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(value: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, RecordingService::class.java).setAction(value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_voice_microphone)
            .setContentTitle(title).setContentText(message).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", action(STOP, 1)).build())
            .addAction(Notification.Action.Builder(null, "Cancel", action(CANCEL, 2)).build()).build()
    }
    fun processing() {
        getSystemService(NotificationManager::class.java).notify(ID, notification("Finishing dictation", "Recognizing the remaining audio"))
    }
    private fun releaseWakeLock() {
        main.removeCallbacks(renewWakeLock)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }
    fun finish() { expectedStop = true; releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        releaseWakeLock()
        if (!expectedStop) app.captureServiceLost(this)
        super.onDestroy()
    }
    companion object {
        const val START = "ai.owlwhisp.mobile.START_RECORDING"
        const val STOP = "ai.owlwhisp.mobile.STOP_RECORDING"
        const val CANCEL = "ai.owlwhisp.mobile.CANCEL_RECORDING"
        private const val CHANNEL = "dictation"
        private const val ID = 10
    }
}
