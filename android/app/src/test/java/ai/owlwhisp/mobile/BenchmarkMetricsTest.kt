package ai.owlwhisp.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkMetricsTest {
    @Test fun summaryWeightsErrorsByWordsAndSpeedByAudioDuration() {
        val summary = BenchmarkMetrics.summarize(listOf(
            BenchmarkMetrics.Clip("one", "wrong", 1.0, 0.5),
            BenchmarkMetrics.Clip("one two three four five six seven eight nine",
                "one two three four five six seven eight nine", 9.0, 1.5)))
        assertEquals(2, summary.clipCount)
        assertEquals(1, summary.wordErrors)
        assertEquals(10, summary.referenceWords)
        assertEquals(0.1, summary.wordErrorRate, 1e-10)
        assertEquals(10.0, summary.audioSeconds, 1e-10)
        assertEquals(2.0, summary.elapsedSeconds, 1e-10)
        assertEquals(0.2, summary.rtf, 1e-10)
    }

    @Test fun normalizationAndInsertionsRemainPartOfTheScore() {
        assertEquals(0.0, BenchmarkMetrics.wordErrors("\u0401\u0436, ONE!", "\u0435\u0436 one").rate, 1e-10)
        assertEquals(2.0, BenchmarkMetrics.wordErrors("one", "one two three").rate, 1e-10)
        assertEquals(1, BenchmarkMetrics.wordErrors("", "one").count)
        val empty = BenchmarkMetrics.summarize(emptyList())
        assertEquals(0.0, empty.wordErrorRate, 0.0)
        assertEquals(0.0, empty.rtf, 0.0)
    }
}
