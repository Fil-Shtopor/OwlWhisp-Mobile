package ai.owlwhisp.mobile

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** A single cursor over a growing file. Backlog stays on disk, never in an audio queue. */
class LiveAudioChunks(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private var position = 0L
    private var coveredEnd = 0L
    private var overlaps = false
    fun next(availableSamples: Long, complete: Boolean): AudioChunks.Chunk? {
        if (availableSamples <= position || (complete && availableSamples <= coveredEnd)) return null
        val window = 8 * RecordingPolicy.SAMPLE_RATE
        if (!complete && availableSamples - position < window) return null
        val count = minOf(window.toLong(), availableSamples - position).toInt()
        val bytes = ByteArray(count * 2)
        input.seek(position * 2); input.readFully(bytes)
        val samples = FloatArray(count) { i ->
            (((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort()) / 32768f
        }
        val more = !complete || position + count < availableSamples
        val boundary = if (more) AudioChunks.quietBoundary(samples) else count
        val start = position; val end = start + boundary; val previous = overlaps
        overlaps = more && boundary == count
        position = end - if (overlaps) RecordingPolicy.SAMPLE_RATE / 2 else 0
        coveredEnd = end
        return AudioChunks.Chunk(if (boundary == count) samples else samples.copyOf(boundary), start, end, previous)
    }
    override fun close() = input.close()
}
