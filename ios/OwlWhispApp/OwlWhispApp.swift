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
                .task { await model.refresh() }
                .onChange(of: scenePhase) { phase in if phase != .active { model.backgrounded() } }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { _ in
                    model.backgrounded()
                }
        }
    }
}

@MainActor
private struct ContentView: View {
    @ObservedObject var model: TranscriptionModel
    private let ink = Color(red: 0.11, green: 0.24, blue: 0.21)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Label("OwlWhisp", systemImage: "waveform")
                    .font(.largeTitle.bold()).foregroundStyle(ink)
                Text("Голос остаётся на устройстве").font(.title3)
                VStack(alignment: .leading, spacing: 10) {
                    Text("Parakeet 0.6B v3").font(.headline)
                    Text("25 языков, включая русский · около 639 МиБ").foregroundStyle(.secondary)
                    if model.phase == .downloading { ProgressView(value: model.progress) }
                    Button(model.ready ? "Проверить модель" : "Загрузить модель") { model.install() }
                        .disabled(model.phase != .idle)
                }.padding().frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white, in: RoundedRectangle(cornerRadius: 18))
                Text(model.status).accessibilityAddTraits(.updatesFrequently)
                Button(action: model.toggleRecording) {
                    Label(model.phase == .recording ? "Остановить и распознать" : "Начать диктовку",
                          systemImage: model.phase == .recording ? "stop.fill" : "mic.fill")
                        .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 16)
                }.buttonStyle(.borderedProminent).tint(ink)
                    .disabled(!model.ready || (model.phase != .idle && model.phase != .recording))
                if model.phase == .processing || model.phase == .downloading {
                    Button("Отмена", role: .cancel) { model.cancel() }
                }
                TextEditor(text: $model.text).frame(minHeight: 180)
                    .padding(8).background(.white, in: RoundedRectangle(cornerRadius: 14))
                    .accessibilityLabel("Распознанный текст")
                HStack {
                    Button("Скопировать") { UIPasteboard.general.string = model.text }
                    Spacer()
                    ShareLink(item: model.text) { Label("Поделиться", systemImage: "square.and.arrow.up") }
                }.disabled(model.text.isEmpty)
                DisclosureGroup("Модель и лицензии") {
                    Text("Parakeet TDT 0.6B v3 — NVIDIA, CC-BY-4.0. Экспорт ONNX — Fangjun Kuang. sherpa-onnx — Apache-2.0; ONNX Runtime — MIT.").font(.footnote)
                    Link("Модель и условия использования", destination: URL(string: "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3")!)
                }
                Text("После загрузки модели интернет не нужен. Аудио не сохраняется. Первый запуск модели может занять несколько секунд.")
                    .font(.footnote).foregroundStyle(.secondary)
            }.padding(24)
        }.background(Color(red: 0.96, green: 0.96, blue: 0.94))
    }
}
