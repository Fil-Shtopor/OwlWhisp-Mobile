import XCTest
@testable import OwlWhispCore

final class MobileFeaturesTests: XCTestCase {
    func testVersionOrderingAndInvalidTags() throws {
        XCTAssertGreaterThan(try XCTUnwrap(ReleaseVersion("0.10.0")), try XCTUnwrap(ReleaseVersion("v0.9.9")))
        for value in ["", "1.2", "01.2.3", "1.2.3-beta", "1.2.3/evil", "9999999.0.0", "1.2.3\n"] {
            XCTAssertNil(ReleaseVersion(value), value)
        }
    }
    func testRoutesPreserveTheManualChoiceAndLanguageSupport() {
        let models = ["manual": ["en", "ru"], "english": ["en"], "russian": ["ru"]]
        let assignments = ["en": "english", "ru": "russian"]
        XCTAssertEqual(LanguageRouting.resolve(enabled: false, language: "ru", assignments: assignments, manual: "manual", models: models), "manual")
        XCTAssertEqual(LanguageRouting.resolve(enabled: true, language: "ru", assignments: assignments, manual: "manual", models: models), "russian")
        XCTAssertEqual(LanguageRouting.resolve(enabled: true, language: "en", assignments: assignments, manual: "manual", models: models), "english")
        XCTAssertEqual(LanguageRouting.resolve(enabled: true, language: "de", assignments: assignments, manual: "manual", models: models), "manual")
        XCTAssertFalse(LanguageRouting.canAssign(language: "ru", id: "english", models: models))
    }
    private func release(_ change: (inout [String: Any]) -> Void = { _ in }) throws -> Data {
        let base = AppRelease.page.absoluteString
        var value: [String: Any] = ["tag_name": "v0.1.6", "html_url": base + "/tag/v0.1.6",
            "draft": false, "prerelease": false, "body": "Update notes",
            "assets": [["name": "OwlWhisp-Mobile-0.1.6-ios-unsigned.ipa", "size": 1234,
                        "browser_download_url": base + "/download/v0.1.6/OwlWhisp-Mobile-0.1.6-ios-unsigned.ipa"]]]
        change(&value)
        return try JSONSerialization.data(withJSONObject: value)
    }
    func testReleaseDiscoveryAndOlderOrPreviewFiltering() throws {
        let update = try XCTUnwrap(AppRelease.available(data: release(), current: "0.1.5"))
        XCTAssertEqual(update.version.description, "0.1.6")
        XCTAssertNotNil(update.ipa)
        XCTAssertNil(try AppRelease.available(data: release(), current: "0.1.6"))
        XCTAssertNil(try AppRelease.available(data: release(), current: "1.0.0"))
        XCTAssertNil(try AppRelease.available(data: release { $0["prerelease"] = true }, current: "0.1.5"))
        XCTAssertNil(try AppRelease.available(data: release { $0["draft"] = true }, current: "0.1.5"))
        XCTAssertThrowsError(try AppRelease.available(data: release { $0["html_url"] = "https://evil.test/" }, current: "0.1.5"))
        XCTAssertThrowsError(try AppRelease.available(data: release { $0["tag_name"] = "v0.1.6/evil" }, current: "0.1.5"))
        let withoutIPA = try XCTUnwrap(AppRelease.available(data: release { $0["assets"] = [] }, current: "0.1.5"))
        XCTAssertNil(withoutIPA.ipa)
    }
    func testUpdateURLsAreRestricted() {
        XCTAssertTrue(AppRelease.allowed(URL(string: "https://github.com/file")!))
        XCTAssertTrue(AppRelease.allowed(URL(string: "https://release-assets.githubusercontent.com/file")!))
        for value in ["http://github.com/file", "https://github.com.evil.test/file", "https://user@github.com/file", "https://github.com:444/file", "file:///tmp/update"] {
            XCTAssertFalse(AppRelease.allowed(URL(string: value)!), value)
        }
    }
    func testHistoryKeepsIndependentModelResultsAndRejectsOldWeights() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let history = BenchmarkHistory(directory: directory)
        func report(_ id: String, _ language: String) throws -> Data {
            try JSONSerialization.data(withJSONObject: ["model": id, "model_version": "weights-v1", "app_version": "0.1.6",
                "rows": [["file": "clip.wav", "language": language, "reference": "one two", "transcript": "one",
                          "audio_s": 2.0, "elapsed_s": 1.0]]])
        }
        try history.save(report("english", "en"))
        try history.save(report("russian", "ru"))
        XCTAssertEqual(history.load(id: "english", version: "weights-v1")?.summary.wordErrorRate, 0.5)
        XCTAssertEqual(history.load(id: "russian", version: "weights-v1")?.summary.rtf, 0.5)
        XCTAssertNotNil(history.load(id: "english", version: "weights-v1"))
        XCTAssertNil(history.load(id: "english", version: "weights-v2"))
        XCTAssertNil(history.load(id: "../english", version: "weights-v1"))
        let latest = try JSONDecoder().decode(BenchmarkRecord.self, from: XCTUnwrap(history.latestData()))
        XCTAssertEqual(latest.model, "russian")
    }
    func testSharedGuideFiltersLanguagesAndPlatforms() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let guide = try JSONDecoder().decode(ModelGuide.self, from: Data(contentsOf: root.appendingPathComponent("shared/model-guide.json")))
        let directory = root.appendingPathComponent("shared/models")
        let catalog = try JSONDecoder().decode([String: [String]].self, from: Data(contentsOf: directory.appendingPathComponent("catalog.json")))
        let models = try XCTUnwrap(catalog["ios"]).map { try ModelManifest.decode(Data(contentsOf: directory.appendingPathComponent($0 + ".json"))) }
        for language in ["ru", "en", "zh", "ja", "ko", "yue", "de", "ar"] {
            for tier in ["compact", "balanced", "roomy"] {
                let suggestions = guide.suggest(language: language, tier: tier, coverage: false, available: models)
                XCTAssertFalse(suggestions.isEmpty, language)
                XCTAssertTrue(suggestions.allSatisfy { $0.languages.contains(language) })
                XCTAssertFalse(suggestions.contains { ["whistle", "phonon-2", "parakeet-redux"].contains($0.id) })
            }
        }
        XCTAssertEqual(guide.suggest(language: "ru", tier: "balanced", coverage: false, available: models).first?.id, "gigaam-v3-ru")
        let smallest = guide.suggest(language: "en", tier: "compact", coverage: false, available: models, smallest: true)
        XCTAssertEqual(smallest.first?.downloadBytes, models.filter { $0.languages.contains("en") }.map(\.downloadBytes).min())
        XCTAssertEqual(ModelGuide.tier(memoryGiB: 4), "compact")
        XCTAssertEqual(ModelGuide.tier(memoryGiB: 8), "balanced")
        XCTAssertEqual(ModelGuide.tier(memoryGiB: 12), "roomy")
    }
}
