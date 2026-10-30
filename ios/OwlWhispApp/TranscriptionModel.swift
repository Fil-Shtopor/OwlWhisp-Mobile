import AVFoundation
import UIKit
import Foundation
import OwlWhispCore

private struct BenchmarkFixture: Decodable {
    let file: String
    let language: String
    let transcript: String
}

@MainActor
final class TranscriptionModel: ObservableObject {
    enum Phase { case idle, downloading, recording, processing }
    @Published var phase: Phase = .idle
    @Published var status = "Download a model to get started"
    @Published var progress = 0.0
    @Published private(set) var downloadingModelID: String?
    @Published var ready = false
    @Published var text = ""
    @Published var benchmarkReport = ""
    @Published var benchmarkSummary: BenchmarkMetrics.Summary?
    @Published var benchmarkModelName = ""
    private var benchmarkFixtures: [BenchmarkFixture] = []
    var benchmarkClipDescription: String {
        let clips = benchmarkFixtures.filter { selectedModel?.languages.contains($0.language) == true }
        let counts = Dictionary(grouping: clips, by: \.language)
        let description = counts.keys.sorted().map { language in
            let count = counts[language]!.count
            let name = Locale(identifier: "en").localizedString(forLanguageCode: language) ?? language
            return String(count) + " " + name + (count == 1 ? " clip" : " clips")
        }.joined(separator: " · ")
        return description.isEmpty ? "No matching clips" : description
    }
    @Published var recordingLimitSeconds = 0 {
        didSet { UserDefaults.standard.set(recordingLimitSeconds, forKey: "recordingLimitSeconds") }
    }
    @Published var models: [ModelManifest] = []
    @Published var downloaded: Set<String> = []
    @Published private var manualModelID = UserDefaults.standard.string(forKey: "selectedModel") ?? "parakeet-tdt-0.6b-v3"
    @Published private(set) var languageRoutingEnabled = UserDefaults.standard.bool(forKey: "languageRouting")
    @Published private(set) var routingLanguage = UserDefaults.standard.string(forKey: "routingLanguage") ?? "en"
    @Published private(set) var languageAssignments = UserDefaults.standard.dictionary(forKey: "languageModels") as? [String: String] ?? [:]
    @Published private(set) var benchmarkRecords: [String: BenchmarkRecord] = [:]
    private(set) var guide: ModelGuide?
    private var history: BenchmarkHistory?
    private var routingModels: [String: [String]] { Dictionary(uniqueKeysWithValues: models.map { ($0.id, $0.languages) }) }
    var selectedID: String {
        LanguageRouting.resolve(enabled: languageRoutingEnabled, language: routingLanguage,
            assignments: languageAssignments, manual: manualModelID, models: routingModels)
    }
    var allLanguages: [String] { Set(models.flatMap(\.languages)).sorted { ModelGuide.languageName($0) < ModelGuide.languageName($1) } }
    func modelName(_ id: String) -> String { guide?.name(id) ?? models.first { $0.id == id }?.name ?? id }
    func metrics(_ id: String) -> String { benchmarkRecords[id]?.metrics ?? "WER \u{2014} \u{00b7} RTF \u{2014}" }
    var memoryGiB: Double { Double(ProcessInfo.processInfo.physicalMemory) / 1073741824 }
    private func persistRouting() {
        UserDefaults.standard.set(languageRoutingEnabled, forKey: "languageRouting")
        UserDefaults.standard.set(routingLanguage, forKey: "routingLanguage")
        UserDefaults.standard.set(languageAssignments, forKey: "languageModels")
        ready = downloaded.contains(selectedID)
        Task { await refresh() }
    }
    func setRoutingEnabled(_ enabled: Bool) {
        guard phase == .idle else { return }
        languageRoutingEnabled = enabled
        if languageAssignments[routingLanguage] == nil { routingLanguage = languageAssignments.keys.sorted().first ?? "en" }
        persistRouting()
    }
    func assignLanguage(_ language: String, id: String?) {
        guard phase == .idle else { return }
        if let id, !LanguageRouting.canAssign(language: language, id: id, models: routingModels) { return }
        languageAssignments[language] = id
        if languageAssignments.isEmpty { languageRoutingEnabled = false }
        if languageAssignments[routingLanguage] == nil { routingLanguage = languageAssignments.keys.sorted().first ?? "en" }
        persistRouting()
    }
    func chooseLanguage(_ language: String) {
        guard phase == .idle, languageRoutingEnabled, let id = languageAssignments[language],
              LanguageRouting.canAssign(language: language, id: id, models: routingModels) else { return }
        guard downloaded.contains(id) else { status = "Download " + modelName(id) + " in Models first"; return }
        routingLanguage = language
        persistRouting()
    }
    @Published var liveTextInsertion = UserDefaults.standard.object(forKey: "liveTextInsertion") as? Bool ?? true {
        didSet { UserDefaults.standard.set(liveTextInsertion, forKey: "liveTextInsertion") }
    }
    var selectedModel: ModelManifest? { models.first { $0.id == selectedID } }
    private var stores: [String: ModelStore] = [:]
    private var store: ModelStore? { stores[selectedID] }
    private let recognizer = LocalRecognizer()
    private let recorder = AudioRecorder()
    private var job: Task<Void, Never>?
    private var generation = UUID()
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var liveLength = 0

