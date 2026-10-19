package ai.owlwhisp.mobile

import android.app.Instrumentation
import android.app.Activity.RESULT_OK
import android.app.Activity.RESULT_CANCELED
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only real-device integration checks; native engines never mocked. */
class SmokeInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        val report = Bundle()
        val client = InferenceClient(targetContext)
        try {
            check(Benchmark.wordErrorRate("Один, два! Ёж.", "один два еж") == 0.0)
            check(Benchmark.wordErrorRate("one two three", "one three") == 1.0 / 3.0)
            val ids = arguments.getString("models", "parakeet-tdt-0.6b-v3")!!.split(",")
            val results = JSONArray()
            for (id in ids) {
                val repository = ModelRepository(targetContext, id)
                if (arguments.getString("download") == "true") repository.install { _, _ -> }
                repository.verify()
                val language = if ("ru" in repository.languages) "ru" else "en"
                val fixtures = JSONArray(targetContext.assets.open("benchmark/fixtures.json").bufferedReader().use { it.readText() })
                val fixture = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }.first { it.getString("language") == language }
                val samples = targetContext.assets.open("benchmark/" + fixture.getString("file")).use { Benchmark.decodeWave(it.readBytes()) }
                val expected = fixture.getString("transcript")
                val result = client.transcribe(repository, samples)
                val wer = Benchmark.wordErrorRate(expected, result.text)
                check(result.text.isNotBlank() && wer <= 0.45) { id + " WER " + wer + ": " + result.text }
                results.put(JSONObject().put("model", id).put("transcript", result.text).put("reference", expected)
                    .put("wer", wer).put("elapsed_ms", result.elapsedMs).put("post_decode_pss_kib", result.pssKiB).put("sampled_peak_pss_kib", result.peakPssKiB))
                client.unload(); check(!client.loaded)
                val file = File(repository.directory, repository.files.last().name)
                val handle = java.io.RandomAccessFile(file, "rw")
                val original = handle.readByte()
                try {
                    handle.seek(0); handle.writeByte(original.toInt() xor 1)
                    check(runCatching { repository.verify() }.isFailure) { "Same-length corruption accepted" }
                } finally { handle.seek(0); handle.writeByte(original.toInt()); handle.close() }
                val silence = client.transcribe(repository, FloatArray(16000))
                check(silence.text.isEmpty()); client.unload()
            }
            RecognitionEngine().use { engine ->
                check(runCatching { engine.transcribe(ModelRepository(targetContext), FloatArray(16000 * 60 + 1)) }.isFailure)
            }
            if (arguments.getString("benchmark") == "true") {
                val repository = ModelRepository(targetContext, ids.first())
                Benchmark.run(targetContext, repository, client) { }
                val json = JSONObject(File(targetContext.filesDir, "benchmark.json").readText())
                check(json.getJSONArray("rows").length() == if ("ru" in repository.languages) 6 else 3)
                report.putString("benchmark", "All compatible clips transcribed; JSON report saved")
            }
            report.putString("results", results.toString())
            report.putString("integrity", "SHA-256 verified; same-length corruption rejected for every model")
            report.putString("lifecycle", "Private inference process unloaded; silence and length limit checked")
            finish(RESULT_OK, report)
        } catch (failure: Throwable) {
            report.putString("failure", failure.stackTraceToString()); finish(RESULT_CANCELED, report)
        } finally { client.unload() }
    }
}
