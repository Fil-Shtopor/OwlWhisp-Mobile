import Foundation
import SwiftUI
import OwlWhispCore

private final class UpdateRedirects: NSObject, URLSessionTaskDelegate {
    private var count = 0
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        count += 1
        completionHandler(count <= 5 && request.url.map(AppRelease.allowed) == true ? request : nil)
    }
}

@MainActor
final class AppUpdateModel: ObservableObject {
    @Published var automatic = UserDefaults.standard.object(forKey: "automaticUpdates") as? Bool ?? true {
        didSet { UserDefaults.standard.set(automatic, forKey: "automaticUpdates") }
    }
    @Published private(set) var available: AvailableAppUpdate?
    @Published private(set) var status = "Not checked yet"
    @Published private(set) var checking = false
    @Published var dismissed = false
    private var lastLaunchCheck = Date.distantPast

    func onLaunch() {
        guard automatic, Date().timeIntervalSince(lastLaunchCheck) >= 3600 else { return }
        lastLaunchCheck = Date()
        check()
    }
    func check() {
        guard !checking else { return }
        checking = true; status = "Checking for updates"
        Task {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 10
            configuration.timeoutIntervalForResource = 30
            let session = URLSession(configuration: configuration, delegate: UpdateRedirects(), delegateQueue: nil)
            defer { session.invalidateAndCancel(); checking = false }
            do {
                var request = URLRequest(url: AppRelease.api)
                request.setValue("OwlWhisp-Mobile", forHTTPHeaderField: "User-Agent")
                request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
                let (bytes, response) = try await session.bytes(for: request)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    throw URLError(.badServerResponse)
                }
                var data = Data()
                for try await byte in bytes {
                    guard data.count < 2 * 1024 * 1024 else { throw ReleaseError.invalidMetadata }
                    data.append(byte)
                }
                let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
                available = try AppRelease.available(data: data, current: current)
                dismissed = false
                status = available.map { "Version \($0.version) is available" } ?? "Up to date"
            } catch { status = "Could not check for updates. " + error.localizedDescription }
        }
    }
}
