import Foundation

public struct BenchmarkRecord: Decodable {
    public struct Row: Decodable {
        public let file: String
        public let language: String
        public let reference: String
        public let transcript: String
        public let audio_s: Double
        public let elapsed_s: Double
        var clip: BenchmarkMetrics.Clip {
            .init(reference: reference, transcript: transcript, audioSeconds: audio_s, elapsedSeconds: elapsed_s)
        }
    }
    public let model: String
    public let model_version: String
    public let app_version: String
    public let rows: [Row]
    public var summary: BenchmarkMetrics.Summary { BenchmarkMetrics.summarize(rows.map(\.clip)) }
    public var metrics: String {
        Dictionary(grouping: rows, by: \.language).keys.sorted().map { language in
            let clips = rows.filter { $0.language == language }
            let result = BenchmarkMetrics.summarize(clips.map(\.clip))
            return language.uppercased() + String(format: "  WER %.1f%% \u{00b7} RTF %.2f \u{00b7} %d clips",
                result.wordErrorRate * 100, result.rtf, clips.count)
        }.joined(separator: "\n")
    }
}

public struct BenchmarkHistory {
    public let directory: URL
    public init(directory: URL) { self.directory = directory }
    private func safe(_ id: String) -> Bool {
        id.range(of: "^[a-z0-9-]+$", options: .regularExpression) != nil
    }
    public func save(_ data: Data) throws {
        let record = try JSONDecoder().decode(BenchmarkRecord.self, from: data)
        guard safe(record.model), !record.rows.isEmpty else { throw ModelError.invalidManifest }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: directory.appendingPathComponent(record.model + ".json"), options: .atomic)
        try data.write(to: directory.appendingPathComponent("latest.json"), options: .atomic)
    }
    public func load(id: String, version: String) -> BenchmarkRecord? {
        guard safe(id), let data = try? Data(contentsOf: directory.appendingPathComponent(id + ".json")),
              let record = try? JSONDecoder().decode(BenchmarkRecord.self, from: data),
              record.model == id, record.model_version == version else { return nil }
        return record
    }
    public func latestData() -> Data? { try? Data(contentsOf: directory.appendingPathComponent("latest.json")) }
}
