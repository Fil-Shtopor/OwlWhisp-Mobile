package ai.owlwhisp.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.min

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

    fun wordErrorRate(reference: String, hypothesis: String): Double {
        fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(Locale.ROOT).replace('\u0451', '\u0435')).map { it.value }.toList()
        val expected = words(reference); val actual = words(hypothesis)
        var previous = IntArray(actual.size + 1) { it }
        for ((i, word) in expected.withIndex()) {
            val current = IntArray(actual.size + 1); current[0] = i + 1
            for (j in actual.indices) current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1),
                previous[j] + if (word == actual[j]) 0 else 1)
            previous = current
        }
        return previous.last().toDouble() / expected.size.coerceAtLeast(1)
    }

    fun run(context: Context, repository: ModelRepository, client: InferenceClient, progress: (String) -> Unit): String {
        val fixtures = JSONArray(context.assets.open("benchmark/fixtures.json").bufferedReader().use { it.readText() })
        val rows = JSONArray()
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            if (fixture.getString("language") !in repository.languages) continue
            progress("Clip " + (rows.length() + 1) + ": " + fixture.getString("file"))
            val audio = context.assets.open("benchmark/" + fixture.getString("file")).use { decodeWave(it.readBytes()) }
            val result = client.transcribe(repository, audio)
            val duration = audio.size / 16000.0
            rows.put(JSONObject().put("file", fixture.getString("file")).put("reference", fixture.getString("transcript"))
                .put("transcript", result.text).put("wer", wordErrorRate(fixture.getString("transcript"), result.text))
                .put("audio_s", duration).put("elapsed_ms", result.elapsedMs).put("rtf", result.elapsedMs / 1000.0 / duration)
                .put("post_decode_pss_kib", result.pssKiB).put("sampled_peak_pss_kib", result.peakPssKiB))
            client.unload()
        }
        check(rows.length() > 0) { "No benchmark clips match this model's languages" }
        val report = JSONObject().put("model", repository.id).put("model_version", repository.manifest.getString("version"))
            .put("app_version", "0.1.1").put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first()).put("created_at_ms", System.currentTimeMillis())
            .put("mode", "cold: hash verification + load + decode; 2 CPU threads; process unloaded after each clip")
            .put("memory_measurement", "Inference-process PSS sampled every 100 ms; sampled maximum, not a guaranteed allocation peak")
            .put("normalization", "Unicode letters and numbers, lowercase, punctuation removed, U+0451 folded to U+0435")
            .put("rows", rows).toString(2)
        File(context.filesDir, "benchmark.json").writeText(report)
        return report
    }
}
