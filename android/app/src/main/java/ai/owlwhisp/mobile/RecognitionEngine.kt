package ai.owlwhisp.mobile

import com.k2fsa.sherpa.onnx.*
import java.io.Closeable
import java.io.File

/** Confined to OwlWhispApp.worker; never accessed concurrently with release(). */
class RecognitionEngine : Closeable {
    private var recognizer: OfflineRecognizer? = null
    private var activeId: String? = null

    internal fun configuration(repository: ModelRepository): OfflineRecognizerConfig {
        val spec = repository.recognition
        fun path(key: String) = spec.optString(key).takeIf { it.isNotEmpty() }?.let {
            require(it.split('/').all { part -> part.matches(Regex("[A-Za-z0-9_.-]+")) && part !in setOf(".", "..") })
            File(repository.directory, it).absolutePath
        } ?: ""
        val model = OfflineModelConfig(tokens = path("tokens"), numThreads = 2, provider = "cpu",
            modelType = spec.optString("modelType"))
        when (spec.getString("family")) {
            "transducer" -> model.transducer = OfflineTransducerModelConfig(encoder = path("encoder"), decoder = path("decoder"), joiner = path("joiner"))
            "whisper" -> model.whisper = OfflineWhisperModelConfig(encoder = path("encoder"), decoder = path("decoder"), language = "", task = "transcribe")
            "moonshine" -> model.moonshine = OfflineMoonshineModelConfig(preprocessor = path("preprocessor"), encoder = path("encoder"),
                uncachedDecoder = path("uncachedDecoder"), cachedDecoder = path("cachedDecoder"))
            "sense_voice" -> model.senseVoice = OfflineSenseVoiceModelConfig(model = path("model"), language = "auto", useInverseTextNormalization = true)
            "omnilingual" -> model.omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = path("model"))
            "qwen3_asr" -> model.qwen3Asr = OfflineQwen3AsrModelConfig(convFrontend = path("convFrontend"), encoder = path("encoder"),
                decoder = path("decoder"), tokenizer = path("tokenizer"), temperature = 0f, topP = 1f)
            else -> error("Unsupported recognition model")
        }
        return OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16_000, featureDim = spec.getInt("featureDim")), modelConfig = model)
    }

    fun transcribe(repository: ModelRepository, samples: FloatArray): String {
        require(samples.size <= RecordingPolicy.CHUNK_SAMPLES) { "Audio chunk exceeds 30 seconds" }
        require(samples.all { it.isFinite() }) { "Invalid audio" }
        val audio = AudioChunks.trimQuietMargins(samples)
        if (audio.isEmpty()) return ""
        if (activeId != repository.id) { close(); activeId = repository.id }
        if (repository.engine != "sherpa") {
            repository.verify()
            val text = NativeEngines.transcribe(repository.engine, repository.directory.absolutePath, audio)
            return if (repository.engine == "whistle") org.json.JSONObject(text).getString("text").trim() else text.trim()
        }
        if (recognizer == null) {
            repository.verify()
            recognizer = OfflineRecognizer(config = configuration(repository))
        }
        val engine = checkNotNull(recognizer)
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(audio, 16_000)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally { stream.release() }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
        activeId = null
    }
}
