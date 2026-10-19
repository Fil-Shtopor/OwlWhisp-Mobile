import XCTest
@testable import OwlWhispCore

final class BenchmarkMetricsTests: XCTestCase {
    func testNormalizationAndEdits() {
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "\u{041e}\u{0434}\u{0438}\u{043d}, \u{0434}\u{0432}\u{0430}! \u{0401}\u{0436}.", hypothesis: "\u{043e}\u{0434}\u{0438}\u{043d} \u{0434}\u{0432}\u{0430} \u{0435}\u{0436}"), 0)
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "one two three", hypothesis: "one three"), 1.0 / 3.0)
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "one two", hypothesis: "one three four"), 1)
    }
}
