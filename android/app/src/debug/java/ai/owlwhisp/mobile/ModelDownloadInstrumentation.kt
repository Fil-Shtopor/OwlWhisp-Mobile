package ai.owlwhisp.mobile

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled transport; production installer, foreground service, file hashes and activities. */
class ModelDownloadInstrumentation : Instrumentation() {
    private var fixture = ""
    private class Transfer(val corrupt: Boolean = false, val fail: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    @Volatile private var transfer = Transfer()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixture = arguments?.getString("tokens").orEmpty()
        start()
    }
    override fun onStart() {
        val result = Bundle()
        val app = targetContext.applicationContext as OwlWhispApp
        val saved = app.preferences.all.toMap()
        val repository = app.models.first { it.id == "moonshine-tiny-en" }
        val tokenFile = File(repository.directory, "tokens.txt")
        val original = tokenFile.takeIf { it.isFile }?.readBytes()
        val payload = File(fixture).readBytes()
        val checks = JSONArray()
        var host: Activity? = null
        var outcome = Activity.RESULT_CANCELED
        fun onMain(check: () -> Boolean): Boolean { var value = false; runOnMainSync { value = check() }; return value }
        fun await(message: String, check: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
            while (System.nanoTime() < deadline) { if (onMain(check)) return; Thread.sleep(100) }
            error(message + ": " + app.state)
        }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        fun open(): Activity {
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            return startActivitySync(Intent(targetContext, MainActivity::class.java).putExtra("tab", 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))!!
        }
        fun begin(next: Transfer): Transfer {
            transfer = next; tokenFile.delete()
            runOnMainSync { app.installModel(repository) }
            check(next.entered.await(25, TimeUnit.SECONDS)) { "Installer did not reach the controlled stream: " + app.state }
            check(onMain { app.state.phase == Phase.DOWNLOADING && app.downloadingModelID == repository.id })
            return next
        }
        try {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): HttpURLConnection {
                        check(url.toString() == repository.files.first { it.name == "tokens.txt" }.url)
                        val request = transfer
                        return object : HttpURLConnection(url) {
                            override fun connect() {}
                            override fun disconnect() {}
                            override fun usingProxy() = false
                            override fun getResponseCode() = 200
                            override fun getInputStream(): InputStream = object : ByteArrayInputStream(
                                payload.copyOf().also { if (request.corrupt) it[0] = (it[0].toInt() xor 1).toByte() }) {
                                private var first = true
                                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                                    if (first) {
                                        first = false; request.entered.countDown()
                                        check(request.release.await(90, TimeUnit.SECONDS)) { "Test gate timed out" }
                                        if (request.fail) throw IOException("Controlled network failure")
                                    }
                                    return super.read(bytes, off, minOf(len, 16384))
                                }
                            }
                        }
                    }
                }
            }
            runOnMainSync {
                app.preferences.edit().putBoolean("automatic_updates", false).putBoolean("language_routing", false).commit()
            }
            check(repository.files.filter { it.name != "tokens.txt" }.all { File(repository.directory, it.name).length() == it.bytes })
            host = open()
            val first = begin(Transfer())
            check(targetContext.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 20 && it.isOngoing })
            val unchanged = repository.files.filter { it.name != "tokens.txt" }.associate { it.name to File(repository.directory, it.name).lastModified() }
            fun clickTab(text: String) {
                runOnMainSync {
                    fun find(view: View): Button? {
                        if (view is Button && view.text.toString() == text) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    check(find(host!!.window.decorView)?.performClick() == true)
                }
                check(onMain { app.state.phase == Phase.DOWNLOADING })
            }
            clickTab("Settings"); clickTab("Dictate"); clickTab("Models")
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { host!!.recreate() }
            host = waitForMonitorWithTimeout(monitor, 15000) ?: error("Activity was not recreated")
            removeMonitor(monitor)
            check(onMain { app.downloadingModelID == repository.id && app.state.phase == Phase.DOWNLOADING })
            shell("input keyevent 3"); shell("input keyevent 223")
            Thread.sleep(2000)
            check(targetContext.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 20 && it.isOngoing })
            first.release.countDown()
            await("Background transfer did not complete") { app.downloadingModelID == null }
            check(onMain { app.state.phase == Phase.IDLE })
            repository.verify()
            check(unchanged.all { (name, time) -> File(repository.directory, name).lastModified() == time })
            check(targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 20 })
            checks.put("verified download finishes across tabs, activity recreation, Home and screen lock")
            checks.put("foreground progress notification and wake-protected transfer clean up on completion")
            checks.put("already verified model files are reused")

            host = open()
            val cancelled = begin(Transfer())
            val notification = targetContext.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 20 }
            notification.notification.actions.first { it.title.toString() == "Cancel" }.actionIntent.send()
            await("Cancellation was not delivered") { app.downloadCancelling }
            cancelled.release.countDown()
            await("Cancellation did not finish") { app.downloadingModelID == null }
            check(!tokenFile.exists() && !File(repository.directory, "tokens.txt.part").exists())
            checks.put("notification Cancel stops transfer and removes its partial file")

            val broken = begin(Transfer(corrupt = true))
            broken.release.countDown()
            await("Corrupt download did not finish") { app.downloadingModelID == null }
            check(onMain { app.state.phase == Phase.ERROR && app.state.message.contains("SHA-256") })
            check(!tokenFile.exists() && !File(repository.directory, "tokens.txt.part").exists())
            checks.put("corrupt download is never committed; service stops on failure")

            val retry = begin(Transfer())
            retry.release.countDown()
            await("Retry did not finish") { app.downloadingModelID == null }
            repository.verify()
            checks.put("retry succeeds after cancellation and verification failure")
            result.putString("checks", checks.toString())
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("failure", error.stackTraceToString())
        } finally {
            transfer.release.countDown()
            runOnMainSync { app.cancelModelDownload() }
            runCatching { await("Cleanup") { app.downloadingModelID == null } }
            if (original != null) tokenFile.writeBytes(original) else tokenFile.delete()
            runOnMainSync {
                val edit = app.preferences.edit().clear()
                for ((key, value) in saved) when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                }
                edit.commit(); host?.finish()
            }
        }
        finish(outcome, result)
    }
}
