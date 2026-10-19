import AVFoundation
import UIKit
import Foundation
import OwlWhispCore

@MainActor
final class TranscriptionModel: ObservableObject {
    enum Phase { case idle, downloading, recording, processing }
    @Published var phase: Phase = .idle
    @Published var status = "Download a model to get started"
    @Published var progress = 0.0
    @Published var ready = false
    @Published var text = ""
    @Published var benchmarkReport = ""
    private var store: ModelStore?
    private let recognizer = LocalRecognizer()
    private let recorder = AudioRecorder()
    private var job: Task<Void, Never>?
    private var generation = UUID()

    init() {
        do {
            guard let manifestURL = Bundle.main.url(forResource: "parakeet-tdt-0.6b-v3", withExtension: "json", subdirectory: "models") else { throw ModelError.invalidManifest }
            let manifest = try ModelManifest.decode(Data(contentsOf: manifestURL))
            let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            store = ModelStore(manifest: manifest, directory: root.appendingPathComponent("models/" + manifest.id))
        } catch { status = error.localizedDescription }
    }

    func refresh() async {
        guard let store, phase == .idle else { return }
        ready = await store.present()
        status = ready ? "Ready · all processing stays on device" : "Parakeet model required · 639 MiB"
    }

    func install() {
        guard let store, phase == .idle else { return }
        phase = .downloading
        status = "Downloading model…"
        let token = UUID()
        generation = token
        job = Task {
            do {
                try await store.install { value in
                    Task { @MainActor [weak self] in
                        guard self?.generation == token else { return }
                        self?.progress = value
                        self?.status = "Downloading model: " + String(Int(value * 100)) + "%"
                    }
                }
                guard generation == token else { return }
                ready = true; phase = .idle; status = "Model verified. Ready to dictate."
            } catch {
                guard generation == token else { return }
                phase = .idle; status = error.localizedDescription
            }
        }
    }

    func toggleRecording() {
        if phase == .recording { finishRecording(); return }
        guard ready, phase == .idle else { return }
        let token = UUID()
        generation = token
        AVAudioSession.sharedInstance().requestRecordPermission { allowed in
            Task { @MainActor [weak self] in
                guard let self, self.generation == token, UIApplication.shared.applicationState == .active else { return }
                guard allowed else { self.status = "Allow microphone access in iPhone settings."; return }
                do {
                    try self.recorder.start(
                        onLimit: { [weak self] in self?.finishRecording() },
                        onError: { [weak self] message in self?.cancel(); self?.status = message })
                    self.phase = .recording
                    self.status = "Listening… Up to 60 seconds"
                } catch { self.status = error.localizedDescription }
            }
        }
    }

    func finishRecording() {
        guard phase == .recording, let store else { return }
        let audio = recorder.stop()
        phase = .processing
        status = "Transcribing on device…"
        let token = generation
        job = Task {
            do {
                let result = try await recognizer.transcribe(store: store, samples: audio)
                guard generation == token else { return }
                if !result.isEmpty { text += (text.isEmpty ? "" : " ") + result }
                status = result.isEmpty ? "No speech detected" : "Ready"
            } catch {
                guard generation == token else { return }
                status = error.localizedDescription
            }
            if generation == token { phase = .idle }
        }
    }

    func cancel() {
        generation = UUID()
        job?.cancel()
        job = nil
        _ = recorder.stop()
        phase = .idle
    }

    func unload() {
        guard phase == .idle else { return }
        status = "The model is already unloaded from memory"
    }

    func deleteModel() {
        guard phase == .idle, let store else { return }
        phase = .processing
        job = Task {
            do {
                try await store.delete()
                ready = false; status = "Model removed from device"
            } catch { status = error.localizedDescription }
            phase = .idle
        }
    }

    func runBenchmark() {
        guard ready, phase == .idle, let store else { return }
        phase = .processing
        let token = UUID(); generation = token
        job = Task {
            do {
                guard let manifestURL = Bundle.main.url(forResource: "fixtures", withExtension: "json", subdirectory: "benchmark"),
                      let fixtures = try JSONSerialization.jsonObject(with: Data(contentsOf: manifestURL)) as? [[String: Any]]
                else { throw ModelError.invalidManifest }
                var rows = [[String: Any]]()
                for fixture in fixtures {
                    try Task.checkCancellation()
                    guard let name = fixture["file"] as? String, let reference = fixture["transcript"] as? String,
                          let url = Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "benchmark")
                    else { throw ModelError.invalidManifest }
                    guard generation == token else { return }
                    status = "Benchmark: " + name
                    let audio = try AVAudioFile(forReading: url)
                    guard let buffer = AVAudioPCMBuffer(pcmFormat: audio.processingFormat, frameCapacity: AVAudioFrameCount(audio.length)) else { throw ModelError.invalidDownload }
                    try audio.read(into: buffer)
                    guard audio.processingFormat.sampleRate == 16000, let channel = buffer.floatChannelData?[0] else { throw ModelError.invalidDownload }
                    let samples = Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
                    let started = Date()
                    let result = try await recognizer.transcribe(store: store, samples: samples)
                    let elapsed = Date().timeIntervalSince(started)
                    rows.append(["file": name, "reference": reference, "transcript": result,
                                 "wer": BenchmarkMetrics.wordErrorRate(reference: reference, hypothesis: result),
                                 "audio_s": Double(samples.count) / 16000, "elapsed_s": elapsed,
                                 "rtf": elapsed / (Double(samples.count) / 16000)])
                }
                let report: [String: Any] = ["model": "parakeet-tdt-0.6b-v3", "app_version": "0.1.1",
                    "device": UIDevice.current.model, "os": UIDevice.current.systemVersion,
                    "mode": "cold: verification + load + decode; 2 CPU threads", "rows": rows]
                let data = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
                guard generation == token else { return }
                benchmarkReport = String(decoding: data, as: UTF8.self)
                status = "Benchmark complete"
            } catch {
                guard generation == token else { return }
                status = error.localizedDescription
            }
            if generation == token { phase = .idle }
        }
    }

    func backgrounded() {
        if phase == .idle { generation = UUID() }
        if phase == .recording || phase == .processing { cancel(); status = "Recording cancelled" }
    }
}
