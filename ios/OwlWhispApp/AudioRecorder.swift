import AVFoundation
import Foundation

final class AudioRecorder {
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var samples: [Float] = []
    private var active = false
    private var limitSent = false
    private let maximum = 16_000 * 60

    func start(onLimit: @escaping () -> Void, onError: @escaping (String) -> Void) throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement)
        try session.setActive(true)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0,
              let outputFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: format, to: outputFormat) else {
            try? session.setActive(false)
            throw NSError(domain: "OwlWhisp", code: 1, userInfo: [NSLocalizedDescriptionKey: "Микрофон недоступен."])
        }
        samples = []
        samples.reserveCapacity(maximum)
        limitSent = false
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            guard let self else { return }
            let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 16_000 / format.sampleRate)) + 32
            guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else { return }
            var supplied = false
            var error: NSError?
            converter.convert(to: output, error: &error) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true
                status.pointee = .haveData
                return buffer
            }
            if let error { DispatchQueue.main.async { onError(error.localizedDescription) }; return }
            guard let channel = output.floatChannelData?[0] else { return }
            self.lock.lock()
            let count = min(Int(output.frameLength), self.maximum - self.samples.count)
            self.samples.append(contentsOf: UnsafeBufferPointer(start: channel, count: count))
            let reached = self.samples.count >= self.maximum && !self.limitSent
            if reached { self.limitSent = true }
            self.lock.unlock()
            if reached { DispatchQueue.main.async(execute: onLimit) }
        }
        active = true
        do { engine.prepare(); try engine.start() }
        catch { _ = stop(); throw error }
    }

    func stop() -> [Float] {
        guard active else { return [] }
        active = false
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        lock.lock()
        let result = samples
        samples = []
        lock.unlock()
        return result
    }
}
