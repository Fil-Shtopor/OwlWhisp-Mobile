package ai.owlwhisp.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.Intent
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase { IDLE, DOWNLOADING, RECORDING, PROCESSING, BENCHMARK, ERROR }
data class AppState(val phase: Phase, val message: String, val progress: Int = 0, val transcript: String = "")

class OwlWhispApp : Application() {
    val worker = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val preferences by lazy { getSharedPreferences("owlwhisp", MODE_PRIVATE) }
    val models by lazy { ModelRepository.catalog(this) }
    val guide by lazy { ModelGuide(this) }
    val benchmarks by lazy { BenchmarkStore(this) }
    val updates by lazy { AppUpdates(this) }
    private val manualModel: ModelRepository get() = models.firstOrNull { it.id == preferences.getString("model", "") } ?: models.first()
    private val routingModels get() = models.map { LanguageRouting.Model(it.id, it.languages, it.supported) }
    val routingEnabled get() = preferences.getBoolean("language_routing", false)
    val routingLanguage get() = preferences.getString("routing_language", "en").orEmpty()
    val languageAssignments: Map<String, String> get() {
        val saved = preferences.getString("language_models", null)
        if (saved != null) return runCatching {
            val json = org.json.JSONObject(saved)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        return mapOf("en" to (manualModel.takeIf { "en" in it.languages }?.id ?: "moonshine-tiny-en"),
            "ru" to (manualModel.takeIf { "ru" in it.languages }?.id ?: "gigaam-v3-ru"))
    }
    val model: ModelRepository get() {
        val id = LanguageRouting.resolve(routingEnabled, routingLanguage, languageAssignments, manualModel.id, routingModels)
        return models.first { it.id == id }
    }
    fun setRoutingEnabled(enabled: Boolean) {
        if (busy) return
        preferences.edit().putBoolean("language_routing", enabled).apply()
        if (enabled && routingLanguage !in languageAssignments) {
            preferences.edit().putString("routing_language", languageAssignments.keys.sorted().firstOrNull() ?: "en").apply()
        }
        unload(); refresh()
    }
    fun assignLanguage(language: String, id: String?) {
        if (busy) return
        require(id == null || LanguageRouting.assign(language, id, routingModels))
        val assignments = languageAssignments.toMutableMap()
        if (id == null) assignments.remove(language) else assignments[language] = id
        val edit = preferences.edit().putString("language_models", org.json.JSONObject(assignments as Map<*, *>).toString())
        if (assignments.isEmpty()) edit.putBoolean("language_routing", false)
        if (routingLanguage !in assignments) edit.putString("routing_language", assignments.keys.sorted().firstOrNull() ?: "en")
        edit.apply(); unload(); refresh()
    }
    fun chooseLanguage(language: String): Boolean {
        if (busy || !routingEnabled) return false
        val id = languageAssignments[language] ?: return false
        val next = models.firstOrNull { it.id == id && it.supported && language in it.languages } ?: return false
        if (!next.present()) { publish(AppState(Phase.ERROR, "Download " + guide.name(next.id) + " in Models first")); return false }
        preferences.edit().putString("routing_language", language).apply()
        unload(); refresh(); return true
    }
    val client by lazy { InferenceClient(this) }
    private val listeners = mutableSetOf<(AppState) -> Unit>()
    var state = AppState(Phase.IDLE, "Ready")
        private set
    private var owner: Any? = null
    private var recorder: AudioRecorder? = null
    private var deliver: ((String) -> Unit)? = null
    private var partial: ((String) -> Unit)? = null
    private var captureService: RecordingService? = null
    val editorDelivery = EditorDelivery()
    val liveTextInsertion get() = preferences.getBoolean("live_text_insertion", true)
    fun setLiveTextInsertion(enabled: Boolean) { if (!busy) preferences.edit().putBoolean("live_text_insertion", enabled).apply() }
    var draftText = ""
    var sessionText = ""
        private set
    var recordingStartedAt = 0L
        private set
    @Volatile private var generation = 0L
    var lastMetrics = ""
        private set
    val busy get() = state.phase in setOf(Phase.DOWNLOADING, Phase.RECORDING, Phase.PROCESSING, Phase.BENCHMARK)
    val recordingActive get() = state.phase == Phase.RECORDING || captureService != null
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
        if (!busy) publish(AppState(Phase.IDLE, if (model.present()) "Ready · " + model.name else "Download a model in the Models tab"))
    }
    fun selectModel(id: String) {
        if (busy || models.none { it.id == id && it.supported }) return
        if (routingEnabled) {
            if (!LanguageRouting.assign(routingLanguage, id, routingModels)) {
                publish(AppState(Phase.ERROR, "This model does not support " + ModelGuide.languageName(routingLanguage))); return
            }
            assignLanguage(routingLanguage, id)
        } else { preferences.edit().putString("model", id).apply(); unload(); refresh() }
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
    private class ModelDownload(val repository: ModelRepository, val token: Long) {
        val cancelled = AtomicBoolean(false)
        var reason = "Download cancelled. Tap Download to retry."
        var service: ModelDownloadService? = null
    }
    private var downloadSequence = 0L
    private var download: ModelDownload? = null
    val downloadingModelID get() = download?.repository?.id
    val downloadCancelling get() = download?.cancelled?.get() == true

    fun installModel(repository: ModelRepository = model) {
        if (busy || !repository.supported) return
        val job = ModelDownload(repository, ++downloadSequence)
        download = job
        publish(AppState(Phase.DOWNLOADING, "Downloading " + guide.name(repository.id) + ": 0%"))
        try {
            startForegroundService(Intent(this, ModelDownloadService::class.java)
                .setAction(ModelDownloadService.START).putExtra("token", job.token))
        } catch (error: Exception) {
            download = null
            publish(AppState(Phase.ERROR, error.message ?: "Could not start model download"))
        }
    }
    internal fun acceptsDownload(token: Long) = download?.token == token
    internal fun beginModelDownload(service: ModelDownloadService, token: Long) {
        val job = download?.takeIf { it.token == token } ?: return
        if (job.service != null) return
        job.service = service
        worker.execute {
            val outcome = runCatching {
                var lastUpdate = 0L
                job.repository.install(cancelled = { job.cancelled.get() }) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 250 || done == total) {
                        lastUpdate = now
                        val percent = (done * 100 / total).toInt()
                        main.post {
                            if (download === job && !job.cancelled.get()) {
                                publish(AppState(Phase.DOWNLOADING, "Downloading " + guide.name(job.repository.id) + ": " + percent + "%", percent))
                                service.progress(percent)
                            }
                        }
                    }
                }
            }
            main.post {
                if (download === job) {
                    download = null
                    service.finish()
                    val message = if (job.cancelled.get()) job.reason else outcome.fold(
                        { guide.name(job.repository.id) + " downloaded. Choose Use model in Models." },
                        { it.message ?: "Download failed. Tap Download to retry." })
                    publish(AppState(if (job.cancelled.get() || outcome.isSuccess) Phase.IDLE else Phase.ERROR, message))
                }
            }
        }
    }
    fun cancelModelDownload(reason: String = "Download cancelled. Tap Download to retry.") {
        val job = download ?: return
        job.reason = reason
        job.cancelled.set(true)
        publish(state.copy(message = "Cancelling model download..."))
        job.service?.cancelling()
    }
    internal fun modelDownloadServiceLost(service: ModelDownloadService) {
        if (download?.service === service) cancelModelDownload("Download interrupted. Tap Download to retry; verified files are kept.")
    }
    fun deleteModel(repository: ModelRepository) {
        if (busy) return
        publish(AppState(Phase.PROCESSING, "Removing model…"))
        worker.execute {
            client.unload(); repository.delete()
            main.post { publish(AppState(Phase.IDLE, "Model removed from device")) }
        }
    }
    fun runBenchmark() {
        if (busy || !model.present()) return
        val repository = model
        publish(AppState(Phase.BENCHMARK, "Preparing benchmark…"))
        worker.execute {
            try {
                client.unload()
                Benchmark.run(this, repository, client) { message -> main.post { publish(AppState(Phase.BENCHMARK, message)) } }
                main.post { publish(AppState(Phase.IDLE, "Benchmark complete. Report saved on device.")) }
            } catch (failure: Throwable) { main.post { publish(AppState(Phase.ERROR, failure.message ?: "Benchmark failed")) } }
            finally { client.unload() }
        }
    }
    fun start(owner: Any, onPartial: (String) -> Unit = {}, onResult: (String) -> Unit) {
        if (busy) return
        if (!model.present()) { publish(AppState(Phase.ERROR, "Download a model first")); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(AppState(Phase.ERROR, "Allow microphone access in the app")); return
        }
        main.removeCallbacks(idleRelease)
        val token = ++generation
        editorDelivery.clear()
        this.owner = owner; deliver = onResult; partial = onPartial; sessionText = ""
        recordingStartedAt = SystemClock.elapsedRealtime()
        publish(AppState(Phase.RECORDING, "Listening"))
        try {
            startForegroundService(Intent(this, RecordingService::class.java)
                .setAction(RecordingService.START).putExtra("generation", token))
        } catch (error: Exception) { captureFailed(token, error.message ?: "Could not start recording") }
    }
    internal val capturedSamples get() = recorder?.samplesWritten ?: 0L
    internal fun ownsCaptureService(service: RecordingService) = captureService === service
    internal fun acceptsCapture(token: Long) = generation == token && state.phase == Phase.RECORDING
    internal fun beginCapture(service: RecordingService, token: Long) {
        if (!acceptsCapture(token)) { service.finish(); return }
        captureService = service
        val repository = model; val base = draftText; val showLive = liveTextInsertion
        try {
            val audio = AudioRecorder(this, recordingLimitSeconds,
                onLimit = { main.post { if (generation == token) stopCurrent() } },
                onError = { message -> main.post { captureFailed(token, message) } })
            recorder = audio; audio.start()
            worker.execute {
                val started = System.nanoTime()
                var text = ""; var peak = 0
                try {
                    LiveAudioChunks(audio.file).use { chunks ->
                        while (generation == token) {
                            val complete = audio.complete
                            val chunk = chunks.next(audio.samplesWritten, complete)
                            if (chunk == null) {
                                if (complete) break
                                Thread.sleep(200); continue
                            }
                            val part = client.transcribe(repository, chunk.samples)
                            if (generation != token) break
                            text = AudioChunks.appendTranscript(text, part.text, chunk.overlapsPrevious)
                            peak = maxOf(peak, part.peakPssKiB)
                            val update = text
                            main.post {
                                if (generation == token) {
                                    sessionText = update
                                    if (showLive) draftText = listOf(base, update).filter { it.isNotBlank() }.joinToString(" ")
                                    partial?.invoke(update)
                                    publish(state.copy(transcript = update))
                                }
                            }
                        }
                    }
                    audio.finish()
                    if (generation == token) {
                        if (warmSeconds == 0 || repository.engine != "sherpa") client.unload()
                        val result = text; val elapsed = (System.nanoTime() - started) / 1_000_000
                        main.post {
                            if (generation == token) {
                                val callback = deliver
                                releaseCapture()
                                lastMetrics = "Last run: $elapsed ms; PSS max " + peak / 1024 + " MiB"
                                draftText = listOf(base, result).filter { it.isNotBlank() }.joinToString(" ")
                                callback?.invoke(result)
                                publish(AppState(Phase.IDLE, if (result.isBlank()) "No speech detected" else "Ready", transcript = result))
                                if (warmSeconds > 0) main.postDelayed(idleRelease, warmSeconds * 1000L)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    client.unload()
                    main.post { captureFailed(token, error.message ?: "Could not transcribe the recording") }
                } finally { audio.discard() }
            }
        } catch (error: Exception) {
            recorder?.discard()
            captureFailed(token, error.message ?: "Could not start the microphone")
        }
    }
    fun stop(requester: Any) { if (isOwner(requester)) stopCurrent() }
    fun stopCurrent() {
        if (state.phase != Phase.RECORDING) return
        // The service may still be starting; invalidate that start instead of leaving an orphan.
        if (recorder == null) { cancelCurrent(); return }
        recorder?.requestStop()
        publish(state.copy(phase = Phase.PROCESSING, message = "Finishing dictation"))
        captureService?.processing()
    }
    fun cancel(requester: Any) { if (isOwner(requester)) cancelCurrent() }
    fun cancelCurrent() {
        if (!recordingActive) return
        generation++
        editorDelivery.clear()
        val audio = recorder
        audio?.requestStop(); releaseCapture()
        worker.execute { try { audio?.discard() } finally { client.unload() } }
        publish(AppState(Phase.IDLE, "Recording cancelled", transcript = sessionText))
    }
    /** Losing a view or editor detaches delivery, never the microphone or recovered draft. */
    fun detach(requester: Any) {
        if (owner === requester) { owner = null; deliver = null; partial = null }
    }
    private fun releaseCapture() {
        recorder = null; owner = null; deliver = null; partial = null
        captureService?.finish(); captureService = null
    }
    internal fun captureFailed(token: Long, message: String) {
        if (generation != token) return
        cancelCurrent()
        publish(AppState(Phase.ERROR, message, transcript = sessionText))
    }
    internal fun captureServiceLost(service: RecordingService) {
        if (captureService === service) captureFailed(generation, "Recording interrupted")
    }
    fun isOwner(requester: Any) = owner === requester
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!busy) unload()
    }
}
