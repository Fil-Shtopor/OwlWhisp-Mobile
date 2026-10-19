package ai.owlwhisp.mobile

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Private, disposable process: unloading also reclaims native allocator pools and global engines. */
class InferenceService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val engine = RecognitionEngine()
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo ?: return
            val what = msg.what
            val data = msg.data
            worker.execute {
                val response = Bundle()
                if (what == 2) {
                    engine.close()
                    runCatching { reply.send(Message.obtain(null, 2).apply { this.data = response }) }
                    main.postDelayed({ stopSelf(); Process.killProcess(Process.myPid()) }, 100)
                    return@execute
                }
                try {
                    val name = data.getString("audio") ?: error("No audio")
                    require(name.matches(Regex("inference-[a-f0-9-]+\\.pcm")))
                    val file = File(cacheDir, name)
                    require(file.length() in 4..(RecordingPolicy.CHUNK_SAMPLES.toLong() * 4) && file.length() % 4 == 0L)
                    val bytes = file.readBytes()
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val samples = FloatArray(bytes.size / 4) { buffer.float }
                    require(samples.all { it.isFinite() })
                    val repository = ModelRepository(this@InferenceService, data.getString("model") ?: "")
                    val started = System.nanoTime()
                    val peak = AtomicLong(0)
                    val sampler = Executors.newSingleThreadScheduledExecutor()
                    val sampling = sampler.scheduleAtFixedRate({ peak.accumulateAndGet(Debug.getPss()) { a, b -> maxOf(a, b) } }, 0, 100, TimeUnit.MILLISECONDS)
                    val text = try { engine.transcribe(repository, samples) } finally { sampling.cancel(false); sampler.shutdown() }
                    response.putInt("peak_pss_kib", peak.get().toInt())
                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                    response.putString("text", text)
                    response.putLong("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                    response.putInt("pss_kib", memory.totalPss)
                } catch (error: Throwable) { response.putString("error", error.message ?: error.javaClass.simpleName) }
                runCatching { reply.send(Message.obtain(null, 1).apply { this.data = response }) }
            }
        }
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
