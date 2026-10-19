import AVFoundation
import Foundation
import OwlWhispCore

final class AudioRecorder {
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var output: FileHandle?
    private var recordingURL: URL?
    private var active = false
    private var tapInstalled = false
    private var limitSent = false
    private var framesWritten: Int64 = 0
    private var maximum: Int64 = .max

    func start(limitSeconds: Int, onLimit: @escaping () -> Void, onError: @escaping (String) -> Void) throws {
        precondition(RecordingPolicy.limits.contains(limitSeconds))
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement); try session.setActive(true)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0,
              let outputFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: format, to: outputFormat) else {
            try? session.setActive(false)
            throw NSError(domain: "OwlWhisp", code: 1, userInfo: [NSLocalizedDescriptionKey: "Microphone unavailable."])
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("owlwhisp-recording-" + UUID().uuidString + ".pcm16")
        guard FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.protectionKey: FileProtectionType.complete]) else {
            try? session.setActive(false); throw AudioError.invalidRecording
        }
        do { output = try FileHandle(forWritingTo: url) } catch { try? FileManager.default.removeItem(at: url); try? session.setActive(false); throw error }
        recordingURL = url; framesWritten = 0; limitSent = false
        maximum = limitSeconds == 0 ? .max : Int64(16_000 * limitSeconds)
        active = true
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            guard let self else { return }
            let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 16_000 / format.sampleRate)) + 32
            guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else { return }
            var supplied = false; var error: NSError?
            converter.convert(to: output, error: &error) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true; status.pointee = .haveData; return buffer
            }
            if let error { DispatchQueue.main.async { onError(error.localizedDescription) }; return }
            guard let channel = output.floatChannelData?[0] else { return }
            self.lock.lock()
            guard self.active, !self.limitSent else { self.lock.unlock(); return }
            let count = Int(min(Int64(output.frameLength), self.maximum - self.framesWritten))
            var bytes = Data(count: count * 2)
            bytes.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
                for i in 0..<count {
                    let sample = channel[i].isFinite ? channel[i] : 0
                    let value = Int16((max(-1, min(1, sample)) * 32767).rounded())
                    let unsigned = UInt16(bitPattern: value)
                    raw[2 * i] = UInt8(truncatingIfNeeded: unsigned); raw[2 * i + 1] = UInt8(truncatingIfNeeded: unsigned >> 8)
                }
            }
            do { try self.output?.write(contentsOf: bytes); self.framesWritten += Int64(count) }
            catch { self.limitSent = true; self.lock.unlock(); DispatchQueue.main.async { onError(error.localizedDescription) }; return }
            let reached = self.framesWritten >= self.maximum
            if reached { self.limitSent = true }
            self.lock.unlock()
            if reached { DispatchQueue.main.async(execute: onLimit) }
        }
        tapInstalled = true
        do { engine.prepare(); try engine.start() } catch { cancel(); throw error }
    }
    private func stopCapture() {
        if tapInstalled { engine.inputNode.removeTap(onBus: 0); tapInstalled = false }
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    /// Transfers a completed private PCM16 file to the transcription job.
    func finish() throws -> URL {
        stopCapture(); lock.lock(); defer { lock.unlock() }
        active = false
        guard let url = recordingURL else { throw AudioError.invalidRecording }
        do { try output?.synchronize(); try output?.close() }
        catch { output = nil; recordingURL = nil; try? FileManager.default.removeItem(at: url); throw error }
        output = nil; recordingURL = nil
        return url
    }
    func cancel() {
        stopCapture(); lock.lock(); defer { lock.unlock() }
        active = false; try? output?.close(); output = nil
        if let url = recordingURL { try? FileManager.default.removeItem(at: url) }
        recordingURL = nil
    }
    deinit { cancel() }
}
