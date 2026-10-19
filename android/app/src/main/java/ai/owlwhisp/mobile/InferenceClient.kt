package ai.owlwhisp.mobile

import android.content.*
import android.os.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class InferenceResult(val text: String, val elapsedMs: Long, val pssKiB: Int)

/** Called only on the application's serial worker. Binder callbacks run on main. */
class InferenceClient(private val context: Context) {
    @Volatile private var endpoint: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var pending: CountDownLatch? = null
    @Volatile private var result: Bundle? = null
    val loaded: Boolean get() = endpoint != null
    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) { result = msg.data; pending?.countDown() }
    })
    private fun connect() {
        if (endpoint != null) return
        val ready = CountDownLatch(1)
        val service = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { endpoint = Messenger(binder); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { endpoint = null; pending?.countDown() }
            override fun onBindingDied(name: ComponentName?) { endpoint = null; pending?.countDown(); ready.countDown() }
            override fun onNullBinding(name: ComponentName?) { ready.countDown() }
        }
        connection = service
        check(context.bindService(Intent(context, InferenceService::class.java), service, Context.BIND_AUTO_CREATE))
        check(ready.await(30, TimeUnit.SECONDS) && endpoint != null) { "Не удалось запустить движок" }
    }
    private fun request(what: Int, data: Bundle = Bundle()): Bundle {
        val latch = CountDownLatch(1); result = null; pending = latch
        try {
            checkNotNull(endpoint).send(Message.obtain(null, what).apply { this.data = data; replyTo = replies })
            check(latch.await(10, TimeUnit.MINUTES)) { "Превышено время распознавания" }
            return result ?: error("Движок остановлен. Попробуйте более лёгкую модель.")
        } finally { pending = null }
    }
    fun transcribe(repository: ModelRepository, samples: FloatArray): InferenceResult {
        require(samples.isNotEmpty() && samples.size <= 16000 * 60)
        val file = File(context.cacheDir, "inference-" + UUID.randomUUID() + ".pcm")
        try {
            val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { bytes.putFloat(it) }; file.writeBytes(bytes.array())
            connect()
            val response = request(1, Bundle().apply { putString("audio", file.name); putString("model", repository.id) })
            response.getString("error")?.let { error(it) }
            return InferenceResult(response.getString("text").orEmpty(), response.getLong("elapsed_ms"), response.getInt("pss_kib"))
        } catch (failure: Throwable) { unload(); throw failure } finally { file.delete() }
    }
    fun unload() {
        if (endpoint != null) runCatching { request(2) }
        endpoint = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        // Give the disposable process time to exit before a subsequent bind.
        Thread.sleep(180)
    }
}
