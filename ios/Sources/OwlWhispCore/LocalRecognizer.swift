import Foundation
import SherpaOnnx

public actor LocalRecognizer {
    public init() {}
    private func prepareAudio(_ samples: [Float]) throws -> [Float] {
        guard samples.count <= RecordingPolicy.chunkSamples, samples.allSatisfy({ $0.isFinite }) else { throw AudioError.invalidRecording }
        return PCM16Chunks.trimQuietMargins(samples)
    }
    private func load(store: ModelStore) async throws -> SherpaOnnxOfflineRecognizer {
        try await store.verify(); try Task.checkCancellation()
        let directory = store.directory
        let spec = store.manifest.recognition
        func path(_ name: String?) -> String { name.map { directory.appendingPathComponent($0).path } ?? "" }
        var model = sherpaOnnxOfflineModelConfig(tokens: path(spec.tokens), numThreads: 2, provider: "cpu", modelType: spec.modelType ?? "")
        switch spec.family {
        case "transducer":
            model.transducer = sherpaOnnxOfflineTransducerModelConfig(encoder: path(spec.encoder), decoder: path(spec.decoder), joiner: path(spec.joiner))
        case "whisper":
            model.whisper = sherpaOnnxOfflineWhisperModelConfig(encoder: path(spec.encoder), decoder: path(spec.decoder), language: "", task: "transcribe")
        case "moonshine":
            model.moonshine = sherpaOnnxOfflineMoonshineModelConfig(preprocessor: path(spec.preprocessor), encoder: path(spec.encoder),
                uncachedDecoder: path(spec.uncachedDecoder), cachedDecoder: path(spec.cachedDecoder))
        case "sense_voice":
            model.sense_voice = sherpaOnnxOfflineSenseVoiceModelConfig(model: path(spec.model), language: "auto", useInverseTextNormalization: true)
        case "omnilingual":
            model.omnilingual = sherpaOnnxOfflineOmnilingualAsrCtcModelConfig(model: path(spec.model))
        case "qwen3_asr":
            model.qwen3_asr = sherpaOnnxOfflineQwen3ASRModelConfig(convFrontend: path(spec.convFrontend), encoder: path(spec.encoder),
                decoder: path(spec.decoder), tokenizer: path(spec.tokenizer), temperature: 0, topP: 1)
        default: throw ModelError.invalidManifest
        }
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: 16_000, featureDim: spec.featureDim), modelConfig: model
        )
        return withUnsafePointer(to: &config) { SherpaOnnxOfflineRecognizer(config: $0) }
    }
    public func transcribe(store: ModelStore, samples: [Float]) async throws -> String {
        try Task.checkCancellation()
        let audio = try prepareAudio(samples)
        guard !audio.isEmpty else { return "" }
        let recognizer = try await load(store: store)
        let text = recognizer.decode(samples: audio, sampleRate: 16_000).text
        try Task.checkCancellation()
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    public func transcribeRecording(store: ModelStore, url: URL, progress: @Sendable (Double) -> Void = { _ in }) async throws -> String {
        let chunks = try PCM16Chunks(url: url)
        var recognizer: SherpaOnnxOfflineRecognizer?
        var text = ""
        while let chunk = try chunks.next() {
            try Task.checkCancellation()
            let audio = try prepareAudio(chunk.samples)
            if !audio.isEmpty {
                if recognizer == nil { recognizer = try await load(store: store) }
                let part = recognizer!.decode(samples: audio, sampleRate: 16_000).text
                try Task.checkCancellation()
                text = PCM16Chunks.appendTranscript(text, part, overlaps: chunk.overlapsPrevious)
            }
            progress(Double(chunk.end) / Double(chunks.totalSamples))
        }
        return text
    }
    /// Decode short phrases while capture continues; the selected model is loaded once per recording.
    public func transcribeLive(store: ModelStore, recording: LiveRecording,
                               partial: @Sendable (String) -> Void) async throws -> String {
        let chunks = try LivePCM16Chunks(url: recording.url)
        var recognizer: SherpaOnnxOfflineRecognizer?
        var text = ""
        while true {
            try Task.checkCancellation()
            let snapshot = recording.snapshot()
            guard let chunk = try chunks.next(availableSamples: snapshot.samples, complete: snapshot.complete) else {
                if snapshot.complete { break }
                try await Task.sleep(nanoseconds: 200_000_000); continue
            }
            let audio = try prepareAudio(chunk.samples)
            if !audio.isEmpty {
                if recognizer == nil { recognizer = try await load(store: store) }
                let part = recognizer!.decode(samples: audio, sampleRate: 16_000).text
                try Task.checkCancellation()
                text = PCM16Chunks.appendTranscript(text, part, overlaps: chunk.overlapsPrevious)
            }
            partial(text)
        }
        return text
    }

}
