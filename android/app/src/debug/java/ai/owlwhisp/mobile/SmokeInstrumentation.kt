package ai.owlwhisp.mobile

import android.app.Instrumentation
import android.app.Activity.RESULT_OK
import android.app.Activity.RESULT_CANCELED
import android.os.Bundle
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Debug-only end-to-end test: pinned files -> native CPU inference -> Russian transcript. */
class SmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = Bundle()
        try {
            val repository = ModelRepository(targetContext)
            repository.verify()
            val fixture = targetContext.assets.open("fleurs_ru_1.wav").use { it.readBytes() }
            val samples = decodeWave(fixture)
            val expected = JSONArray(targetContext.assets.open("fixtures.json").bufferedReader().use { it.readText() })
                .getJSONObject(0).getString("transcript")
            val engine = RecognitionEngine()
            val started = System.nanoTime()
            val text = try { engine.transcribe(repository, samples) } finally { engine.close() }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val errorRate = wordErrorRate(expected, text)
            check(text.any { it in 'А'..'я' }) { "No Russian speech recognized: " + text }
            check(errorRate <= 0.35) { "Russian word error rate exceeded threshold: " + text }

            val tokens = File(repository.directory, "tokens.txt")
            val original = tokens.readBytes()
            try {
                val tampered = original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                tokens.writeBytes(tampered)
                check(runCatching { repository.verify() }.isFailure) { "Corrupt weights were accepted" }
            } finally { tokens.writeBytes(original) }

            RecognitionEngine().use { silent ->
                check(silent.transcribe(repository, FloatArray(16_000)).isEmpty()) { "Silence was not suppressed" }
                check(runCatching { silent.transcribe(repository, FloatArray(16_000 * 60 + 1)) }.isFailure) {
                    "The recording limit was not enforced"
                }
            }
            report.putString("transcript", text)
            report.putString("reference", expected)
            report.putString("wer", errorRate.toString())
            report.putLong("elapsed_ms", elapsed)
            report.putString("model", "parakeet-tdt-0.6b-v3")
            report.putString("provider", "cpu")
            report.putString("integrity", "SHA-256 verified; same-length tampering rejected")
            finish(RESULT_OK, report)
        } catch (failure: Throwable) {
            report.putString("failure", failure.stackTraceToString())
            finish(RESULT_CANCELED, report)
        }
    }

    private fun decodeWave(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        var offset = 12
        var format = 0
        var bits = 0
        var rate = 0
        var channels = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            check(size >= 0 && offset.toLong() + 8 + size <= bytes.size) { "Invalid WAV chunk" }
            val start = offset + 8
            if (id == "fmt ") {
                format = buffer.getShort(start).toInt()
                channels = buffer.getShort(start + 2).toInt()
                rate = buffer.getInt(start + 4)
                bits = buffer.getShort(start + 14).toInt()
            }
            if (id == "data") {
                check(rate == 16_000 && channels == 1)
                return when {
                    format == 3 && bits == 32 -> FloatArray(size / 4) { buffer.getFloat(start + it * 4) }
                    format == 1 && bits == 16 -> FloatArray(size / 2) { buffer.getShort(start + it * 2) / 32768f }
                    else -> error("Unsupported fixture encoding")
                }
            }
            offset = start + size + (size % 2)
        }
        error("WAV contains no audio")
    }

    private fun wordErrorRate(reference: String, hypothesis: String): Double {
        fun words(text: String) = Regex("[а-яёa-z0-9]+").findAll(text.lowercase()).map { it.value }.toList()
        val expected = words(reference)
        val actual = words(hypothesis)
        var previous = IntArray(actual.size + 1) { it }
        for ((i, word) in expected.withIndex()) {
            val current = IntArray(actual.size + 1)
            current[0] = i + 1
            for (j in actual.indices) current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1),
                previous[j] + if (word == actual[j]) 0 else 1)
            previous = current
        }
        return previous.last().toDouble() / expected.size.coerceAtLeast(1)
    }
}
