package ai.owlwhisp.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Private PCM16 capture on disk; the microphone always releases on its capture thread. */
class AudioRecorder(private val context: Context, private val limitSeconds: Int,
    private val onLimit: () -> Unit, private val onError: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    val file = File(context.cacheDir, "recording-" + UUID.randomUUID() + ".pcm16")
    @Volatile private var count = 0L
    val samplesWritten get() = count
    @Volatile var complete = false
        private set
    @Volatile private var failure: Exception? = null
    private var audio: AudioRecord? = null
    private var thread: Thread? = null
    init {
        require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        require(limitSeconds in RecordingPolicy.limits)
    }
    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission is required")
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Microphone recording is not supported" }
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("Microphone unavailable") }
        audio = record
        try {
            check(file.createNewFile()) { "Could not create the recording file" }
            running.set(true); record.startRecording()
        } catch (error: Exception) {
            running.set(false); audio = null; record.release(); complete = true; file.delete(); throw error
        }
        thread = Thread({
            try {
                val buffer = ShortArray(2048); val bytes = ByteArray(buffer.size * 2)
                val maximum = if (limitSeconds == 0) Long.MAX_VALUE else 16000L * limitSeconds
                file.outputStream().buffered().use { output ->
                    while (running.get() && count < maximum) {
                        val size = record.read(buffer, 0, minOf(buffer.size.toLong(), maximum - count).toInt())
                        if (size < 0) { if (running.get()) error("Microphone recording interrupted"); break }
                        for (i in 0 until size) {
                            bytes[2 * i] = buffer[i].toByte(); bytes[2 * i + 1] = (buffer[i].toInt() shr 8).toByte()
                        }
                        output.write(bytes, 0, size * 2); output.flush(); count += size
                    }
                }
                if (running.getAndSet(false)) onLimit()
            } catch (error: Exception) {
                failure = error
                if (running.getAndSet(false)) onError(error.message ?: "Could not save microphone audio")
            } finally { record.release(); complete = true }
        }, "owlwhisp-microphone").also { it.start() }
    }
    fun requestStop() {
        running.set(false)
        try { audio?.stop() } catch (_: IllegalStateException) { /* Already stopped or released. */ }
    }
    /** Off the UI thread; transfers the completed private file to the caller. */
    fun finish(): File {
        requestStop(); thread?.join(2000)
        check(thread?.isAlive != true) { "Microphone did not stop recording" }
        failure?.let { throw it }
        return file
    }
    fun discard() {
        requestStop(); thread?.join(2000); file.delete()
    }
}
