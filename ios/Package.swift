// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OwlWhispCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "OwlWhispCore", targets: ["OwlWhispCore"])],
    dependencies: [.package(url: "https://github.com/k2-fsa/sherpa-onnx", exact: "1.13.6")],
    targets: [
        .target(name: "OwlWhispCore", dependencies: [.product(name: "sherpa-onnx", package: "sherpa-onnx")]),
        .testTarget(name: "OwlWhispCoreTests", dependencies: ["OwlWhispCore"])
    ]
)
