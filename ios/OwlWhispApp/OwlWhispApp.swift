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
                        Text("Голос остаётся на устройстве").foregroundStyle(.secondary)
                        Text(model.status).accessibilityAddTraits(.updatesFrequently)
                        Button(action: model.toggleRecording) {
                            Label(model.phase == .recording ? "Остановить и распознать" : "Начать диктовку",
                                  systemImage: model.phase == .recording ? "stop.fill" : "mic.fill")
                                .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 14)
                        }.buttonStyle(.borderedProminent).disabled(!model.ready || (model.phase != .idle && model.phase != .recording))
                        TextEditor(text: $model.text).frame(minHeight: 200).padding(8)
                            .background(.black.opacity(0.2), in: RoundedRectangle(cornerRadius: 12))
                        HStack {
                            Button("Скопировать") { UIPasteboard.general.string = model.text }
                            ShareLink(item: model.text) { Label("Поделиться", systemImage: "square.and.arrow.up") }
                            Button("Очистить") { model.text = "" }
                        }.disabled(model.text.isEmpty)
                        Text("До 60 секунд на запись. Аудио не отправляется на сервер.").font(.footnote).foregroundStyle(.secondary)
                    }.padding(20)
                }.background(background).navigationTitle("Диктовка").navigationBarTitleDisplayMode(.inline)
            }.tabItem { Label("Диктовка", systemImage: "mic") }
            NavigationStack {
                Form {
                    Section("Память") {
                        Text("Модель освобождается после каждой записи. Постоянного фонового процесса нет.")
                        Button("Выгрузить модель") { model.unload() }.disabled(model.phase != .idle)
                    }
                    Section("Ввод текста") {
                        Text("На iPhone диктовка доступна в основном приложении через копирование и меню «Поделиться». Клавиатурное расширение — следующий этап.")
                    }
                    Section("Микрофон") {
                        Button("Открыть настройки iOS") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                }.navigationTitle("Настройки")
            }.tabItem { Label("Настройки", systemImage: "gearshape") }
            NavigationStack {
                Form {
                    Section("Parakeet TDT 0.6B v3") {
                        Text("Обычный Parakeet INT8 · 25 языков, включая русский · 639 МиБ")
                        Text(model.ready ? "На устройстве" : "Не загружена")
                        if model.phase == .downloading { ProgressView(value: model.progress) }
                        Button(model.ready ? "Проверить / восстановить" : "Загрузить") { model.install() }.disabled(model.phase != .idle)
                        if model.ready { Button("Удалить модель", role: .destructive) { model.deleteModel() }.disabled(model.phase != .idle) }
                        Text("NVIDIA · CC-BY-4.0; ONNX export Fangjun Kuang. sherpa-onnx Apache-2.0, ONNX Runtime MIT.").font(.footnote)
                    }
                    Section("Лёгкие модели") {
                        Text("В Android 0.1.0 подключены Redux, Whistle и Phonon-2. Перенос их отдельных движков в iOS ещё не завершён.")
                        Text("Redux: 25 языков. Whistle: EN/DE/FR/ES/IT/NL/PL. Phonon-2: английский.").font(.footnote)
                    }
                }.navigationTitle("Модели")
            }.tabItem { Label("Модели", systemImage: "square.stack.3d.up") }
            NavigationStack {
                Form {
                    Section("Локальный бенчмарк") {
                        Text("6 коротких записей FLEURS EN/RU. Холодная загрузка модели для каждого образца. WER и RTF: меньше — лучше.")
                        Button("Запустить Parakeet") { model.runBenchmark() }.disabled(!model.ready || model.phase != .idle)
                        Text(model.status)
                    }
                    if !model.benchmarkReport.isEmpty {
                        Section("JSON-отчёт") {
                            ShareLink(item: model.benchmarkReport) { Label("Поделиться", systemImage: "square.and.arrow.up") }
                            Text(model.benchmarkReport).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                        }
                    }
                    Section { Text("FLEURS, Conneau et al., 2022 · google/fleurs · CC-BY-4.0. Небольшой набор для сравнения на одном устройстве.").font(.footnote) }
                }.navigationTitle("Бенчмарк")
            }.tabItem { Label("Бенчмарк", systemImage: "chart.bar") }
            NavigationStack {
                Form {
                    Section("OwlWhisp Mobile 0.1.0") {
                        Text(UIDevice.current.model + " · iOS " + UIDevice.current.systemVersion)
                        Text("CPU · 2 потока · sherpa-onnx 1.13.6")
                        Text(model.ready ? "Parakeet загружен на диск" : "Нет модели на диске")
                        Text("После обработки модель не остаётся в памяти.")
                    }
                    Section("Приватность") {
                        Text("Распознавание локальное. Сеть используется для загрузки модели. Аудио и распознанный текст не отправляются на сервер.")
                    }
                    Section("Лицензии") {
                        Text("Приложение Apache-2.0. Веса Parakeet CC-BY-4.0. Исходники и полный список лицензий доступны в репозитории.")
                        Link("OwlWhisp-Mobile", destination: URL(string: "https://github.com/Fil-Shtopor/OwlWhisp-Mobile")!)
                    }
                }.navigationTitle("Диагностика")
            }.tabItem { Label("Диагностика", systemImage: "stethoscope") }
        }
    }
}
