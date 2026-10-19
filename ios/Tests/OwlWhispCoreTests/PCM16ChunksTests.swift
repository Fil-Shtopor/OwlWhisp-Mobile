import Foundation
import XCTest
@testable import OwlWhispCore

final class PCM16ChunksTests: XCTestCase {
    private func recording(seconds: Int, value: Int16) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pcm16")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url); defer { try? handle.close() }
        var block = Data(count: 16_000 * 2)
        block.withUnsafeMutableBytes { (bytes: UnsafeMutableRawBufferPointer) in
            let unsigned = UInt16(bitPattern: value)
            for i in 0..<16_000 { bytes[2 * i] = UInt8(truncatingIfNeeded: unsigned); bytes[2 * i + 1] = UInt8(truncatingIfNeeded: unsigned >> 8) }
        }
        for _ in 0..<seconds { try handle.write(contentsOf: block) }
        return url
    }
    func testRecordingBeyondOneMinuteIsReadWithoutGapsOrUnboundedChunks() throws {
        let url = try recording(seconds: 80, value: 16000); defer { try? FileManager.default.removeItem(at: url) }
        let reader = try PCM16Chunks(url: url)
        var end: UInt64 = 0; var count = 0
        while let chunk = try reader.next() {
            XCTAssertLessThanOrEqual(chunk.samples.count, RecordingPolicy.chunkSamples)
            XCTAssertLessThanOrEqual(chunk.start, end)
            XCTAssertGreaterThan(chunk.end, end)
            XCTAssertEqual(chunk.overlapsPrevious, count > 0)
            XCTAssertEqual(chunk.samples.first!, Float(16000) / 32768, accuracy: 0.000001)
            end = chunk.end; count += 1
        }
        XCTAssertEqual(end, 80 * 16000); XCTAssertEqual(count, 3)
    }
    func testPauseBoundaryDoesNotDuplicateAudio() throws {
        var audio = [Float](repeating: 0.5, count: RecordingPolicy.chunkSamples)
        for i in (26 * 16000)..<audio.count { audio[i] = 0 }
        XCTAssertEqual(PCM16Chunks.quietBoundary(audio), 28 * 16000)
        let url = try recording(seconds: 80, value: 0); defer { try? FileManager.default.removeItem(at: url) }
        let reader = try PCM16Chunks(url: url)
        var end: UInt64 = 0
        while let chunk = try reader.next() { XCTAssertFalse(chunk.overlapsPrevious); XCTAssertEqual(chunk.start, end); end = chunk.end }
        XCTAssertEqual(end, 80 * 16000)
    }
    func testQuietSpeechSurvivesLongSilentMargins() {
        var samples = [Float](repeating: 0, count: RecordingPolicy.chunkSamples)
        for i in (10 * 16000)..<(12 * 16000) { samples[i] = 0.0007 }
        XCTAssertEqual(PCM16Chunks.trimQuietMargins(samples).count, 3 * 16000)
        XCTAssertTrue(PCM16Chunks.trimQuietMargins([Float](repeating: 0, count: 16000)).isEmpty)
    }
    func testOnlyOverlappingChunksRemoveRepeatedWords() {
        XCTAssertEqual(PCM16Chunks.appendTranscript("Hello WORLD.", "world and more", overlaps: true), "Hello WORLD. and more")
        XCTAssertEqual(PCM16Chunks.appendTranscript("yes", "yes", overlaps: false), "yes yes")
        XCTAssertEqual(PCM16Chunks.appendTranscript("yes", "yes yes", overlaps: true), "yes yes")
        XCTAssertEqual(PCM16Chunks.appendTranscript("one", "two", overlaps: true), "one two")
    }
    func testEmptyAndTruncatedRecordings() throws {
        let url = try recording(seconds: 0, value: 0); defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertNil(try PCM16Chunks(url: url).next())
        try Data([1]).write(to: url)
        XCTAssertThrowsError(try PCM16Chunks(url: url))
    }
    func testUnlimitedIsTheFirstRecordingOption() {
        XCTAssertEqual(RecordingPolicy.limits.first, 0)
        XCTAssertEqual(RecordingPolicy.label(0), "No time limit")
    }
}
