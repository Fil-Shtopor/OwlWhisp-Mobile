package ai.owlwhisp.mobile

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.FutureTask

/** Actual foreground microphone lifecycle plus real Parakeet decoding of a growing file. */
class LiveDictationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun <T> ui(block: () -> T): T {
        val task = FutureTask(block); runOnMainSync(task); return task.get()
    }
    private fun await(message: String, seconds: Int = 40, block: () -> Boolean) {
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < until) { if (block()) return; Thread.sleep(100) }
        error(message)
    }
    private fun shell(command: String) { uiAutomation.executeShellCommand(command).close() }
    override fun onStart() {
        val report = Bundle()
        val app = targetContext.applicationContext as OwlWhispApp
        val file = File(targetContext.cacheDir, "live-dictation-test.pcm16")
        val client = InferenceClient(targetContext)
        try {
            file.writeBytes(ByteArray(20 * 32000) { if (it % 2 == 0) 0 else 64 })
            LiveAudioChunks(file).use { chunks ->
                check(chunks.next(7L * 16000, false) == null)
                check(chunks.next(8L * 16000, false)!!.end == 8L * 16000)
                check(chunks.next(8L * 16000, true) == null)
            }
            LiveAudioChunks(file).use { chunks ->
                var end = 0L; var count = 0
                while (true) {
                    val chunk = chunks.next(20L * 16000, true) ?: break
                    check(chunk.start <= end && chunk.end > end && chunk.samples.size <= 8 * 16000)
                    end = chunk.end; count++
                }
                check(end == 20L * 16000 && count == 3)
            }
            report.putString("cursor", "Growing prefix, bounded backlog, short tail and no final-overlap replay passed")

            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            check(app.model.present())
            ui { app.setWarmSeconds(0); app.start(activity) {} }
            await("Microphone did not start: " + ui { app.state.message }) { ui { app.capturedSamples > 16000 } }
            val beforeSettings = ui { app.capturedSamples }
            val settings = startActivitySync(Intent(targetContext, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            ui { activity.finish(); settings.recreate() }
            Thread.sleep(1500)
            check(ui { app.state.phase == Phase.RECORDING && app.capturedSamples > beforeSettings })
            shell("input keyevent 3"); Thread.sleep(2000)
            val beforeLock = ui { app.capturedSamples }
            check(ui { app.state.phase == Phase.RECORDING })
            shell("input keyevent 223"); Thread.sleep(2500)
            check(ui { app.state.phase == Phase.RECORDING && app.capturedSamples > beforeLock })
            val notifications = targetContext.getSystemService(NotificationManager::class.java)
            val notification = notifications.activeNotifications.single { it.id == 10 }.notification
            notification.actions.single { it.title.toString() == "Stop" }.actionIntent.send()
            await("Notification stop did not finish") { ui { app.state.phase == Phase.IDLE } }
            await("Recording notification remained") { notifications.activeNotifications.none { it.id == 10 } }
            await("Temporary capture audio remained") { targetContext.cacheDir.listFiles().orEmpty().none { it.name.startsWith("recording-") } }
            report.putString("capture", "Settings, Activity recreation/destruction, Home, screen lock, notification Stop and file/service cleanup passed")
            shell("input keyevent 224"); shell("input keyevent 82")
            Thread.sleep(500)

            val fixtures = JSONArray(targetContext.assets.open("benchmark/fixtures.json").bufferedReader().use { it.readText() })
            val fixture = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }.first { it.getString("language") == "en" }
            val samples = targetContext.assets.open("benchmark/" + fixture.getString("file")).use { Benchmark.decodeWave(it.readBytes()) }
            val block = ByteArray(8 * 32000)
            for (i in 0 until minOf(samples.size, block.size / 2)) {
                val sample = (samples[i] * 32767).toInt().toShort()
                block[2*i] = sample.toByte(); block[2*i+1] = (sample.toInt() shr 8).toByte()
            }
            file.writeBytes(byteArrayOf())
            var text = ""; var updatesBeforeStop = 0; var peak = 0
            LiveAudioChunks(file).use { chunks ->
                fun drain(complete: Boolean) {
                    while (true) {
                        val chunk = chunks.next(file.length() / 2, complete) ?: break
                        val part = client.transcribe(app.model, chunk.samples)
                        text = AudioChunks.appendTranscript(text, part.text, chunk.overlapsPrevious)
                        peak = maxOf(peak, part.peakPssKiB)
                        if (!complete && text.isNotBlank()) updatesBeforeStop++
                    }
                }
                repeat(3) { file.appendBytes(block); drain(false) }
                check(updatesBeforeStop >= 2) { "No live text before capture ended" }
                drain(true)
            }
            val expected = List(3) { fixture.getString("transcript") }.joinToString(" ")
            val wer = Benchmark.wordErrorRate(expected, text)
            check(wer <= 0.45) { "Live recording WER $wer: $text" }
            report.putString("recognition", JSONObject().put("audio_seconds", 24).put("updates_before_stop", updatesBeforeStop)
                .put("wer", wer).put("text", text).put("peak_pss_kib", peak).toString())
            client.unload()
            finish(Activity.RESULT_OK, report)
        } catch (failure: Throwable) {
            ui { app.cancelCurrent() }
            report.putString("failure", failure.stackTraceToString()); finish(Activity.RESULT_CANCELED, report)
        } finally { file.delete(); client.unload() }
    }
}
