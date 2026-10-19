package ai.owlwhisp.mobile

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

/** Confined to OwlWhispApp.worker; never accessed concurrently with release(). */
class RecognitionEngine : Closeable {
    private var recognizer: OfflineRecognizer? = null
    private var activeId: String? = null

    fun transcribe(repository: ModelRepository, samples: FloatArray): String {
        require(samples.size <= 16_000 * 60) { "Recording exceeds 60 seconds" }
        require(samples.all { it.isFinite() }) { "Invalid audio" }
        if (samples.isEmpty() || sqrt(samples.sumOf { it.toDouble() * it } / samples.size) < 0.0005) return ""
        if (activeId != repository.id) { close(); activeId = repository.id }
        if (repository.engine != "sherpa") {
            repository.verify()
            val text = NativeEngines.transcribe(repository.engine, repository.directory.absolutePath, samples)
            return if (repository.engine == "whistle") org.json.JSONObject(text).getString("text").trim() else text.trim()
        }
        if (recognizer == null) {
            repository.verify()
            fun path(name: String) = File(repository.directory, name).absolutePath
            recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 128),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"), decoder = path("decoder.int8.onnx"), joiner = path("joiner.int8.onnx")
                    ),
                    tokens = path("tokens.txt"), numThreads = 2, provider = "cpu", modelType = "nemo_transducer"
                )
            ))
        }
        val engine = checkNotNull(recognizer)
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, 16_000)
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
