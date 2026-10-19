# iOS

SwiftUI prototype with foreground recording, conversion to 16 kHz mono, verified Parakeet
downloads, CPU inference, editable text, clipboard copy and system sharing.

Requirements: macOS, Xcode, XcodeGen, iOS 16+. The exact sherpa-onnx 1.13.6 Swift package uses
upstream checksum-pinned XCFrameworks.

Run swift test and xcodegen generate here, then build OwlWhispMobile.xcodeproj, scheme
OwlWhispMobile. Choose a signing team for device builds. project.yml is the source of truth;
the generated project and Info.plist are ignored.

The app cancels capture on interruption/backgrounding. Audio is kept in memory for at most
60 seconds. The recognizer releases after each transcription. A keyboard extension is not
implemented. Xcode compilation and iPhone validation must run on macOS; this implementation
session runs on Windows.
