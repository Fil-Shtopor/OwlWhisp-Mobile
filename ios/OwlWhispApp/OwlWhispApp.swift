import SwiftUI
import UIKit
import AVFoundation
import OwlWhispCore

@main
@MainActor
struct OwlWhispApp: App {
    @StateObject private var model = TranscriptionModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .preferredColorScheme(.dark)
                .tint(Color(red: 0.54, green: 0.71, blue: 0.98))
                .task { await model.refresh() }
                .onChange(of: scenePhase) { phase in if phase == .background { model.backgrounded() } }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { model.audioInterrupted($0) }
        }
    }
}

@MainActor
private struct ContentView: View {
    @ObservedObject var model: TranscriptionModel
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
                        Text(model.selectedModel?.name ?? "").font(.footnote).foregroundStyle(.secondary)
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
                    Section("Local benchmark") {
                        Text("Six short FLEURS EN/RU clips. Each clip uses a cold model load. Lower WER and RTF are better.")
                        Button("Run selected model") { model.runBenchmark() }.disabled(!model.ready || model.phase != .idle)
                        Text(model.status)
                    }
                    if !model.benchmarkReport.isEmpty {
                        Section("JSON report") {
                            ShareLink(item: model.benchmarkReport) { Label("Share", systemImage: "square.and.arrow.up") }
                            Text(model.benchmarkReport).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        }
                    }
                    Section { Text("FLEURS, Conneau et al., 2022 · google/fleurs · CC-BY-4.0. A small comparison set for the same device.").font(.footnote) }
                }.navigationTitle("Benchmark")
            }.tabItem { Label("Benchmark", systemImage: "chart.bar") }
            NavigationStack {
                Form {
                    Section("OwlWhisp Mobile 0.1.4") {
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
    }
}

@MainActor
private struct MobileModelList: View {
    @ObservedObject var model: TranscriptionModel
    var body: some View {
        Form {
            Section { Text(model.status) }
            ForEach(model.models) { item in
                Section(item.name) {
                    Text(String(item.languages.count) + " languages ? " + String(item.downloadBytes / 1024 / 1024) + " MiB")
                    Text(model.downloaded.contains(item.id) ? "Downloaded" : "Not downloaded")
                    Button(model.selectedID == item.id ? "Selected" : "Select") { model.selectModel(item.id) }.disabled(model.phase != .idle)
                    Button(model.downloaded.contains(item.id) ? "Verify / repair" : "Download") { model.install(item.id) }.disabled(model.phase != .idle)
                    if model.downloaded.contains(item.id) {
                        Button("Remove model", role: .destructive) { model.deleteModel(item.id) }.disabled(model.phase != .idle)
                    }
                    Text(item.license + " ? " + item.attribution).font(.footnote)
                }
            }
            if model.phase == .downloading { ProgressView(value: model.progress) }
            Section("Additional Android models") {
                Text("Redux, Whistle and Phonon-2 use separate Android runtimes. Their iOS adapters are not available.")
            }
        }.navigationTitle("Models")
    }
}
