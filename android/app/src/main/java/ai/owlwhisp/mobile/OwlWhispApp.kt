package ai.owlwhisp.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

enum class Phase { IDLE, DOWNLOADING, RECORDING, PROCESSING, BENCHMARK, ERROR }
data class AppState(val phase: Phase, val message: String, val progress: Int = 0)

class OwlWhispApp : Application() {
    val worker = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val preferences by lazy { getSharedPreferences("owlwhisp", MODE_PRIVATE) }
    val models by lazy { ModelRepository.catalog(this) }
    val model: ModelRepository get() = models.firstOrNull { it.id == preferences.getString("model", "") } ?: models.first()
    val client by lazy { InferenceClient(this) }
    private val listeners = mutableSetOf<(AppState) -> Unit>()
    var state = AppState(Phase.IDLE, "Your voice stays on your device")
        private set
    private var owner: Any? = null
    private var recorder: AudioRecorder? = null
    private var deliver: ((String) -> Unit)? = null
    @Volatile private var generation = 0L
    var lastMetrics = ""
        private set
    val busy get() = state.phase in setOf(Phase.DOWNLOADING, Phase.RECORDING, Phase.PROCESSING, Phase.BENCHMARK)
    val warmSeconds get() = preferences.getInt("warm_seconds", 0)
    val recordingLimitSeconds get() = preferences.getInt("recording_limit_seconds", 0).takeIf { it in RecordingPolicy.limits } ?: 0
    fun setRecordingLimit(seconds: Int) {
        require(seconds in RecordingPolicy.limits)
        preferences.edit().putInt("recording_limit_seconds", seconds).apply()
    }
    private val idleRelease = Runnable { unload() }

    override fun onCreate() {
        super.onCreate()
        // Recover private temporary audio after an interrupted process.
        if (getProcessName() == packageName) cacheDir.listFiles()?.filter { (it.name.startsWith("inference-") && it.extension == "pcm") || (it.name.startsWith("recording-") && it.extension == "pcm16") }?.forEach { it.delete() }
    }
    fun observe(listener: (AppState) -> Unit) { listeners.add(listener); listener(state) }
    fun removeObserver(listener: (AppState) -> Unit) { listeners.remove(listener) }
    private fun publish(next: AppState) { state = next; listeners.toList().forEach { it(next) } }
    fun refresh() {
        if (!busy) publish(AppState(Phase.IDLE, if (model.present()) "Ready Â· " + model.name else "Download a model in the Models tab"))
    }
    fun selectModel(id: String) {
        if (busy || models.none { it.id == id && it.supported }) return
        preferences.edit().putString("model", id).apply()
        unload()
    }
    fun setWarmSeconds(seconds: Int) {
        require(seconds in listOf(0, 30, 300))
        preferences.edit().putInt("warm_seconds", seconds).apply()
        if (seconds == 0) unload()
    }
    fun unload() {
        main.removeCallbacks(idleRelease)
        worker.execute { client.unload(); main.post { if (!busy) publish(AppState(Phase.IDLE, "Model unloaded from memory")) } }
    }
    fun installModel(repository: ModelRepository = model) {
        if (busy || !repository.supported) return
        publish(AppState(Phase.DOWNLOADING, "Downloading: " + repository.name))
        worker.execute {
            try {
                var lastUpdate = 0L
                repository.install { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 150 || done == total) {
                        lastUpdate = now
                        val percent = (done * 100 / total).toInt()
                        main.post { publish(AppState(Phase.DOWNLOADING, "Downloading: " + percent + "%", percent)) }
                    }
                }
                main.post { publish(AppState(Phase.IDLE, "Model verified. Ready to dictate.")) }
            } catch (failure: Exception) { main.post { publish(AppState(Phase.ERROR, failure.message ?: "Download failed")) } }
        }
    }
    fun deleteModel(repository: ModelRepository) {
        if (busy) return
        publish(AppState(Phase.PROCESSING, "Removing modelâ€¦"))
        worker.execute {
            client.unload(); repository.delete()
            main.post { publish(AppState(Phase.IDLE, "Model removed from device")) }
        }
    }
    fun runBenchmark() {
        if (busy || !model.present()) return
        val repository = model
        publish(AppState(Phase.BENCHMARK, "Preparing benchmarkâ€¦"))
        worker.execute {
            try {
                client.unload()
                Benchmark.run(this, repository, client) { message -> main.post { publish(AppState(Phase.BENCHMARK, message)) } }
                main.post { publish(AppState(Phase.IDLE, "Benchmark complete. Report saved on device.")) }
            } catch (failure: Throwable) { main.post { publish(AppState(Phase.ERROR, failure.message ?: "Benchmark failed")) } }
            finally { client.unload() }
        }
    }
    fun start(owner: Any, onResult: (String) -> Unit) {
        if (busy) return
        if (!model.present()) { publish(AppState(Phase.ERROR, "Download a model first")); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(AppState(Phase.ERROR, "Allow microphone access in the app")); return
        }
        main.removeCallbacks(idleRelease)
        val token = ++generation
        this.owner = owner; deliver = onResult
        try {
            recorder = AudioRecorder(this, recordingLimitSeconds,
                onLimit = { main.post { if (generation == token) stop(owner) } },
                onError = { message -> main.post { if (generation == token) { cancel(owner); publish(AppState(Phase.ERROR, message)) } } })
                .also { it.start() }
            publish(AppState(Phase.RECORDING, "Listening - " + RecordingPolicy.label(recordingLimitSeconds)))
        } catch (failure: Exception) {
            this.owner = null; deliver = null; recorder = null
            publish(AppState(Phase.ERROR, failure.message ?: "Could not start the microphone"))
        }
    }
    fun stop(requester: Any) {
        if (owner !== requester || state.phase != Phase.RECORDING) return
        val audio = recorder ?: return
        val token = generation; val callback = deliver; val repository = model
        recorder = null; audio.requestStop()
        publish(AppState(Phase.PROCESSING, "Transcribing on deviceâ€¦"))
        worker.execute {
            try {
                val file = audio.finish()
                val result = client.transcribeRecording(repository, file, cancelled = { generation != token }) { percent ->
                    main.post { if (generation == token) publish(AppState(Phase.PROCESSING, "Transcribing on device: $percent%")) }
                }
                if (warmSeconds == 0 || repository.engine != "sherpa") client.unload()
                main.post {
                    if (generation == token) {
                        owner = null; deliver = null
                        lastMetrics = "Last run: " + result.elapsedMs + " ms Â· PSS max " + result.peakPssKiB / 1024 + " MiB"
                        publish(AppState(Phase.IDLE, if (result.text.isBlank()) "No speech detected" else "Ready"))
                        callback?.invoke(result.text)
                        if (warmSeconds > 0) main.postDelayed(idleRelease, warmSeconds * 1000L)
                    }
                }
            } catch (failure: Throwable) {
                client.unload()
                main.post { if (generation == token) {
                    owner = null; deliver = null
                    publish(AppState(Phase.ERROR, failure.message ?: "Could not transcribe the recording"))
                } }
            } finally { audio.discard() }
        }
    }
    fun cancel(requester: Any) {
        if (owner !== requester) return
        generation++
        val audio = recorder
        recorder = null; owner = null; deliver = null
        audio?.requestStop()
        worker.execute { try { audio?.discard() } finally { client.unload() } }
        publish(AppState(Phase.IDLE, "Recording cancelled"))
    }
    fun isOwner(requester: Any) = owner === requester
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!busy) unload()
    }
}
