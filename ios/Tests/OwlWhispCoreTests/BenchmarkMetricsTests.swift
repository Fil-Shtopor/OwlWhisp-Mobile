import XCTest
@testable import OwlWhispCore

final class BenchmarkMetricsTests: XCTestCase {
    func testNormalizationAndEdits() {
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "\u{041e}\u{0434}\u{0438}\u{043d}, \u{0434}\u{0432}\u{0430}! \u{0401}\u{0436}.", hypothesis: "\u{043e}\u{0434}\u{0438}\u{043d} \u{0434}\u{0432}\u{0430} \u{0435}\u{0436}"), 0)
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "one two three", hypothesis: "one three"), 1.0 / 3.0)
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "one two", hypothesis: "one three four"), 1)
    }

    func testSummaryWeightsErrorsByWordsAndSpeedByAudioDuration() {
        let summary = BenchmarkMetrics.summarize([
            .init(reference: "one", transcript: "wrong", audioSeconds: 1, elapsedSeconds: 0.5),
            .init(reference: "one two three four five six seven eight nine",
                  transcript: "one two three four five six seven eight nine", audioSeconds: 9, elapsedSeconds: 1.5)
        ])
        XCTAssertEqual(summary.clipCount, 2)
        XCTAssertEqual(summary.wordErrors, 1)
        XCTAssertEqual(summary.referenceWords, 10)
        XCTAssertEqual(summary.wordErrorRate, 0.1, accuracy: 1e-10)
        XCTAssertEqual(summary.audioSeconds, 10)
        XCTAssertEqual(summary.elapsedSeconds, 2)
        XCTAssertEqual(summary.rtf, 0.2, accuracy: 1e-10)
    }

    func testInsertionsAndEmptyInput() {
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "one", hypothesis: "one two three"), 2)
        XCTAssertEqual(BenchmarkMetrics.wordErrors(reference: "", hypothesis: "one").count, 1)
        let empty = BenchmarkMetrics.summarize([])
        XCTAssertEqual(empty.wordErrorRate, 0)
        XCTAssertEqual(empty.rtf, 0)
    }
}
