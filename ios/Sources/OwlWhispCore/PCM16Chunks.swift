import Foundation

public enum AudioError: Error, LocalizedError {
    case invalidRecording
    public var errorDescription: String? { "Invalid PCM16 recording." }
}

public struct AudioChunk {
    public let samples: [Float]
    public let start: UInt64
    public let end: UInt64
    public let overlapsPrevious: Bool
}

/// Reads private PCM16 audio using bounded buffers, regardless of recording duration.
public final class PCM16Chunks {
    private let input: FileHandle
    public let totalSamples: UInt64
    private var position: UInt64 = 0
    private var overlaps = false
    public init(url: URL) throws {
        let handle = try FileHandle(forReadingFrom: url)
        let bytes = try handle.seekToEnd()
        guard bytes % 2 == 0 else { try? handle.close(); throw AudioError.invalidRecording }
        input = handle; totalSamples = bytes / 2
    }
    deinit { try? input.close() }
    public func next() throws -> AudioChunk? {
        guard position < totalSamples else { return nil }
        let count = Int(min(UInt64(RecordingPolicy.chunkSamples), totalSamples - position))
        try input.seek(toOffset: position * 2)
        var data = Data()
        while data.count < count * 2 {
            guard let part = try input.read(upToCount: count * 2 - data.count), !part.isEmpty else { throw AudioError.invalidRecording }
            data.append(part)
        }
        let samples: [Float] = data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
            (0..<count).map { i in
                Float(Int16(bitPattern: UInt16(bytes[2 * i]) | (UInt16(bytes[2 * i + 1]) << 8))) / 32768
            }
        }
        let hasMore = position + UInt64(count) < totalSamples
        let boundary = hasMore ? Self.quietBoundary(samples) : count
        let start = position; let end = start + UInt64(boundary); let previous = overlaps
        overlaps = hasMore && boundary == count
        position = end - (overlaps ? UInt64(RecordingPolicy.sampleRate / 2) : 0)
        return AudioChunk(samples: boundary == count ? samples : Array(samples.prefix(boundary)), start: start, end: end, overlapsPrevious: previous)
    }
    /// Prefer at least 200 ms of quiet in the final five seconds of a chunk.
    public static func quietBoundary(_ samples: [Float]) -> Int {
        let window = RecordingPolicy.sampleRate / 50
        var peakEnergy = 0.0
        for start in stride(from: 0, to: samples.count, by: window) {
            let end = min(start + window, samples.count)
            var energy = 0.0
            for i in start..<end { energy += Double(samples[i]) * Double(samples[i]) }
            peakEnergy = max(peakEnergy, energy / Double(end - start))
        }
        let quietEnergy = max(1e-10, peakEnergy * 0.01)
        var run = -1; var bestLength = 0; var best = samples.count
        var cursor = max(0, samples.count - RecordingPolicy.sampleRate * 5)
        while cursor + window <= samples.count {
            var energy = 0.0
            for i in cursor..<(cursor + window) { energy += Double(samples[i]) * Double(samples[i]) }
            if energy / Double(window) <= quietEnergy {
                if run < 0 { run = cursor }
                let length = cursor + window - run
                if length >= RecordingPolicy.sampleRate / 5 && length >= bestLength { bestLength = length; best = run + length / 2 }
            } else { run = -1 }
            cursor += window
        }
        return best
    }
    /// Framewise energy preserves quiet utterances even when a chunk contains long pauses.
    public static func trimQuietMargins(_ samples: [Float]) -> [Float] {
        let window = RecordingPolicy.sampleRate / 10
        var first = -1; var last = 0
        for start in stride(from: 0, to: samples.count, by: window) {
            let end = min(start + window, samples.count)
            var energy = 0.0
            for i in start..<end { energy += Double(samples[i]) * Double(samples[i]) }
            if energy / Double(end - start) >= 0.0005 * 0.0005 {
                if first < 0 { first = start }
                last = end
            }
        }
        if first < 0 { return [] }
        let padding = RecordingPolicy.sampleRate / 2
        let start = max(0, first - padding); let end = min(samples.count, last + padding)
        return start == 0 && end == samples.count ? samples : Array(samples[start..<end])
    }
    public static func appendTranscript(_ existing: String, _ incoming: String, overlaps: Bool) -> String {
        let next = incoming.trimmingCharacters(in: .whitespacesAndNewlines)
        if next.isEmpty { return existing }
        if existing.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return next }
        let words = next.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        var skip = 0
        if overlaps {
            let before = existing.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            func normalized(_ word: String) -> String {
                String(String.UnicodeScalarView(word.lowercased().replacingOccurrences(of: "\u{0451}", with: "\u{0435}").unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }))
            }
            let maximum = min(12, min(before.count, words.count))
            if maximum > 0 {
                for size in stride(from: maximum, through: 1, by: -1) {
                    let suffix = before.suffix(size).map(normalized)
                    if suffix.allSatisfy({ !$0.isEmpty }) && suffix == words.prefix(size).map(normalized) { skip = size; break }
                }
            }
        }
        let tail = words.dropFirst(skip).joined(separator: " ")
        return tail.isEmpty ? existing : existing.trimmingCharacters(in: .whitespacesAndNewlines) + " " + tail
    }
}
