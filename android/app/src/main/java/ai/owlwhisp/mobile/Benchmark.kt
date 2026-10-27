package ai.owlwhisp.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object Benchmark {
    fun decodeWave(bytes: ByteArray): FloatArray {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12; var format = 0; var bits = 0; var rate = 0; var channels = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset.toLong() + 8 + size <= bytes.size)
            val start = offset + 8
            if (id == "fmt ") {
                require(size >= 16)
                format = buffer.getShort(start).toInt(); channels = buffer.getShort(start + 2).toInt()
                rate = buffer.getInt(start + 4); bits = buffer.getShort(start + 14).toInt()
            }
            if (id == "data") {
                require(rate == 16000 && channels == 1)
                return when {
                    format == 3 && bits == 32 -> FloatArray(size / 4) { buffer.getFloat(start + it * 4) }
                    format == 1 && bits == 16 -> FloatArray(size / 2) { buffer.getShort(start + it * 2) / 32768f }
                    else -> error("Unsupported WAV encoding")
                }.also { require(it.all { value -> value.isFinite() }) }
            }
            offset = start + size + size % 2
        }
        error("No WAV data")
    }

    fun wordErrorRate(reference: String, hypothesis: String): Double =
        BenchmarkMetrics.wordErrors(reference, hypothesis).rate

    fun fixtures(context: Context, languages: List<String>): List<JSONObject> {
        val all = JSONArray(context.assets.open("benchmark/fixtures.json").bufferedReader().use { it.readText() })
        return (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("language") in languages }
    }

    fun clipDescription(fixtures: List<JSONObject>): String =
        fixtures.groupingBy { it.getString("language") }.eachCount().toSortedMap().entries.joinToString(" · ") { (language, count) ->
            "$count " + Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH) + if (count == 1) " clip" else " clips"
        }.ifEmpty { "No matching clips" }

    fun summary(rows: JSONArray): BenchmarkMetrics.Summary = BenchmarkMetrics.summarize((0 until rows.length()).map {
        val row = rows.getJSONObject(it)
        BenchmarkMetrics.Clip(row.getString("reference"), row.getString("transcript"),
            row.getDouble("audio_s"), row.getDouble("elapsed_ms") / 1000.0)
    })

    fun summaryJSON(summary: BenchmarkMetrics.Summary): JSONObject = JSONObject()
        .put("clips", summary.clipCount).put("word_errors", summary.wordErrors).put("reference_words", summary.referenceWords)
        .put("wer", summary.wordErrorRate).put("audio_s", summary.audioSeconds).put("elapsed_s", summary.elapsedSeconds)
        .put("rtf", summary.rtf)

    fun run(context: Context, repository: ModelRepository, client: InferenceClient, progress: (String) -> Unit): String {
        val fixtures = fixtures(context, repository.languages)
        val rows = JSONArray()
        for (fixture in fixtures) {
            progress("Clip " + (rows.length() + 1) + " of " + fixtures.size)
            val audio = context.assets.open("benchmark/" + fixture.getString("file")).use { decodeWave(it.readBytes()) }
            val result = client.transcribe(repository, audio)
            val duration = audio.size / 16000.0
            val errors = BenchmarkMetrics.wordErrors(fixture.getString("transcript"), result.text)
            rows.put(JSONObject().put("file", fixture.getString("file")).put("language", fixture.getString("language"))
                .put("reference", fixture.getString("transcript")).put("transcript", result.text)
                .put("wer", errors.rate).put("word_errors", errors.count).put("reference_words", errors.referenceWords)
                .put("audio_s", duration).put("elapsed_ms", result.elapsedMs).put("rtf", result.elapsedMs / 1000.0 / duration)
                .put("post_decode_pss_kib", result.pssKiB).put("sampled_peak_pss_kib", result.peakPssKiB))
            client.unload()
        }
        check(rows.length() > 0) { "No benchmark clips match this model's languages" }
        val report = JSONObject().put("model", repository.id).put("model_version", repository.manifest.getString("version"))
            .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName).put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first()).put("created_at_ms", System.currentTimeMillis())
            .put("mode", "cold: hash verification + load + decode; 2 CPU threads; process unloaded after each clip")
            .put("memory_measurement", "Inference-process PSS sampled every 100 ms; sampled maximum, not a guaranteed allocation peak")
            .put("normalization", "Unicode letters and numbers, lowercase, punctuation removed, U+0451 folded to U+0435")
            .put("summary", summaryJSON(summary(rows))).put("rows", rows).toString(2)
        BenchmarkStore(context).save(report)
        return report
    }
}
