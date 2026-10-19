# OwlWhisp Mobile

Offline voice input for Android and iPhone, in a separate repository from
[OwlWhisp Desktop](https://github.com/Fil-Shtopor/OwlWhisp).

[Download Android and iOS 0.1.2](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/tag/v0.1.2)

The default model is **ordinary NVIDIA Parakeet TDT 0.6B v3 INT8**, with 25 languages,
including Russian. Models download separately from the APK.

## Android 0.1.2

- The same five sections as desktop: Dictate, Settings, Models, Benchmark, Diagnostics.
- OwlWhisp's owl icon and dark palette.
- Microphone capture, editable transcript, clipboard copy and a voice input method (IME).
- Verified downloads, model selection, repair and deletion.
- Local FLEURS EN/RU benchmark, WER, cold latency, RTF, sampled maximum PSS and JSON sharing.
- Disposable inference process: immediate unloading by default. Parakeet can optionally stay
  ready for 30 seconds or 5 minutes. Memory pressure and leaving the app release idle sessions.
- No account, cloud transcription, startup service or persistent background microphone.

| Model | Languages | Download | Runtime |
|---|---|---:|---|
| Parakeet TDT 0.6B v3 INT8 | 25, including Russian | 639 MiB | sherpa-onnx |
| Parakeet Redux | 25, including Russian | 171 MiB | voxrt |
| Whistle | EN, DE, FR, ES, IT, NL, PL | 16 MiB | Needle; ARM64 only |
| Phonon-2 | English | 178 MiB | phonon-2-c; 244 MiB installed |

Parakeet, Redux and Phonon passed real recognition checks on an Android API 34 x86_64
emulator. Whistle's ARM64 adapter compiles, but needs validation on a physical ARM64 phone.
A smaller download does not imply the same amount of RAM during recognition.

Open **Set up voice keyboard** in Dictate or Settings, allow the microphone, enable OwlWhisp
in Android's keyboard settings and select it through the system keyboard picker. Samsung Keyboard's microphone button depends on Samsung's supported providers; the
app provides its own voice IME. Android 9/API 28+; ARM64 phones and x86_64 emulators.

## iOS

SwiftUI app with the same five sections, branding, ordinary Parakeet, foreground microphone
capture/resampling, verified downloads, local benchmark, copy and share. Swift tests and an
iOS device archive and simulator build pass in GitHub Actions. It has not been tested on a physical iPhone.
Redux, Whistle and Phonon adapters and a keyboard extension are not yet implemented on iOS.
Releases include an **unsigned ARM64 iPhone IPA** and a **simulator app ZIP**. The IPA requires
Apple signing before installation; the simulator ZIP requires macOS/Xcode. There is no
TestFlight/App Store distribution yet. See [iOS installation and signing](ios/README.md).

## Build

Android requires JDK 17+, Rust 1.89+, Android SDK platform 36, build-tools 35.0.0,
NDK 27.1.12297006 and CMake 3.22.1. Set ANDROID_HOME, then:

    python scripts/build_android.py

Dependencies and model manifests use immutable revisions and checksums. The script downloads
the pinned AAR/Needle library, checks out the two native engines and builds Redux for both
Android ABIs. Output: android/app/build/outputs/apk/debug/app-debug.apk.

For the signed Windows release, prepare dependencies first and run:

    powershell -ExecutionPolicy Bypass -File scripts/build_release.ps1

See [release signing](docs/releasing.md). iOS requires macOS, Xcode and XcodeGen:

    cd ios
    swift test
    xcodegen generate
    cd ..
    python3 scripts/build_ios.py

Choose your Apple signing team to install on a physical iPhone.

## Test and limits

    python -m unittest discover -s tests -v
    python scripts/prepare.py --model-dir .cache/models/parakeet-tdt-0.6b-v3
    python scripts/android-smoke.py --serial emulator-5580
    python scripts/android-release-smoke.py --serial emulator-5580 --fresh

The last script removes only this app on the explicitly selected disposable emulator, installs
the signed APK, downloads Phonon and exercises benchmark, microphone cancellation and IME.
Supply --adb with the SDK adb path when it is not on PATH.

[Validation results](docs/validation.md) distinguish emulator measurements from phone performance.
Keep the app open while downloading or benchmarking. Downloads are not resumable. Recording
is limited to 60 seconds; recognition starts when recording stops. Android passes temporary
PCM through private cache files and deletes it after processing or at the next main-app start.
iOS holds recording audio in memory. There is no streaming partial transcript.

## Layout and license

android/: native Kotlin/JNI app and voice IME. ios/: SwiftUI app and Swift package.
shared/: manifests, runtime pins, benchmark fixtures and licenses. scripts/: build/test tools.
docs/: architecture and mobile research moved from desktop.

No sibling checkout or desktop Rust app code is required. Application code: Apache-2.0.
Model weights and bundled dependencies have their own licenses; see
[third-party notices](shared/THIRD_PARTY_NOTICES.md) and shared/licenses/.

All authored repository and interface text is English; see [contribution guidance](CONTRIBUTING.md). Non-English ASR reference data uses JSON Unicode escapes.
