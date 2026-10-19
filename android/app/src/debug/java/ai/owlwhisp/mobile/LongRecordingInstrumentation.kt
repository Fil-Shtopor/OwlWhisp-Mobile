package ai.owlwhisp.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real disk capture and inference beyond one minute, plus chunk-boundary regression checks. */
class LongRecordingInstrumentation : Instrumentation() {
    private var options = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }
    override fun onStart() {
        val report = Bundle(); val client = InferenceClient(targetContext)
        val temporary = File(targetContext.cacheDir, "long-recording-test.pcm16")
        var recorder: AudioRecorder? = null
        try {
            check(RecordingPolicy.limits.first() == 0)
            fun write(value: Short, seconds: Int) {
                val bytes = ByteArray(32000) { i -> if (i % 2 == 0) value.toByte() else (value.toInt() shr 8).toByte() }
                temporary.outputStream().use { out -> repeat(seconds) { out.write(bytes) } }
            }
            write(16000, 80)
            AudioChunks(temporary).use { reader ->
                var end = 0L; var count = 0
                while (true) {
                    val chunk = reader.next() ?: break
                    check(chunk.samples.size <= RecordingPolicy.CHUNK_SAMPLES && chunk.start <= end && chunk.end > end)
                    check(chunk.overlapsPrevious == (count > 0))
                    check(kotlin.math.abs(chunk.samples[0] - 16000f / 32768) < 0.000001f)
                    end = chunk.end; count++
                }
                check(end == 80L * 16000 && count == 3)
            }
            write(0, 80)
            AudioChunks(temporary).use { reader ->
                var end = 0L
                while (true) { val chunk = reader.next() ?: break; check(!chunk.overlapsPrevious && chunk.start == end); end = chunk.end }
                check(end == 80L * 16000)
            }
            check(AudioChunks.appendTranscript("Hello WORLD.", "world and more", true) == "Hello WORLD. and more")
            check(AudioChunks.appendTranscript("yes", "yes", false) == "yes yes")
            check(AudioChunks.appendTranscript("yes", "yes yes", true) == "yes yes")
            val repository = ModelRepository(targetContext)
            val seconds = options.getString("capture_seconds", "0")!!.toInt()
            if (seconds > 0) {
                startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitForIdleSync()
                val limits = AtomicInteger(0)
                var error: String? = null
                recorder = AudioRecorder(targetContext, 0, { limits.incrementAndGet() }, { error = it })
                recorder.start()
                Thread.sleep(seconds * 1000L)
                val file = recorder.finish()
                check(error == null && limits.get() == 0 && file.length() > 60L * 16000 * 2) { error ?: "Unlimited capture stopped early" }
                report.putLong("captured_bytes", file.length())
                report.putString("capture", "Recorded $seconds seconds with no time limit; private disk PCM16")
                val silent = client.transcribeRecording(repository, file, { false })
                check(silent.text.isEmpty()); client.unload(); recorder.discard(); recorder = null
            }
            if (options.getString("recognize") == "true") {
                repository.verify()
                val fixtures = JSONArray(targetContext.assets.open("benchmark/fixtures.json").bufferedReader().use { it.readText() })
                val fixture = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }.first { it.getString("language") == "en" }
                val samples = targetContext.assets.open("benchmark/" + fixture.getString("file")).use { Benchmark.decodeWave(it.readBytes()) }
                temporary.outputStream().use { output ->
                    for (second in 0 until 72) {
                        val data = ByteArray(32000)
                        val offset = when { second < 10 -> second * 16000; second in 32..41 -> (second - 32) * 16000; second >= 64 -> (second - 64) * 16000; else -> samples.size }
                        for (i in 0 until 16000) {
                            val sample = if (offset + i < samples.size) (samples[offset + i] * 32767).toInt().toShort() else 0.toShort()
                            data[2 * i] = sample.toByte(); data[2 * i + 1] = (sample.toInt() shr 8).toByte()
                        }
                        output.write(data)
                    }
                }
                val result = client.transcribeRecording(repository, temporary, { false })
                val expected = List(3) { fixture.getString("transcript") }.joinToString(" ")
                val wer = Benchmark.wordErrorRate(expected, result.text)
                check(wer <= 0.45) { "Long recording WER $wer: ${result.text}" }
                report.putString("recognition", JSONObject().put("seconds", 72).put("wer", wer).put("text", result.text).put("peak_pss_kib", result.peakPssKiB).toString())
            }
            report.putString("chunking", "80-second coverage; pause boundaries; overlap stitching; bounded 30-second chunks passed")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) { report.putString("failure", error.stackTraceToString()); finish(Activity.RESULT_CANCELED, report) }
        finally { recorder?.discard(); temporary.delete(); client.unload() }
    }
}
