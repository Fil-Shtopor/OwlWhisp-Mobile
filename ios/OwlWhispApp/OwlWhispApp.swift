import SwiftUI
import UIKit
import AVFoundation
import OwlWhispCore

@main
@MainActor
struct OwlWhispApp: App {
    @StateObject private var model = TranscriptionModel()
    @StateObject private var updates = AppUpdateModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ContentView(model: model, updates: updates)
                .preferredColorScheme(.dark)
                .tint(Color(red: 0.54, green: 0.71, blue: 0.98))
                .task { await model.refresh(); updates.onLaunch() }
                .onChange(of: scenePhase) { phase in if phase == .background { model.backgrounded() }; if phase == .active { updates.onLaunch() } }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { model.audioInterrupted($0) }
        }
    }
}

@MainActor
private struct ContentView: View {
    @ObservedObject var model: TranscriptionModel
    @ObservedObject var updates: AppUpdateModel
    @State private var showingUpdate = false
    private let background = Color(red: 17/255, green: 19/255, blue: 26/255)
    var body: some View {
        TabView {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        HStack {
                            Image("OwlWhisp").resizable().scaledToFit().frame(width: 52, height: 52)
                            Text("OwlWhisp").font(.largeTitle.bold())
                        }
                        Text(model.status).accessibilityAddTraits(.updatesFrequently)
                        Text(model.modelName(model.selectedID)).font(.footnote).foregroundStyle(.secondary)
                        if model.languageRoutingEnabled {
                            Menu {
                                ForEach(model.languageAssignments.keys.sorted(), id: \.self) { language in
                                    let id = model.languageAssignments[language] ?? ""
                                    Button(ModelGuide.languageName(language) + " - " + model.modelName(id) +
                                           (model.downloaded.contains(id) ? "" : " (download first)")) { model.chooseLanguage(language) }
                                        .disabled(!model.downloaded.contains(id))
                                }
                            } label: { Label(ModelGuide.languageName(model.routingLanguage), systemImage: "globe") }
                                .disabled(model.phase != .idle)
                        }
                        Button(action: model.toggleRecording) {
                            Label(model.phase == .recording ? "Stop dictation" : "Start dictation",
                                  systemImage: model.phase == .recording ? "stop.fill" : "mic.fill")
                                .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 14)
                        }.buttonStyle(.borderedProminent).disabled(!model.ready || (model.phase != .idle && model.phase != .recording))
                        TextEditor(text: $model.text).frame(minHeight: 200).padding(8).disabled(model.phase != .idle)
                            .background(.black.opacity(0.2), in: RoundedRectangle(cornerRadius: 12))
                        HStack {
                            Button("Copy") { UIPasteboard.general.string = model.text }
                            ShareLink(item: model.text) { Label("Share", systemImage: "square.and.arrow.up") }
                            Button("Clear") { model.text = "" }.disabled(model.phase != .idle)
                        }.disabled(model.text.isEmpty)
                        Text("Recording limit: " + RecordingPolicy.label(model.recordingLimitSeconds)).font(.footnote).foregroundStyle(.secondary)
                    }.padding(20)
                }.background(background).navigationTitle("Dictate").navigationBarTitleDisplayMode(.inline)
            }.tabItem { Label("Dictate", systemImage: "mic") }
            NavigationStack {
                Form {
                    LanguageRoutingSettings(model: model)
                    Section("Recording") {
                        Picker("Recording limit", selection: $model.recordingLimitSeconds) {
                            ForEach(RecordingPolicy.limits, id: \.self) { seconds in
                                Text(RecordingPolicy.label(seconds)).tag(seconds)
                            }
                        }.disabled(model.phase != .idle)
                        Toggle("Live text insertion", isOn: $model.liveTextInsertion).disabled(model.phase != .idle)
                        Text("When enabled, text appears in phrases during dictation. When disabled, text appears after Stop.")
                    }
                    Section("Speech models") {
                        NavigationLink("Manage speech models") { MobileModelList(model: model) }
                    }
                    Section("App updates") {
                        NavigationLink("App updates") { UpdateSettings(updates: updates) }
                    }
                    Section("Memory") {
                        Text("The model is released after each dictation.")
                        Button("Unload model") { model.unload() }.disabled(model.phase != .idle)
                    }
                    Section("Text input") {
                        Text("On iPhone, dictate in the main app, then copy or share the text. A keyboard extension is planned.")
                    }
                    Section("Microphone") {
                        Button("Open iOS settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                }.navigationTitle("Settings")
            }.tabItem { Label("Settings", systemImage: "gearshape") }
            NavigationStack {
                MobileModelList(model: model)
            }.tabItem { Label("Models", systemImage: "square.stack.3d.up") }
            NavigationStack {
                Form {
                    Section {
                        Text(model.selectedModel?.name ?? "No model selected")
                        Text(model.benchmarkClipDescription).font(.footnote).foregroundStyle(.secondary)
                        Button("Run benchmark") { model.runBenchmark() }.disabled(!model.ready || model.phase != .idle)
                        Text(model.status)
                    }
                    if let summary = model.benchmarkSummary {
                        Section("Latest result") {
                            Text(model.benchmarkModelName)
                            Text(String(summary.clipCount) + " clips · " + String(format: "%.2f s audio", summary.audioSeconds))
                                .font(.footnote).foregroundStyle(.secondary)
                            LabeledContent("Word error (WER)", value: String(format: "%.1f%%", summary.wordErrorRate * 100))
                            LabeledContent("Speed (RTF)", value: String(format: "%.2f", summary.rtf))
                            LabeledContent("Processing time", value: String(format: "%.2f s", summary.elapsedSeconds))
                            ShareLink(item: model.benchmarkReport) { Label("Share report", systemImage: "square.and.arrow.up") }
                        }
                    }
                }.navigationTitle("Benchmark")
            }.tabItem { Label("Benchmark", systemImage: "chart.bar") }
            NavigationStack {
                Form {
                    Section("OwlWhisp Mobile 0.2.0") {
                        Text(UIDevice.current.model + " · iOS " + UIDevice.current.systemVersion)
                        Text("CPU · 2 threads · sherpa-onnx 1.13.6")
                        Text(model.selectedModel?.name ?? "No model selected")
                        Text(model.ready ? "Selected model downloaded" : "Selected model not downloaded")
                        Text("The model is released from memory after processing.")
                    }
                    Section("Licenses") {
                        Text("App: Apache-2.0. Parakeet weights: CC-BY-4.0. Source code and full license notices are in the repository.")
                        Link("OwlWhisp-Mobile", destination: URL(string: "https://github.com/Fil-Shtopor/OwlWhisp-Mobile")!)
                    }
                }.navigationTitle("Diagnostics")
            }.tabItem { Label("Diagnostics", systemImage: "stethoscope") }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let update = updates.available, !updates.dismissed {
                HStack {
                    Button("OwlWhisp \(update.version) is available") { showingUpdate = true }.font(.subheadline)
                    Spacer()
                    Button { updates.dismissed = true } label: { Image(systemName: "xmark").accessibilityLabel("Later") }
                }.padding(12).background(Color(red: 26/255, green: 29/255, blue: 39/255))
            }
        }
        .sheet(isPresented: $showingUpdate) { NavigationStack { UpdateSettings(updates: updates) } }
    }
}

