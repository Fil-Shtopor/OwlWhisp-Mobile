import XCTest
@testable import OwlWhispCore

final class ModelManifestTests: XCTestCase {
    private func manifest(paths: [String], url: String = "https://example.com/model") throws -> Data {
        let entries = paths.map { ["path": $0, "url": url, "bytes": 1, "sha256": String(repeating: "a", count: 64)] as [String: Any] }
        return try JSONSerialization.data(withJSONObject: [
            "id": "parakeet-tdt-0.6b-v3", "name": "Parakeet", "license": "CC-BY-4.0",
            "attribution": "NVIDIA", "languages": ["ru"], "common": [entries[0]],
            "recognition": ["family": "transducer", "featureDim": 128, "tokens": "tokens.txt",
                "encoder": "encoder.int8.onnx", "decoder": "decoder.int8.onnx", "joiner": "joiner.int8.onnx"],
            "artifacts": [["target": "cpu_int8", "files": Array(entries.dropFirst())]]
        ])
    }

    func testAllBundledCatalogModels() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let directory = root.appendingPathComponent("shared/models")
        let catalog = try JSONDecoder().decode([String: [String]].self, from: Data(contentsOf: directory.appendingPathComponent("catalog.json")))
        for id in try XCTUnwrap(catalog["ios"]) {
            let manifest = try ModelManifest.decode(Data(contentsOf: directory.appendingPathComponent(id + ".json")))
            XCTAssertEqual(manifest.id, id)
            XCTAssertGreaterThan(manifest.downloadBytes, 0)
        }
    }

    func testRejectsUnpinnedAndUnsafeDownloads() throws {
        let names = ["tokens.txt", "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"]
        XCTAssertEqual(try ModelManifest.decode(manifest(paths: names)).files.count, 4)
        XCTAssertThrowsError(try ModelManifest.decode(manifest(paths: names, url: "http://example.com/model")))
        XCTAssertThrowsError(try ModelManifest.decode(manifest(paths: ["../tokens.txt"] + names.dropFirst())))
        XCTAssertThrowsError(try ModelManifest.decode(manifest(paths: names + ["tokens.txt"])))
    }
}
