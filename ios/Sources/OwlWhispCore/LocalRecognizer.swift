import Foundation
import SherpaOnnx

public actor LocalRecognizer {
    public init() {}

    public func transcribe(store: ModelStore, samples: [Float]) async throws -> String {
        try Task.checkCancellation()
        guard samples.count <= 16_000 * 60 else { throw ModelError.invalidDownload }
        if samples.isEmpty || sqrt(samples.reduce(0.0) { $0 + Double($1 * $1) } / Double(samples.count)) < 0.0005 { return "" }
        try await store.verify()
        let directory = store.directory
        func path(_ name: String) -> String { directory.appendingPathComponent(name).path }
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: 16_000, featureDim: 128),
            modelConfig: sherpaOnnxOfflineModelConfig(
                tokens: path("tokens.txt"),
                transducer: sherpaOnnxOfflineTransducerModelConfig(
                    encoder: path("encoder.int8.onnx"),
                    decoder: path("decoder.int8.onnx"),
                    joiner: path("joiner.int8.onnx")
                ),
                numThreads: 2, provider: "cpu", modelType: "nemo_transducer"
            )
        )
        // The recognizer is scoped to one job: releasing it also releases its model session.
        let recognizer = withUnsafePointer(to: &config) { SherpaOnnxOfflineRecognizer(config: $0) }
        let text = recognizer.decode(samples: samples, sampleRate: 16_000).text
        try Task.checkCancellation()
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
