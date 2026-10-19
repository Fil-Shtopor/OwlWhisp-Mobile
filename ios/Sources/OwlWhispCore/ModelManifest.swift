import Foundation

public struct ModelFile: Codable, Sendable {
    public let path: String
    public let url: URL
    public let bytes: Int64
    public let sha256: String
}

public struct ModelManifest: Codable, Sendable {
    public struct Artifact: Codable, Sendable {
        public let target: String
        public let files: [ModelFile]
    }
    public let id: String
    public let name: String
    public let license: String
    public let attribution: String
    public let languages: [String]
    public let common: [ModelFile]
    public let artifacts: [Artifact]

    public var files: [ModelFile] {
        common + (artifacts.first { $0.target == "cpu_int8" }?.files ?? [])
    }

    public var downloadBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }

    public static func decode(_ data: Data) throws -> ModelManifest {
        let manifest = try JSONDecoder().decode(Self.self, from: data)
        guard manifest.id == "parakeet-tdt-0.6b-v3",
              manifest.artifacts.filter({ $0.target == "cpu_int8" }).count == 1,
              Set(manifest.files.map(\.path)) == Set(["tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"]),
              manifest.files.count == 4 else { throw ModelError.invalidManifest }
        for file in manifest.files {
            guard file.url.scheme == "https", file.bytes > 0,
                  file.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
                  file.path.range(of: "^[a-zA-Z0-9_.-]+$", options: .regularExpression) != nil,
                  file.path != ".", file.path != ".." else { throw ModelError.invalidManifest }
        }
        return manifest
    }
}

public enum ModelError: LocalizedError {
    case invalidManifest, invalidDownload, corruptModel, missingModel, emptyRecording
    public var errorDescription: String? {
        switch self {
        case .invalidManifest: return "Не удалось прочитать описание модели."
        case .invalidDownload: return "Ошибка загрузки модели. Попробуйте ещё раз."
        case .corruptModel: return "Файлы модели повреждены. Загрузите модель заново."
        case .missingModel: return "Сначала загрузите модель."
        case .emptyRecording: return "Запись пуста."
        }
    }
}
