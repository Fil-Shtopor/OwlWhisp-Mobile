package ai.owlwhisp.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

enum class Phase { IDLE, DOWNLOADING, RECORDING, PROCESSING, ERROR }
data class AppState(val phase: Phase, val message: String, val progress: Int = 0)

class OwlWhispApp : Application() {
    val worker = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val model by lazy { ModelRepository(this) }
    private val engine = RecognitionEngine()
    private val listeners = mutableSetOf<(AppState) -> Unit>()
    var state = AppState(Phase.IDLE, "Загрузите модель, чтобы начать")
        private set
    private var owner: Any? = null
    private var recorder: AudioRecorder? = null
    private var deliver: ((String) -> Unit)? = null
    private var generation = 0L

    fun observe(listener: (AppState) -> Unit) { listeners.add(listener); listener(state) }
    fun removeObserver(listener: (AppState) -> Unit) { listeners.remove(listener) }
    private fun publish(next: AppState) { state = next; listeners.toList().forEach { it(next) } }

    fun refresh() {
        if (state.phase == Phase.IDLE || state.phase == Phase.ERROR)
            publish(AppState(Phase.IDLE, if (model.present()) "Готово · всё обрабатывается на устройстве" else "Нужна модель Parakeet · 639 МиБ"))
    }

    fun installModel() {
        if (state.phase in setOf(Phase.DOWNLOADING, Phase.RECORDING, Phase.PROCESSING)) return
        publish(AppState(Phase.DOWNLOADING, "Загрузка модели…"))
        worker.execute {
            try {
                var lastUpdate = 0L
                model.install { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 150 || done == total) {
                        lastUpdate = now
                        val percent = (done * 100 / total).toInt()
                        main.post { publish(AppState(Phase.DOWNLOADING, "Загрузка модели: " + percent + "%", percent)) }
                    }
                }
                main.post { publish(AppState(Phase.IDLE, "Модель проверена. Можно диктовать.")) }
            } catch (failure: Exception) {
                main.post { publish(AppState(Phase.ERROR, failure.message ?: "Не удалось загрузить модель")) }
            }
        }
    }

    fun start(owner: Any, onResult: (String) -> Unit) {
        if (state.phase in setOf(Phase.DOWNLOADING, Phase.PROCESSING, Phase.RECORDING)) return
        if (!model.present()) { publish(AppState(Phase.ERROR, "Сначала загрузите модель в приложении")); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(AppState(Phase.ERROR, "Разрешите доступ к микрофону в приложении")); return
        }
        val token = ++generation
        this.owner = owner
        deliver = onResult
        try {
            recorder = AudioRecorder(this,
                onLimit = { main.post { if (generation == token) stop(owner) } },
                onError = { message -> main.post { if (generation == token) { cancel(owner); publish(AppState(Phase.ERROR, message)) } } })
                .also { it.start() }
            publish(AppState(Phase.RECORDING, "Слушаю… До 60 секунд"))
        } catch (failure: Exception) {
            this.owner = null; deliver = null; recorder = null
            publish(AppState(Phase.ERROR, failure.message ?: "Не удалось включить микрофон"))
        }
    }

    fun stop(requester: Any) {
        if (owner !== requester || state.phase != Phase.RECORDING) return
        val audio = recorder ?: return
        val token = generation
        val callback = deliver
        recorder = null
        audio.requestStop()
        publish(AppState(Phase.PROCESSING, "Распознаю на устройстве…"))
        worker.execute {
            try {
                val result = engine.transcribe(model, audio.finish())
                main.post {
                    if (generation == token) {
                        owner = null; deliver = null
                        publish(AppState(Phase.IDLE, if (result.isBlank()) "Речь не обнаружена" else "Готово"))
                        callback?.invoke(result)
                    }
                }
            } catch (failure: Throwable) {
                main.post {
                    if (generation == token) {
                        owner = null; deliver = null
                        publish(AppState(Phase.ERROR, failure.message ?: "Не удалось распознать запись"))
                    }
                }
            } finally { engine.close() }
        }
    }

    fun cancel(requester: Any) {
        if (owner !== requester) return
        generation++
        val audio = recorder
        recorder = null; owner = null; deliver = null
        audio?.requestStop()
        if (audio != null) worker.execute { try { audio.finish() } catch (_: Exception) { } }
        publish(AppState(Phase.IDLE, "Запись отменена"))
    }

    fun isOwner(requester: Any): Boolean = owner === requester
}
