import Foundation

public struct ReleaseVersion: Comparable, CustomStringConvertible, Sendable {
    public let major: Int
    public let minor: Int
    public let patch: Int
    public init?(_ text: String) {
        guard text.range(of: #"^v?(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$"#, options: .regularExpression) != nil else { return nil }
        let values = text.replacingOccurrences(of: #"^v"#, with: "", options: .regularExpression).split(separator: ".").compactMap { Int($0) }
        guard values.count == 3 else { return nil }
        major = values[0]; minor = values[1]; patch = values[2]
    }
    public var description: String { "\(major).\(minor).\(patch)" }
    public static func < (lhs: Self, rhs: Self) -> Bool {
        (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
    }
}

public struct AvailableAppUpdate: Sendable {
    public let version: ReleaseVersion
    public let page: URL
    public let notes: String
    public let ipa: URL?
}

public enum ReleaseError: LocalizedError {
    case invalidMetadata
    public var errorDescription: String? { "The update server returned invalid release information." }
}

public struct AppRelease: Decodable {
    struct Asset: Decodable {
        let name: String
        let browser_download_url: String
        let size: Int64
    }
    let tag_name: String
    let html_url: String
    let draft: Bool
    let prerelease: Bool
    let body: String?
    let assets: [Asset]
    public static let page = URL(string: "https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases")!
    public static let api = URL(string: "https://api.github.com/repos/Fil-Shtopor/OwlWhisp-Mobile/releases/latest")!

    public static func allowed(_ url: URL) -> Bool {
        url.scheme == "https" && ["api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"].contains(url.host ?? "") &&
            url.user == nil && url.password == nil && (url.port == nil || url.port == 443)
    }
    public static func available(data: Data, current: String) throws -> AvailableAppUpdate? {
        guard data.count <= 2 * 1024 * 1024, let installed = ReleaseVersion(current) else { throw ReleaseError.invalidMetadata }
        let release = try JSONDecoder().decode(Self.self, from: data)
        if release.draft || release.prerelease { return nil }
        guard let version = ReleaseVersion(release.tag_name) else { throw ReleaseError.invalidMetadata }
        if version <= installed { return nil }
        guard release.tag_name == "v\(version)",
              release.html_url == page.absoluteString + "/tag/v\(version)",
              let url = URL(string: release.html_url) else { throw ReleaseError.invalidMetadata }
        let name = "OwlWhisp-Mobile-\(version)-ios-unsigned.ipa"
        let matches = release.assets.filter { $0.name == name }
        guard matches.count <= 1 else { throw ReleaseError.invalidMetadata }
        var ipa: URL?
        if let asset = matches.first {
            let expected = page.absoluteString + "/download/v\(version)/" + name
            guard asset.browser_download_url == expected, asset.size > 0, asset.size <= 256 * 1024 * 1024 else { throw ReleaseError.invalidMetadata }
            ipa = URL(string: expected)
        }
        return AvailableAppUpdate(version: version, page: url, notes: String((release.body ?? "").prefix(40_000)), ipa: ipa)
    }
}
