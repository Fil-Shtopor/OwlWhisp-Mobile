import XCTest
@testable import OwlWhispCore

final class BenchmarkMetricsTests: XCTestCase {
    func testNormalizationAndEdits() {
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "Один, два! Ёж.", hypothesis: "один два еж"), 0)
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "one two three", hypothesis: "one three"), 1.0 / 3.0)
        XCTAssertEqual(BenchmarkMetrics.wordErrorRate(reference: "one two", hypothesis: "one three four"), 1)
    }
}
