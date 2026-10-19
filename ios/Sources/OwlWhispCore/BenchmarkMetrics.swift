import Foundation

public enum BenchmarkMetrics {
    public static func wordErrorRate(reference: String, hypothesis: String) -> Double {
        func words(_ text: String) -> [String] {
            text.lowercased().replacingOccurrences(of: "ё", with: "е")
                .components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }
        }
        let expected = words(reference), actual = words(hypothesis)
        var previous = Array(0...actual.count)
        for (i, word) in expected.enumerated() {
            var row = [Int](repeating: 0, count: actual.count + 1)
            row[0] = i + 1
            for j in actual.indices {
                row[j + 1] = min(row[j] + 1, previous[j + 1] + 1, previous[j] + (word == actual[j] ? 0 : 1))
            }
            previous = row
        }
        return Double(previous.last!) / Double(max(expected.count, 1))
    }
}
