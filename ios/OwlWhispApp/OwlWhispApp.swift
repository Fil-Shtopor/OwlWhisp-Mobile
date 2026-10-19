import SwiftUI
import UIKit
import AVFoundation

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
                .onChange(of: scenePhase) { phase in if phase != .active { model.backgrounded() } }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { _ in model.backgrounded() }
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
                        Text("Your voice stays on your device").foregroundStyle(.secondary)
                        Text(model.status).accessibilityAddTraits(.updatesFrequently)
                        Button(action: model.toggleRecording) {
                            Label(model.phase == .recording ? "Stop and transcribe" : "Start dictation",
                                  systemImage: model.phase == .recording ? "stop.fill" : "mic.fill")
                                .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 14)
                        }.buttonStyle(.borderedProminent).disabled(!model.ready || (model.phase != .idle && model.phase != .recording))
                        TextEditor(text: $model.text).frame(minHeight: 200).padding(8)
                            .background(.black.opacity(0.2), in: RoundedRectangle(cornerRadius: 12))
                        HStack {
                            Button("Copy") { UIPasteboard.general.string = model.text }
                            ShareLink(item: model.text) { Label("Share", systemImage: "square.and.arrow.up") }
                            Button("Clear") { model.text = "" }
                        }.disabled(model.text.isEmpty)
                        Text("Up to 60 seconds per recording. Audio is not uploaded.").font(.footnote).foregroundStyle(.secondary)
                    }.padding(20)
                }.background(background).navigationTitle("Dictate").navigationBarTitleDisplayMode(.inline)
            }.tabItem { Label("Dictate", systemImage: "mic") }
            NavigationStack {
                Form {
                    Section("Memory") {
                        Text("The model is released after every recording. There is no persistent background process.")
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
                Form {
                    Section("Parakeet TDT 0.6B v3") {
                        Text("Ordinary Parakeet INT8 · 25 languages, including Russian · 639 MiB")
                        Text(model.ready ? "On device" : "Not downloaded")
                        if model.phase == .downloading { ProgressView(value: model.progress) }
                        Button(model.ready ? "Verify / repair" : "Download") { model.install() }.disabled(model.phase != .idle)
                        if model.ready { Button("Remove model", role: .destructive) { model.deleteModel() }.disabled(model.phase != .idle) }
                        Text("NVIDIA · CC-BY-4.0; ONNX export Fangjun Kuang. sherpa-onnx Apache-2.0, ONNX Runtime MIT.").font(.footnote)
                    }
                    Section("Small models") {
                        Text("Android supports Redux, Whistle and Phonon-2. Their separate runtimes have not yet been ported to iOS.")
                        Text("Redux: 25 languages. Whistle: EN/DE/FR/ES/IT/NL/PL. Phonon-2: English.").font(.footnote)
                    }
                }.navigationTitle("Models")
            }.tabItem { Label("Models", systemImage: "square.stack.3d.up") }
            NavigationStack {
                Form {
                    Section("Local benchmark") {
                        Text("Six short FLEURS EN/RU clips. Each clip uses a cold model load. Lower WER and RTF are better.")
                        Button("Run Parakeet") { model.runBenchmark() }.disabled(!model.ready || model.phase != .idle)
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
                    Section("OwlWhisp Mobile 0.1.1") {
                        Text(UIDevice.current.model + " · iOS " + UIDevice.current.systemVersion)
                        Text("CPU · 2 threads · sherpa-onnx 1.13.6")
                        Text(model.ready ? "Parakeet downloaded to device" : "No model on device")
                        Text("The model is released from memory after processing.")
                    }
                    Section("Privacy") {
                        Text("Recognition runs locally. The network is used to download the model. Audio and transcripts are not uploaded.")
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
