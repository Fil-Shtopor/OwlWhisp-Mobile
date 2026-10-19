package ai.owlwhisp.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded PCM recording; the AudioRecord always releases on its capture thread. */
class AudioRecorder(private val context: Context, private val onLimit: () -> Unit, private val onError: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private val samples = FloatArray(16_000 * 60)
    @Volatile private var count = 0
    private var audio: AudioRecord? = null
    private var thread: Thread? = null

    init { require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Микрофон не разрешён")
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Микрофон не поддерживает запись" }
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("Микрофон недоступен") }
        audio = record
        running.set(true)
        try { record.startRecording() } catch (failure: Exception) {
            running.set(false); audio = null; record.release(); throw failure
        }
        thread = Thread({
            try {
                val buffer = ShortArray(2048)
                while (running.get() && count < samples.size) {
                    val size = record.read(buffer, 0, minOf(buffer.size, samples.size - count))
                    if (size < 0) {
                        if (running.get()) error("Запись микрофона прервана")
                        break
                    }
                    for (i in 0 until size) samples[count++] = buffer[i] / 32768f
                }
                if (running.getAndSet(false)) onLimit()
            } catch (failure: Exception) {
                if (running.getAndSet(false)) onError(failure.message ?: "Ошибка микрофона")
            } finally { record.release() }
        }, "owlwhisp-microphone").also { it.start() }
    }

    fun requestStop() {
        running.set(false)
        try { audio?.stop() } catch (_: IllegalStateException) { /* Already stopped or released. */ }
    }

    /** Called off the UI thread after requestStop(). */
    fun finish(): FloatArray {
        requestStop()
        thread?.join(2000)
        check(thread?.isAlive != true) { "Микрофон не завершил запись" }
        return samples.copyOf(count)
    }
}
