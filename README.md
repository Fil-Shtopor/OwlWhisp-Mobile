# OwlWhisp Mobile

Private, on-device speech-to-text for Android and iPhone. The first prototype uses ordinary
NVIDIA Parakeet TDT 0.6B v3, quantized to INT8, with all 25 languages including Russian.
The model download is approximately 639 MiB and is separate from the application.

## Current implementation

- Android: Kotlin application, microphone recording, pinned model downloads with SHA-256
  verification, offline transcription, editable text and clipboard copy.
- Android voice input method: switch from your regular keyboard, dictate and insert text into
  the active editor. Samsung Keyboard's own microphone provider is not replaced.
- iOS: SwiftUI application sources, microphone capture/resampling, verified model downloads,
  local transcription, copy and share. Build and device validation require macOS/Xcode.
- Audio stays in memory, limited to 60 seconds. Recognition happens after recording stops.
  Streaming partial text is not implemented.
- Both platforms use sherpa-onnx 1.13.6. No cloud speech service is used.

## Build Android

Install JDK 17+, Android SDK platform 36 and build-tools 35.0.0, then run from the root:

    python scripts/build_android.py

The script fetches the checksum-pinned runtime and invokes the Gradle wrapper. Output:

    android/app/build/outputs/apk/debug/app-debug.apk

Open the installed app, download Parakeet and grant microphone permission before enabling the
voice input method in system settings. Keep the app open during download. Background/resumable
downloads are future work.

## Build iOS

On a Mac with Xcode and XcodeGen:

    cd ios
    swift test
    xcodegen generate
    xcodebuild -project OwlWhispMobile.xcodeproj -scheme OwlWhispMobile -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build

For a physical iPhone, open the generated project and choose your signing team. Deployment
target: iOS 16.0. This prototype records in the main app and exposes text through copy/share.
A keyboard extension is not implemented.

## Verification

    python -m unittest discover -s tests -v
    python scripts/prepare.py --model-dir .cache/models/parakeet-tdt-0.6b-v3
    python scripts/android-smoke.py --serial emulator-5580

The last command needs an already running test emulator, adb on PATH and a built debug APK.
It checks actual native recognition of attributed FLEURS speech, file integrity, silence and
recording limits. Personal-phone serials are rejected. See docs/validation.md for results.

## Layout

android/: Kotlin app and voice input method. ios/: Swift package, SwiftUI app and XcodeGen.
shared/: common model manifest and runtime lock. scripts/: builds and smoke checks.
tests/: integrity tests and attributed audio. docs/: architecture and transferred research.

The desktop project remains https://github.com/Fil-Shtopor/OwlWhisp. This repository builds
without a sibling checkout. See docs/shared-core.md for the current reuse boundary.

## License

Apache-2.0 application code. Parakeet weights: CC-BY-4.0.
See shared/THIRD_PARTY_NOTICES.md and tests/fixtures/ATTRIBUTION.md.
