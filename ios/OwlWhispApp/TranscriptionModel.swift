import AVFoundation
import Foundation
import OwlWhispCore

@MainActor
final class TranscriptionModel: ObservableObject {
    enum Phase { case idle, downloading, recording, processing }
    @Published var phase: Phase = .idle
    @Published var status = "Загрузите модель, чтобы начать"
    @Published var progress = 0.0
    @Published var ready = false
    @Published var text = ""
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
        status = ready ? "Готово · всё обрабатывается на устройстве" : "Нужна модель Parakeet · 639 МиБ"
    }

    func install() {
        guard let store, phase == .idle else { return }
        phase = .downloading
        status = "Загрузка модели…"
        let token = UUID()
        generation = token
        job = Task {
            do {
                try await store.install { value in
                    Task { @MainActor [weak self] in
                        guard self?.generation == token else { return }
                        self?.progress = value
                        self?.status = "Загрузка модели: " + String(Int(value * 100)) + "%"
                    }
                }
                guard generation == token else { return }
                ready = true; phase = .idle; status = "Модель проверена. Можно диктовать."
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
                guard let self, self.generation == token else { return }
                guard allowed else { self.status = "Разрешите микрофон в настройках iPhone."; return }
                do {
                    try self.recorder.start(
                        onLimit: { [weak self] in self?.finishRecording() },
                        onError: { [weak self] message in self?.cancel(); self?.status = message })
                    self.phase = .recording
                    self.status = "Слушаю… До 60 секунд"
                } catch { self.status = error.localizedDescription }
            }
        }
    }

    func finishRecording() {
        guard phase == .recording, let store else { return }
        let audio = recorder.stop()
        phase = .processing
        status = "Распознаю на устройстве…"
        let token = generation
        job = Task {
            do {
                let result = try await recognizer.transcribe(store: store, samples: audio)
                guard generation == token else { return }
                if !result.isEmpty { text += (text.isEmpty ? "" : " ") + result }
                status = result.isEmpty ? "Речь не обнаружена" : "Готово"
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

    func backgrounded() {
        if phase == .recording || phase == .processing { cancel(); status = "Запись отменена" }
    }
}
