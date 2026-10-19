package ai.owlwhisp.mobile

object RecordingPolicy {
    const val SAMPLE_RATE = 16000
    const val CHUNK_SECONDS = 30
    const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS
    val limits = listOf(0, 60, 300, 900)
    fun label(seconds: Int) = when (seconds) {
        0 -> "No time limit"
        60 -> "1 minute"
        else -> "${seconds / 60} minutes"
    }
}
