package ai.owlwhisp.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Real selected-model recognition through the disposable Binder process, using attributed FLEURS audio. */
class CatalogInstrumentation : Instrumentation() {
    private var id = ""
    override fun onCreate(arguments: Bundle?) { id = arguments?.getString("model").orEmpty(); super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val client = InferenceClient(targetContext)
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val repository = ModelRepository(targetContext, id)
            repository.verify()
            val fixtures = JSONArray(targetContext.assets.open("benchmark/fixtures.json").bufferedReader().use { it.readText() })
            val fixture = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }
                .first { it.getString("language") in repository.languages }
            val samples = targetContext.assets.open("benchmark/" + fixture.getString("file")).use { Benchmark.decodeWave(it.readBytes()) }
            val decoded = client.transcribe(repository, samples)
            val wer = Benchmark.wordErrorRate(fixture.getString("transcript"), decoded.text)
            check(decoded.text.isNotBlank() && wer < 0.7) { "Recognition failed: WER $wer; " + decoded.text }
            result.putString("recognition", JSONObject().put("model", id).put("fixture", fixture.getString("file")).put("text", decoded.text)
                .put("wer", wer).put("elapsed_ms", decoded.elapsedMs).put("peak_pss_kib", decoded.peakPssKiB).toString())
            runOnMainSync { activity.finish() }
            client.unload(); finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("failure", failure.stackTraceToString()); finish(Activity.RESULT_CANCELED, result)
        } finally { client.unload() }
    }
}
