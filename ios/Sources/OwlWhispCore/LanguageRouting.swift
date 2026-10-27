import Foundation

public enum LanguageRouting {
    public static func resolve(enabled: Bool, language: String, assignments: [String: String],
                               manual: String, models: [String: [String]]) -> String {
        guard enabled, let id = assignments[language], models[id]?.contains(language) == true else { return manual }
        return id
    }
    public static func canAssign(language: String, id: String, models: [String: [String]]) -> Bool {
        models[id]?.contains(language) == true
    }
}