@MainActor
private struct LanguageRoutingSettings: View {
    @ObservedObject var model: TranscriptionModel
    var body: some View {
        Section("Language models") {
            Toggle("Experimental language switch", isOn: Binding(get: { model.languageRoutingEnabled }, set: model.setRoutingEnabled))
                .disabled(model.phase != .idle)
            if model.languageRoutingEnabled {
                ForEach(model.languageAssignments.keys.sorted(), id: \.self) { language in
                    HStack {
                        Picker(ModelGuide.languageName(language), selection: Binding(
                            get: { model.languageAssignments[language] ?? "" },
                            set: { model.assignLanguage(language, id: $0) })) {
                                ForEach(model.models.filter { $0.languages.contains(language) }) { item in
                                    Text(model.modelName(item.id) + (model.downloaded.contains(item.id) ? "" : " (not downloaded)")).tag(item.id)
                                }
                            }
                        Button(role: .destructive) { model.assignLanguage(language, id: nil) } label: {
                            Image(systemName: "minus.circle").accessibilityLabel("Remove " + ModelGuide.languageName(language))
                        }
                    }.disabled(model.phase != .idle)
                }
                Menu("Add language") {
                    ForEach(model.allLanguages.filter { model.languageAssignments[$0] == nil }, id: \.self) { language in
                        Button(ModelGuide.languageName(language)) {
                            if let item = model.models.first(where: { $0.languages.contains(language) }) { model.assignLanguage(language, id: item.id) }
                        }
                    }
                }.disabled(model.phase != .idle)
                Text("Choose a model per language. Switch between recordings. Multilingual models may still detect the spoken language.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

@MainActor
private struct UpdateSettings: View {
    @ObservedObject var updates: AppUpdateModel
    var body: some View {
        Form {
            Section {
                Text("Installed version: " + (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""))
                Toggle("Check for updates on launch", isOn: $updates.automatic)
                Button("Check for updates") { updates.check() }.disabled(updates.checking)
                Text(updates.status).font(.footnote).foregroundStyle(.secondary)
            }
            if let release = updates.available {
                Section("Version " + release.version.description) {
                    if let ipa = release.ipa {
                        Link("Download IPA (signing required)", destination: ipa)
                    }
                    Link("Release page", destination: release.page)
                    Text("Sign the new IPA with your Apple account, then install it over the existing app. Personal signing is required for each update.")
                        .font(.footnote).foregroundStyle(.secondary)
                    DisclosureGroup("Release notes") { Text(release.notes).font(.footnote).textSelection(.enabled) }
                }
            } else {
                Section { Link("View releases", destination: AppRelease.page) }
            }
        }.navigationTitle("App updates")
    }
}

@MainActor
private struct MobileModelList: View {
    @ObservedObject var model: TranscriptionModel
    var body: some View {
        Form {
            Section {
                NavigationLink("Find a model for your phone and language") { ModelGuideView(model: model) }
                Text(model.status).font(.footnote).foregroundStyle(.secondary)
            }
            Section("Speech models") {
                ForEach(model.models) { item in
                    DisclosureGroup {
                        ModelDetails(model: model, item: item)
                    } label: {
                        ModelSummary(model: model, item: item)
                    }
                }
            }
            if model.phase == .downloading { ProgressView(value: model.progress) }
        }.navigationTitle("Models")
    }
}

@MainActor
private struct ModelSummary: View {
    @ObservedObject var model: TranscriptionModel
    let item: ModelManifest
    private var subtitle: String {
        let languages: String
        if item.languages.count > 5 { languages = String(item.languages.count) + " languages" }
        else { languages = item.languages.map { $0.uppercased() }.joined(separator: ", ") }
        let size = String(item.downloadBytes / 1024 / 1024) + " MiB"
        let state = model.downloaded.contains(item.id) ? "Downloaded" : "Not downloaded"
        return [languages, size, state].joined(separator: " \u{00b7} ")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(model.modelName(item.id)).font(.headline)
                if model.selectedID == item.id {
                    Image(systemName: "checkmark.circle.fill").accessibilityLabel("Active")
                }
            }
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
            Text(model.metrics(item.id)).font(.caption).foregroundStyle(.secondary)
        }.padding(.vertical, 4)
    }
}

@MainActor
private struct ModelDetails: View {
    @ObservedObject var model: TranscriptionModel
    let item: ModelManifest
    var body: some View {
        Text(model.guide?.purpose(item.id) ?? "").font(.footnote)
        Text(item.languages.map(ModelGuide.languageName).joined(separator: ", ")).font(.caption).foregroundStyle(.secondary)
        Button(model.languageRoutingEnabled ? "Use for " + model.routingLanguage.uppercased() : "Use model") { model.selectModel(item.id) }
            .disabled(model.phase != .idle || (model.languageRoutingEnabled && !item.languages.contains(model.routingLanguage)))
        Button(model.downloaded.contains(item.id) ? "Verify / repair" : "Download") { model.install(item.id) }.disabled(model.phase != .idle)
        if model.downloaded.contains(item.id) {
            Button("Remove model", role: .destructive) { model.deleteModel(item.id) }.disabled(model.phase != .idle)
        }
        if let record = model.benchmarkRecords[item.id] {
            Text("Device benchmark \u{00b7} app " + record.app_version + "\n" + record.metrics).font(.caption).foregroundStyle(.secondary)
        } else {
            Text("Select this model and run Benchmark to measure WER and RTF.").font(.caption).foregroundStyle(.secondary)
        }
        DisclosureGroup("License and attribution") { Text(item.license + "\n" + item.attribution).font(.caption) }
    }
}

@MainActor
private struct ModelGuideView: View {
    @ObservedObject var model: TranscriptionModel
    @State private var language = "en"
    @State private var profile = "device"
    @State private var priority = "everyday"
    private var selectedTier: String {
        if priority == "compact" { return "compact" }
        if profile == "device" { return ModelGuide.tier(memoryGiB: model.memoryGiB) }
        return model.guide?.profiles.first { $0.id == profile }?.tier ?? "balanced"
    }
    private var suggestions: [ModelManifest] {
        model.guide?.suggest(language: language, tier: selectedTier, coverage: priority == "coverage",
                             available: model.models, smallest: priority == "compact") ?? []
    }
    var body: some View {
        Form {
            Section {
                Picker("Language", selection: $language) {
                    ForEach(model.allLanguages, id: \.self) { Text(ModelGuide.languageName($0)).tag($0) }
                }
                Picker("Phone profile", selection: $profile) {
                    Text("This phone").tag("device")
                    ForEach(model.guide?.profiles ?? []) { Text($0.name).tag($0.id) }
                }
                if profile == "device" {
                    Text(UIDevice.current.model + String(format: " \u{00b7} %.1f GiB RAM", model.memoryGiB)).font(.caption).foregroundStyle(.secondary)
                } else if let selected = model.guide?.profiles.first(where: { $0.id == profile }) {
                    Text(selected.examples).font(.caption).foregroundStyle(.secondary)
                }
                Picker("Priority", selection: $priority) {
                    Text("Everyday dictation").tag("everyday")
                    Text("Smallest download").tag("compact")
                    Text("Multilingual dictation").tag("coverage")
                }
            }
            if let guide = model.guide {
                Section("Starting points") {
                    ForEach(suggestions) { item in
                        DisclosureGroup(model.modelName(item.id)) { ModelDetails(model: model, item: item) }
                    }
                    Text(guide.basis).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }.navigationTitle("Model guide")
    }
}
