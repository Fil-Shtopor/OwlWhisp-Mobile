package ai.owlwhisp.mobile

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class BenchmarkStore(private val context: Context) {
    private val directory = File(context.filesDir, "benchmarks")
    private val latest = File(context.filesDir, "benchmark.json")
    private val languages by lazy {
        val fixtures = JSONArray(context.assets.open("benchmark/fixtures.json").bufferedReader().use { it.readText() })
        (0 until fixtures.length()).associate { fixtures.getJSONObject(it).let { row -> row.getString("file") to row.getString("language") } }
    }
    private fun write(file: File, report: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(report.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    fun save(report: String) {
        val json = JSONObject(report)
        val id = json.getString("model")
        require(Regex("[a-z0-9-]+").matches(id))
        write(File(directory, "$id.json"), report)
        write(latest, report)
    }
    fun report(model: ModelRepository): JSONObject? {
        val file = File(directory, model.id + ".json").takeIf { it.isFile } ?: latest.takeIf { it.isFile } ?: return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        if (json.optString("model") != model.id || json.optString("model_version") != model.manifest.getString("version")) return null
        return json
    }
    fun metrics(model: ModelRepository): String {
        val report = report(model) ?: return "WER \u2014 \u00b7 RTF \u2014"
        val rows = report.getJSONArray("rows")
        val grouped = (0 until rows.length()).map { rows.getJSONObject(it) }
            .groupBy { it.optString("language").ifBlank { languages[it.optString("file")] ?: "?" } }
        return grouped.toSortedMap().entries.joinToString("\n") { (language, clips) ->
            val selected = JSONArray().also { array -> clips.forEach { array.put(it) } }
            val total = Benchmark.summary(selected)
            String.format(Locale.ROOT, "%s  WER %.1f%% \u00b7 RTF %.2f \u00b7 %d clips",
                language.uppercase(Locale.ROOT), total.wordErrorRate * 100, total.rtf, clips.size)
        }
    }
}
