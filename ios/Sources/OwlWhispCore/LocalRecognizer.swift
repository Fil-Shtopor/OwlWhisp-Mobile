import Foundation
import SherpaOnnx

public actor LocalRecognizer {
    public init() {}
    private func hasSpeech(_ samples: [Float]) throws -> Bool {
        guard samples.count <= RecordingPolicy.chunkSamples, samples.allSatisfy({ $0.isFinite }) else { throw AudioError.invalidRecording }
        return !samples.isEmpty && sqrt(samples.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(samples.count)) >= 0.0005
    }
    private func load(store: ModelStore) async throws -> SherpaOnnxOfflineRecognizer {
        try await store.verify(); try Task.checkCancellation()
        let directory = store.directory
        func path(_ name: String) -> String { directory.appendingPathComponent(name).path }
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: 16_000, featureDim: 128),
            modelConfig: sherpaOnnxOfflineModelConfig(
                tokens: path("tokens.txt"),
                transducer: sherpaOnnxOfflineTransducerModelConfig(
                    encoder: path("encoder.int8.onnx"), decoder: path("decoder.int8.onnx"), joiner: path("joiner.int8.onnx")
                ), numThreads: 2, provider: "cpu", modelType: "nemo_transducer"
            )
        )
        return withUnsafePointer(to: &config) { SherpaOnnxOfflineRecognizer(config: $0) }
    }
    public func transcribe(store: ModelStore, samples: [Float]) async throws -> String {
        try Task.checkCancellation()
        guard try hasSpeech(samples) else { return "" }
        let recognizer = try await load(store: store)
        let text = recognizer.decode(samples: samples, sampleRate: 16_000).text
        try Task.checkCancellation()
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    public func transcribeRecording(store: ModelStore, url: URL, progress: @Sendable (Double) -> Void = { _ in }) async throws -> String {
        let chunks = try PCM16Chunks(url: url)
        var recognizer: SherpaOnnxOfflineRecognizer?
        var text = ""
        while let chunk = try chunks.next() {
            try Task.checkCancellation()
            if try hasSpeech(chunk.samples) {
                if recognizer == nil { recognizer = try await load(store: store) }
                let part = recognizer!.decode(samples: chunk.samples, sampleRate: 16_000).text
                try Task.checkCancellation()
                text = PCM16Chunks.appendTranscript(text, part, overlaps: chunk.overlapsPrevious)
            }
            progress(Double(chunk.end) / Double(chunks.totalSamples))
        }
        return text
    }
}
