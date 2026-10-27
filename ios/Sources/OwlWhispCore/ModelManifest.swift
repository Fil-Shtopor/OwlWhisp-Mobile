import Foundation

public struct ModelFile: Codable, Sendable {
    public let path: String
    public let url: URL
    public let bytes: Int64
    public let sha256: String
}

public struct RecognitionConfiguration: Codable, Sendable {
    public let family: String
    public let featureDim: Int
    public let modelType: String?
    public let tokens: String?
    public let encoder: String?
    public let decoder: String?
    public let joiner: String?
    public let model: String?
    public let preprocessor: String?
    public let uncachedDecoder: String?
    public let cachedDecoder: String?
    public let convFrontend: String?
    public let tokenizer: String?

    var requiredFiles: Set<String> {
        var names = [tokens, encoder, decoder, joiner, model, preprocessor, uncachedDecoder, cachedDecoder, convFrontend].compactMap { $0 }
        if let tokenizer { names += ["merges.txt", "vocab.json", "tokenizer_config.json"].map { tokenizer + "/" + $0 } }
        return Set(names)
    }
    var valid: Bool {
        let required: [String?]
        switch family {
        case "transducer": required = [tokens, encoder, decoder, joiner]
        case "whisper": required = [tokens, encoder, decoder]
        case "moonshine": required = [tokens, encoder, preprocessor, uncachedDecoder, cachedDecoder]
        case "sense_voice", "omnilingual": required = [tokens, model]
        case "qwen3_asr": required = [convFrontend, encoder, decoder, tokenizer]
        default: return false
        }
        return [64, 80, 128].contains(featureDim) && required.allSatisfy { $0.map(ModelManifest.safePath) == true }
    }
}

public struct ModelManifest: Codable, Sendable, Identifiable {
    public struct Artifact: Codable, Sendable {
        public let target: String
        public let files: [ModelFile]
    }
    public let id: String
    public let version: String?
    public let name: String
    public let license: String
    public let attribution: String
    public let languages: [String]
    public let common: [ModelFile]
    public let artifacts: [Artifact]
    public let recognition: RecognitionConfiguration

    public var files: [ModelFile] { common + (artifacts.first { $0.target == "cpu_int8" }?.files ?? []) }
    public var downloadBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }

    static func safePath(_ path: String) -> Bool {
        !path.isEmpty && path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy {
            $0 != "." && $0 != ".." && $0.range(of: "^[a-zA-Z0-9_.-]+$", options: .regularExpression) != nil
        }
    }

    public static func decode(_ data: Data) throws -> ModelManifest {
        let manifest = try JSONDecoder().decode(Self.self, from: data)
        guard safePath(manifest.id), !manifest.id.contains("/"), manifest.recognition.valid,
              manifest.artifacts.filter({ $0.target == "cpu_int8" }).count == 1,
              !manifest.files.isEmpty, Set(manifest.files.map(\.path)).count == manifest.files.count,
              Set(manifest.files.map(\.path)) == manifest.recognition.requiredFiles else { throw ModelError.invalidManifest }
        for file in manifest.files {
            guard file.url.scheme == "https", file.bytes > 0,
                  file.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
                  safePath(file.path) else { throw ModelError.invalidManifest }
        }
        return manifest
    }
}

public enum ModelError: LocalizedError {
    case invalidManifest, invalidDownload, corruptModel, missingModel, emptyRecording
    public var errorDescription: String? {
        switch self {
        case .invalidManifest: return "Could not read the model manifest."
        case .invalidDownload: return "Model download failed. Try again."
        case .corruptModel: return "Model files are corrupt. Download the model again."
        case .missingModel: return "Download a model first."
        case .emptyRecording: return "The recording is empty."
        }
    }
}
