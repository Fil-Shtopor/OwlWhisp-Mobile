import Foundation

public struct RecordingSnapshot: Sendable {
    public let samples: UInt64
    public let complete: Bool
}

/// Capture publishes only flushed samples. Recognition never holds the full recording in RAM.
public final class LiveRecording: @unchecked Sendable {
    public let url: URL
    private let lock = NSLock()
    private var samples: UInt64 = 0
    private var complete = false
    public init(url: URL) { self.url = url }
    public func update(samples: UInt64) { lock.lock(); self.samples = samples; lock.unlock() }
    public func finish() { lock.lock(); complete = true; lock.unlock() }
    public func snapshot() -> RecordingSnapshot {
        lock.lock(); defer { lock.unlock() }
        return RecordingSnapshot(samples: samples, complete: complete)
    }
}

/// A cursor over immutable prefixes of an actively growing PCM16 file.
public final class LivePCM16Chunks {
    private let input: FileHandle
    private var position: UInt64 = 0
    private var coveredEnd: UInt64 = 0
    private var overlaps = false
    public init(url: URL) throws { input = try FileHandle(forReadingFrom: url) }
    deinit { try? input.close() }
    public func next(availableSamples: UInt64, complete: Bool) throws -> AudioChunk? {
        guard availableSamples > position, !(complete && availableSamples <= coveredEnd) else { return nil }
        let window = UInt64(8 * RecordingPolicy.sampleRate)
        if !complete && availableSamples - position < window { return nil }
        let count = Int(min(window, availableSamples - position))
        try input.seek(toOffset: position * 2)
        var data = Data()
        while data.count < count * 2 {
            guard let part = try input.read(upToCount: count * 2 - data.count), !part.isEmpty else { throw AudioError.invalidRecording }
            data.append(part)
        }
        let samples: [Float] = data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
            (0..<count).map { i in Float(Int16(bitPattern: UInt16(bytes[2*i]) | UInt16(bytes[2*i+1]) << 8)) / 32768 }
        }
        let more = !complete || position + UInt64(count) < availableSamples
        let boundary = more ? PCM16Chunks.quietBoundary(samples) : count
        let start = position; let end = start + UInt64(boundary); let previous = overlaps
        overlaps = more && boundary == count
        position = end - (overlaps ? UInt64(RecordingPolicy.sampleRate / 2) : 0)
        coveredEnd = end
        return AudioChunk(samples: boundary == count ? samples : Array(samples.prefix(boundary)), start: start, end: end, overlapsPrevious: previous)
    }
}
