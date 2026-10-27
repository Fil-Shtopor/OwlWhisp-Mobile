import Foundation

public struct ModelGuide: Decodable {
    public struct Entry: Decodable, Identifiable {
        public let id: String
        public let name: String
        public let purpose: String
        public let source: String
    }
    public struct Profile: Decodable, Identifiable {
        public let id: String
        public let name: String
        public let tier: String
        public let examples: String
        public let sources: [String]
    }
    public let basis: String
    public let models: [Entry]
    public let profiles: [Profile]
    public let recommendations: [String: [String: [String]]]
    public func name(_ id: String) -> String { models.first { $0.id == id }?.name ?? id }
    public func purpose(_ id: String) -> String { models.first { $0.id == id }?.purpose ?? "" }
    public static func languageName(_ code: String) -> String {
        Locale(identifier: "en").localizedString(forLanguageCode: code) ?? code
    }
    public static func tier(memoryGiB: Double) -> String {
        memoryGiB <= 4.5 ? "compact" : memoryGiB <= 8.5 ? "balanced" : "roomy"
    }
    public func suggest(language: String, tier: String, coverage: Bool, available: [ModelManifest], smallest: Bool = false) -> [ModelManifest] {
        let key = coverage ? "default" : ["zh", "ja", "ko", "yue"].contains(language) ? "east_asian" : language
        let order = (recommendations[key] ?? recommendations["default"])?[tier] ?? []
        let eligible = available.filter { $0.languages.contains(language) }
        if smallest { return Array(eligible.sorted { $0.downloadBytes < $1.downloadBytes }.prefix(3)) }
        return Array(eligible.sorted {
            let left = order.firstIndex(of: $0.id) ?? Int.max, right = order.firstIndex(of: $1.id) ?? Int.max
            return left == right ? $0.downloadBytes < $1.downloadBytes : left < right
        }.prefix(3))
    }
}
