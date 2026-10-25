package ai.owlwhisp.mobile

import java.util.Locale
import kotlin.math.min

object BenchmarkMetrics {
    data class WordErrors(val count: Int, val referenceWords: Int) {
        val rate: Double get() = count.toDouble() / referenceWords.coerceAtLeast(1)
    }
    data class Clip(val reference: String, val transcript: String, val audioSeconds: Double, val elapsedSeconds: Double)
    data class Summary(val clipCount: Int, val wordErrors: Int, val referenceWords: Int, val audioSeconds: Double, val elapsedSeconds: Double) {
        val wordErrorRate: Double get() = wordErrors.toDouble() / referenceWords.coerceAtLeast(1)
        val rtf: Double get() = if (audioSeconds > 0) elapsedSeconds / audioSeconds else 0.0
    }

    fun wordErrors(reference: String, hypothesis: String): WordErrors {
        fun words(text: String) = Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase(Locale.ROOT).replace('\u0451', '\u0435')).map { it.value }.toList()
        val expected = words(reference); val actual = words(hypothesis)
        var previous = IntArray(actual.size + 1) { it }
        for ((i, word) in expected.withIndex()) {
            val current = IntArray(actual.size + 1); current[0] = i + 1
            for (j in actual.indices) current[j + 1] = min(min(current[j] + 1, previous[j + 1] + 1),
                previous[j] + if (word == actual[j]) 0 else 1)
            previous = current
        }
        return WordErrors(previous.last(), expected.size)
    }

    fun summarize(clips: List<Clip>): Summary {
        val scores = clips.map { wordErrors(it.reference, it.transcript) }
        return Summary(clips.size, scores.sumOf { it.count }, scores.sumOf { it.referenceWords },
            clips.sumOf { it.audioSeconds }, clips.sumOf { it.elapsedSeconds })
    }
}
