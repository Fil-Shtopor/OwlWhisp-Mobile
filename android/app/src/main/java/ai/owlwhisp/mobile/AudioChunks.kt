package ai.owlwhisp.mobile

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** PCM16 disk reader: memory stays bounded independently of the recording duration. */
class AudioChunks(file: File) : Closeable {
    data class Chunk(val samples: FloatArray, val start: Long, val end: Long, val overlapsPrevious: Boolean)
    private val input = RandomAccessFile(file, "r")
    val totalSamples: Long
    private var position = 0L
    private var overlaps = false
    init {
        if (input.length() % 2 != 0L) { input.close(); error("Invalid PCM16 recording") }
        totalSamples = input.length() / 2
    }
    fun next(): Chunk? {
        if (position >= totalSamples) return null
        val count = minOf(RecordingPolicy.CHUNK_SAMPLES.toLong(), totalSamples - position).toInt()
        val bytes = ByteArray(count * 2)
        input.seek(position * 2); input.readFully(bytes)
        val samples = FloatArray(count) { i ->
            (((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort()) / 32768f
        }
        val hasMore = position + count < totalSamples
        val boundary = if (hasMore) quietBoundary(samples) else count
        val start = position; val end = start + boundary; val previous = overlaps
        overlaps = hasMore && boundary == count
        position = end - if (overlaps) RecordingPolicy.SAMPLE_RATE / 2 else 0
        return Chunk(if (boundary == count) samples else samples.copyOf(boundary), start, end, previous)
    }
    override fun close() = input.close()

    companion object {
        /** Prefer a pause of at least 200 ms within the final five seconds of a chunk. */
        fun quietBoundary(samples: FloatArray): Int {
            val window = RecordingPolicy.SAMPLE_RATE / 50
            var peakEnergy = 0.0
            for (start in samples.indices step window) {
                val end = minOf(start + window, samples.size)
                var energy = 0.0
                for (i in start until end) energy += samples[i].toDouble() * samples[i]
                peakEnergy = maxOf(peakEnergy, energy / (end - start))
            }
            val quietEnergy = maxOf(1e-10, peakEnergy * 0.01)
            var run = -1; var bestLength = 0; var best = samples.size
            var cursor = maxOf(0, samples.size - RecordingPolicy.SAMPLE_RATE * 5)
            while (cursor + window <= samples.size) {
                var energy = 0.0
                for (i in cursor until cursor + window) energy += samples[i].toDouble() * samples[i]
                if (energy / window <= quietEnergy) {
                    if (run < 0) run = cursor
                    val length = cursor + window - run
                    if (length >= RecordingPolicy.SAMPLE_RATE / 5 && length >= bestLength) {
                        bestLength = length; best = run + length / 2
                    }
                } else run = -1
                cursor += window
            }
            return best
        }
        /** Framewise energy avoids losing quiet speech surrounded by long pauses. */
        fun trimQuietMargins(samples: FloatArray): FloatArray {
            val window = RecordingPolicy.SAMPLE_RATE / 10
            var first = -1; var last = 0
            for (start in samples.indices step window) {
                val end = minOf(start + window, samples.size)
                var energy = 0.0
                for (i in start until end) energy += samples[i].toDouble() * samples[i]
                if (energy / (end - start) >= 0.0005 * 0.0005) {
                    if (first < 0) first = start
                    last = end
                }
            }
            if (first < 0) return FloatArray(0)
            val padding = RecordingPolicy.SAMPLE_RATE / 2
            val start = maxOf(0, first - padding); val end = minOf(samples.size, last + padding)
            return if (start == 0 && end == samples.size) samples else samples.copyOfRange(start, end)
        }
        fun appendTranscript(existing: String, incoming: String, overlaps: Boolean): String {
            val next = incoming.trim()
            if (next.isEmpty()) return existing
            if (existing.isBlank()) return next
            val words = next.split(Regex("\\s+"))
            var skip = 0
            if (overlaps) {
                val before = existing.trim().split(Regex("\\s+"))
                fun normalized(word: String) = word.lowercase(Locale.ROOT).replace('\u0451', '\u0435').filter { it.isLetterOrDigit() }
                for (size in minOf(12, before.size, words.size) downTo 1) {
                    val suffix = before.takeLast(size).map(::normalized)
                    if (suffix.all { it.isNotEmpty() } && suffix == words.take(size).map(::normalized)) { skip = size; break }
                }
            }
            val tail = words.drop(skip).joinToString(" ")
            return if (tail.isEmpty()) existing else existing.trimEnd() + " " + tail
        }
    }
}
