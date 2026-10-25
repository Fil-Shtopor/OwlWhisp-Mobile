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
    /// Parakeet is an offline model; decode short phrases while capture continues.
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
