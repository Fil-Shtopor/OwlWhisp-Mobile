import Foundation

public enum BenchmarkMetrics {
    public struct WordErrors {
        public let count: Int
        public let referenceWords: Int
        public var rate: Double { Double(count) / Double(max(referenceWords, 1)) }
    }

    public struct Clip {
        public let reference: String
        public let transcript: String
        public let audioSeconds: Double
        public let elapsedSeconds: Double

        public init(reference: String, transcript: String, audioSeconds: Double, elapsedSeconds: Double) {
            self.reference = reference
            self.transcript = transcript
            self.audioSeconds = audioSeconds
            self.elapsedSeconds = elapsedSeconds
        }
    }

    public struct Summary {
        public let clipCount: Int
        public let wordErrors: Int
        public let referenceWords: Int
        public let audioSeconds: Double
        public let elapsedSeconds: Double
        public var wordErrorRate: Double { Double(wordErrors) / Double(max(referenceWords, 1)) }
        public var rtf: Double { audioSeconds > 0 ? elapsedSeconds / audioSeconds : 0 }
    }

    public static func wordErrors(reference: String, hypothesis: String) -> WordErrors {
        func words(_ text: String) -> [String] {
            text.lowercased().replacingOccurrences(of: "\u{0451}", with: "\u{0435}")
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
        return WordErrors(count: previous.last!, referenceWords: expected.count)
    }

    public static func wordErrorRate(reference: String, hypothesis: String) -> Double {
        wordErrors(reference: reference, hypothesis: hypothesis).rate
    }

    public static func summarize(_ clips: [Clip]) -> Summary {
        let scores = clips.map { wordErrors(reference: $0.reference, hypothesis: $0.transcript) }
        return Summary(clipCount: clips.count, wordErrors: scores.reduce(0) { $0 + $1.count },
                       referenceWords: scores.reduce(0) { $0 + $1.referenceWords },
                       audioSeconds: clips.reduce(0) { $0 + $1.audioSeconds },
                       elapsedSeconds: clips.reduce(0) { $0 + $1.elapsedSeconds })
    }
}