    init() {
        let savedLimit = UserDefaults.standard.integer(forKey: "recordingLimitSeconds")
        recordingLimitSeconds = RecordingPolicy.limits.contains(savedLimit) ? savedLimit : 0
        let temporary = FileManager.default.temporaryDirectory
        if let files = try? FileManager.default.contentsOfDirectory(at: temporary, includingPropertiesForKeys: nil) {
            for file in files where file.lastPathComponent.hasPrefix("owlwhisp-recording-") && file.pathExtension == "pcm16" { try? FileManager.default.removeItem(at: file) }
        }
        do {
            guard let catalogURL = Bundle.main.url(forResource: "catalog", withExtension: "json", subdirectory: "models"),
                  let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: catalogURL)) as? [String: [String]],
                  let ids = catalog["ios"] else { throw ModelError.invalidManifest }
            let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            for id in ids {
                guard let url = Bundle.main.url(forResource: id, withExtension: "json", subdirectory: "models") else { throw ModelError.invalidManifest }
                let manifest = try ModelManifest.decode(Data(contentsOf: url))
                models.append(manifest)
                stores[id] = ModelStore(manifest: manifest, directory: root.appendingPathComponent("models/" + id))
            }
            if stores[manualModelID] == nil { manualModelID = "parakeet-tdt-0.6b-v3" }
            if UserDefaults.standard.object(forKey: "languageModels") == nil {
                let manual = models.first { $0.id == manualModelID }
                languageAssignments = ["en": manual?.languages.contains("en") == true ? manualModelID : "moonshine-tiny-en",
                                       "ru": manual?.languages.contains("ru") == true ? manualModelID : "gigaam-v3-ru"]
            }
            guard let guideURL = Bundle.main.url(forResource: "model-guide", withExtension: "json") else { throw ModelError.invalidManifest }
            guide = try JSONDecoder().decode(ModelGuide.self, from: Data(contentsOf: guideURL))
            let history = BenchmarkHistory(directory: root.appendingPathComponent("benchmarks"))
            self.history = history
            for model in models {
                if let record = history.load(id: model.id, version: model.version ?? "") { benchmarkRecords[model.id] = record }
            }
            if let data = history.latestData(), let record = try? JSONDecoder().decode(BenchmarkRecord.self, from: data),
               benchmarkRecords[record.model] != nil {
                benchmarkReport = String(decoding: data, as: UTF8.self)
                benchmarkSummary = record.summary
                benchmarkModelName = modelName(record.model)
            }
            guard let fixturesURL = Bundle.main.url(forResource: "fixtures", withExtension: "json", subdirectory: "benchmark")
            else { throw ModelError.invalidManifest }
            benchmarkFixtures = try JSONDecoder().decode([BenchmarkFixture].self, from: Data(contentsOf: fixturesURL))
        } catch { status = error.localizedDescription }
    }

    func refresh() async {
        guard let store, phase == .idle else { return }
        let selection = selectedID
        for (id, item) in stores {
            if await item.present() { downloaded.insert(id) } else { downloaded.remove(id) }
        }
        guard phase == .idle, selectedID == selection else { return }
        let present = await store.present()
        guard phase == .idle, selectedID == selection else { return }
        ready = present
        status = ready ? "Ready \u{00b7} " + store.manifest.name : "Download a model in Models"
    }

    func selectModel(_ id: String) {
        guard phase == .idle, stores[id] != nil else { return }
        if languageRoutingEnabled {
            guard LanguageRouting.canAssign(language: routingLanguage, id: id, models: routingModels) else {
                status = "This model does not support " + ModelGuide.languageName(routingLanguage); return
            }
            assignLanguage(routingLanguage, id: id)
        } else {
            manualModelID = id
            UserDefaults.standard.set(id, forKey: "selectedModel")
            ready = downloaded.contains(id)
            Task { await refresh() }
        }
    }

    func install(_ id: String? = nil) {
        guard let store = stores[id ?? selectedID], phase == .idle else { return }
        phase = .downloading
        downloadingModelID = store.manifest.id
        progress = 0
        status = "Downloading model…"
        let token = UUID()
        generation = token
        job = Task {
            defer { if generation == token { downloadingModelID = nil } }
            do {
                try await store.install { value in
                    Task { @MainActor [weak self] in
                        guard self?.generation == token else { return }
                        self?.progress = value
                        self?.status = "Downloading model: " + String(Int(value * 100)) + "%"
                    }
                }
                guard generation == token else { return }
                downloaded.insert(store.manifest.id); ready = downloaded.contains(selectedID); phase = .idle; status = modelName(store.manifest.id) + " downloaded. Choose Use model in Models."
            } catch {
                guard generation == token else { return }
                phase = .idle; status = error.localizedDescription
            }
        }
    }

    func toggleRecording() {
        if phase == .recording { finishRecording(); return }
        guard ready, phase == .idle, let store else { return }
        let token = UUID()
        generation = token
        AVAudioSession.sharedInstance().requestRecordPermission { allowed in
            Task { @MainActor [weak self] in
                guard let self, self.generation == token, UIApplication.shared.applicationState == .active else { return }
                guard allowed else { self.status = "Allow microphone access in iPhone settings."; return }
                do {
                    let recording = try self.recorder.start(limitSeconds: self.recordingLimitSeconds,
                        onLimit: { [weak self] in guard self?.generation == token else { return }; self?.finishRecording() },
                        onError: { [weak self] message in guard self?.generation == token else { return }; self?.cancel(); self?.status = message })
                    self.phase = .recording
                    self.status = "Listening"
                    let base = self.text
                    let showLive = self.liveTextInsertion
                    self.liveLength = 0
                    self.job = Task {
                        defer {
                            try? FileManager.default.removeItem(at: recording.url)
                            if self.generation == token { self.endBackgroundTask() }
                        }
                        do {
                            let result = try await self.recognizer.transcribeLive(store: store, recording: recording) { value in
                                Task { @MainActor [weak self] in
                                    guard let self, showLive, self.generation == token, self.phase == .recording || self.phase == .processing, value.count >= self.liveLength else { return }
                                    self.liveLength = value.count
                                    self.text = [base, value].filter { !$0.isEmpty }.joined(separator: " ")
                                }
                            }
                            guard self.generation == token else { return }
                            self.text = [base, result].filter { !$0.isEmpty }.joined(separator: " ")
                            self.phase = .idle
                            self.status = result.isEmpty ? "No speech detected" : "Ready"
                        } catch {
                            guard self.generation == token else { return }
                            self.recorder.cancel(); self.phase = .idle; self.status = error.localizedDescription
                        }
                    }
                } catch { self.status = error.localizedDescription }
            }
        }
    }

    func finishRecording() {
        guard phase == .recording else { return }
        beginBackgroundTask()
        do {
            _ = try recorder.finish()
            phase = .processing; status = "Finishing dictation"
        } catch {
            cancel(); status = error.localizedDescription
        }
    }

    private func beginBackgroundTask() {
        guard backgroundTask == .invalid else { return }
        let token = generation
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Finish dictation") { [weak self] in
            // Keep the file, cursor and task intact if iOS suspends us. They resume on return.
            Task { @MainActor [weak self] in if self?.generation == token { self?.endBackgroundTask() } }
        }
    }
    private func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask); backgroundTask = .invalid
        }
    }

    func cancel() {
        generation = UUID()
        job?.cancel()
        job = nil
        recorder.cancel()
        endBackgroundTask()
        phase = .idle
    }

    func unload() {
        guard phase == .idle else { return }
        status = "The model is already unloaded from memory"
    }

    func deleteModel(_ id: String? = nil) {
        guard phase == .idle, let store = stores[id ?? selectedID] else { return }
        phase = .processing
        job = Task {
            do {
                try await store.delete()
                downloaded.remove(store.manifest.id); ready = downloaded.contains(selectedID); status = "Model removed from device"
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
                let fixtures = benchmarkFixtures.filter { store.manifest.languages.contains($0.language) }
                guard !fixtures.isEmpty else { throw ModelError.invalidManifest }
                var rows = [[String: Any]]()
                var clips = [BenchmarkMetrics.Clip]()
                for fixture in fixtures {
                    try Task.checkCancellation()
                    let name = fixture.file, reference = fixture.transcript
                    guard let url = Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "benchmark")
                    else { throw ModelError.invalidManifest }
                    guard generation == token else { return }
                    status = "Clip " + String(rows.count + 1) + " of " + String(fixtures.count)
                    let audio = try AVAudioFile(forReading: url)
                    guard let buffer = AVAudioPCMBuffer(pcmFormat: audio.processingFormat, frameCapacity: AVAudioFrameCount(audio.length)) else { throw ModelError.invalidDownload }
                    try audio.read(into: buffer)
                    guard audio.processingFormat.sampleRate == 16000, let channel = buffer.floatChannelData?[0] else { throw ModelError.invalidDownload }
                    let samples = Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
                    let started = Date()
                    let result = try await recognizer.transcribe(store: store, samples: samples)
                    let elapsed = Date().timeIntervalSince(started)
                    let duration = Double(samples.count) / 16000
                    let errors = BenchmarkMetrics.wordErrors(reference: reference, hypothesis: result)
                    clips.append(.init(reference: reference, transcript: result, audioSeconds: duration, elapsedSeconds: elapsed))
                    rows.append(["file": name, "language": fixture.language, "reference": reference, "transcript": result,
                                 "wer": errors.rate, "word_errors": errors.count, "reference_words": errors.referenceWords,
                                 "audio_s": duration, "elapsed_s": elapsed, "rtf": elapsed / duration])
                }
                let summary = BenchmarkMetrics.summarize(clips)
                let totals: [String: Any] = ["clips": summary.clipCount, "word_errors": summary.wordErrors,
                    "reference_words": summary.referenceWords, "wer": summary.wordErrorRate,
                    "audio_s": summary.audioSeconds, "elapsed_s": summary.elapsedSeconds, "rtf": summary.rtf]
                let report: [String: Any] = ["model": store.manifest.id, "model_version": store.manifest.version ?? "",
                    "app_version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
                    "device": UIDevice.current.model, "os": UIDevice.current.systemVersion,
                    "mode": "cold: verification + load + decode; 2 CPU threads",
                    "summary": totals,
                    "rows": rows]
                let data = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
                guard generation == token else { return }
                try history?.save(data)
                if let record = history?.load(id: store.manifest.id, version: store.manifest.version ?? "") {
                    benchmarkRecords[store.manifest.id] = record
                }
                benchmarkReport = String(decoding: data, as: UTF8.self)
                benchmarkSummary = summary
                benchmarkModelName = modelName(store.manifest.id)
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
        if phase == .processing { beginBackgroundTask() }
    }

    func audioInterrupted(_ notification: Notification) {
        guard let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              type == AVAudioSession.InterruptionType.began.rawValue else { return }
        if phase == .recording { finishRecording() }
    }
}
