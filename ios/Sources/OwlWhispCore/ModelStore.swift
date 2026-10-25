import Foundation
import CryptoKit

public actor ModelStore {
    public nonisolated let manifest: ModelManifest
    public nonisolated let directory: URL

    public init(manifest: ModelManifest, directory: URL) {
        self.manifest = manifest
        self.directory = directory
    }

    public func present() -> Bool {
        manifest.files.allSatisfy { spec in
            let path = directory.appendingPathComponent(spec.path)
            let size = try? path.resourceValues(forKeys: [.fileSizeKey]).fileSize
            return size.map(Int64.init) == spec.bytes
        }
    }

    public func delete() throws {
        for file in manifest.files {
            let url = directory.appendingPathComponent(file.path)
            if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        }
    }

    public func verify() throws {
        for file in manifest.files {
            guard try valid(directory.appendingPathComponent(file.path), spec: file) else { throw ModelError.corruptModel }
        }
    }

    public func install(onProgress: @escaping @Sendable (Double) -> Void) async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var directoryValues = URLResourceValues()
        directoryValues.isExcludedFromBackup = true
        var directoryURL = directory
        try directoryURL.setResourceValues(directoryValues)
        var complete: Int64 = 0
        for file in manifest.files {
            try Task.checkCancellation()
            let destination = directory.appendingPathComponent(file.path)
            if !(try valid(destination, spec: file)) {
                let partial = directory.appendingPathComponent(file.path + ".part")
                try FileManager.default.createDirectory(at: partial.deletingLastPathComponent(), withIntermediateDirectories: true)
                let preceding = complete
                let total = manifest.downloadBytes
                let request = ModelDownload(destination: partial, expectedBytes: file.bytes) { bytes in
                    onProgress(Double(preceding + bytes) / Double(total))
                }
                defer { try? FileManager.default.removeItem(at: partial) }
                try await request.run(file.url)
                try Task.checkCancellation()
                guard try valid(partial, spec: file) else { throw ModelError.corruptModel }
                if FileManager.default.fileExists(atPath: destination.path) {
                    _ = try FileManager.default.replaceItemAt(destination, withItemAt: partial)
                } else {
                    try FileManager.default.moveItem(at: partial, to: destination)
                }
            }
            complete += file.bytes
            onProgress(Double(complete) / Double(manifest.downloadBytes))
        }
        try verify()
    }

    private func valid(_ url: URL, spec: ModelFile) throws -> Bool {
        guard FileManager.default.fileExists(atPath: url.path),
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              Int64(size) == spec.bytes else { return false }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hash = SHA256()
        while let chunk = try handle.read(upToCount: 1024 * 1024), !chunk.isEmpty { hash.update(data: chunk) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined() == spec.sha256
    }
}

private final class ModelDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destination: URL
    private let expectedBytes: Int64
    private let onProgress: @Sendable (Int64) -> Void
    private var session: URLSession?
    private var task: URLSessionDownloadTask?
    private var continuation: CheckedContinuation<Void, Error>?
    private let lock = NSLock()
    private var cancelled = false

    init(destination: URL, expectedBytes: Int64, onProgress: @escaping @Sendable (Int64) -> Void) {
        self.destination = destination
        self.expectedBytes = expectedBytes
        self.onProgress = onProgress
    }

    func run(_ url: URL) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                self.continuation = continuation
                if cancelled {
                    self.continuation = nil
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                let configuration = URLSessionConfiguration.ephemeral
                configuration.timeoutIntervalForRequest = 60
                configuration.timeoutIntervalForResource = 1800
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                self.session = session
                let task = session.downloadTask(with: url)
                self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: {
            self.lock.lock()
            self.cancelled = true
            let task = self.task
            self.lock.unlock()
            task?.cancel()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > expectedBytes { downloadTask.cancel() }
        else { onProgress(totalBytesWritten) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        do {
            guard let response = downloadTask.response as? HTTPURLResponse,
                  (200...299).contains(response.statusCode),
                  response.url?.scheme == "https" else { throw ModelError.invalidDownload }
            if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
            try FileManager.default.moveItem(at: location, to: destination)
        } catch { finish(.failure(error)) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error.map { .failure($0) } ?? .success(()))
    }

    private func finish(_ result: Result<Void, Error>) {
        lock.lock()
        let callback = continuation
        continuation = nil
        lock.unlock()
        callback?.resume(with: result)
        session?.finishTasksAndInvalidate()
    }
}
