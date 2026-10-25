# OwlWhisp Mobile

Offline voice input for Android and iPhone, in a separate repository from
[OwlWhisp Desktop](https://github.com/Fil-Shtopor/OwlWhisp).

## Downloads

**OwlWhisp Mobile 0.1.3** - [release notes](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/tag/v0.1.3).

| Platform | Application download | Installation |
|---|---|---|
| **Android**, ARM64 / x86_64, Android 9+ | **[Download Android APK](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/download/v0.1.3/OwlWhisp-Mobile-0.1.3-android.apk)** | Signed APK; updates existing OwlWhisp installations |
| **iPhone / iPad**, ARM64, iOS 16+ | **[Download iOS IPA](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/download/v0.1.3/OwlWhisp-Mobile-0.1.3-ios-unsigned.ipa)** | **Personal signing required** before installation; [instructions](ios/README.md#personal-signing-from-windows-or-macos) |
| iOS Simulator, macOS with Xcode | [Download simulator app ZIP](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/download/v0.1.3/OwlWhisp-Mobile-0.1.3-ios-simulator.app.zip) | ARM64 / x86_64; simulator only |

Models download separately. The iPhone IPA is unsigned and is not a TestFlight/App Store
release. A free Apple account can be used for personal signing. Checksums and build provenance
are available in the release assets.

The default model is **ordinary NVIDIA Parakeet TDT 0.6B v3 INT8**, with 25 languages,
including Russian. Models download separately from the APK.

## Android

Version 0.1.3 includes guided keyboard setup and a regular selectable voice IME.
[Voice panel preview](docs/validation/android-voice-panel-ready.png).

- The same five sections as desktop: Dictate, Settings, Models, Benchmark, Diagnostics.
- OwlWhisp's owl icon and dark palette.
- Microphone capture, editable transcript, clipboard copy and a voice input method (IME).
- A circular microphone with smaller keyboard/settings buttons, recording timer and navigation-safe spacing.
- Verified downloads, model selection, repair and deletion.
- Local FLEURS EN/RU benchmark, WER, cold latency, RTF, sampled maximum PSS and JSON sharing.
- Disposable inference process: immediate unloading by default. Parakeet can optionally stay
  ready for 30 seconds or 5 minutes. Memory pressure and leaving the app release idle sessions.
- No account or startup service. An explicit dictation runs until Stop, Cancel or an interruption.
- Live phrase updates and recording across Settings, app switching and screen lock.
- No recording time limit by default; optional 1, 5 or 15 minute auto-stop in Settings.

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

SwiftUI app with the same five sections, branding, ordinary Parakeet, background-capable microphone
capture/resampling, verified downloads, local benchmark, copy and share. Swift tests and an
iOS device archive and simulator build pass in GitHub Actions. It has not been tested on a physical iPhone.
Redux, Whistle and Phonon adapters and a keyboard extension are not yet implemented on iOS.
Releases include an **unsigned ARM64 iPhone IPA** and a **simulator app ZIP**. The IPA requires
personal Apple signing before installation, for example with Sideloadly on Windows or macOS;
the simulator ZIP requires macOS/Xcode. There is no
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
    python scripts/android-keyboard-smoke.py --serial emulator-5580 --fresh
    python scripts/android-release-smoke.py --serial emulator-5580 --fresh

The last script removes only this app on the explicitly selected disposable emulator, installs
the signed APK, downloads Phonon and exercises benchmark, microphone cancellation and IME.
Supply --adb with the SDK adb path when it is not on PATH.

[Validation results](docs/validation.md) distinguish emulator measurements from phone performance.
Keep the app open while downloading or benchmarking. Downloads are not resumable.

Recording has **no time limit by default** on Android and iOS. Settings can optionally stop
after 1, 5 or 15 minutes. Explicitly started recording continues through Settings, app switching and screen lock.
Text appears in short phrases while recording continues. The first update needs about eight
seconds of audio plus model loading/decoding; this is not a word-by-word streaming model. Private PCM16 audio is temporarily stored on disk at about
**1.83 MiB per minute**; the complete recording is not retained in RAM. Audio is deleted after
processing/cancellation, and abandoned recordings are removed at the next app start.
On Android, a recording notification offers Stop/Cancel. Leaving a text editor detaches live
insertion without stopping capture; the transcript remains in Dictate. iOS uses its audio
background mode; finite background time after Stop may defer the final tail until returning
to the app. Physical-phone background behavior still needs validation.

Live recognition reads at most eight seconds of audio at a time and prefers pauses near chunk
boundaries. Continuous speech uses a short overlap with duplicate-word stitching. These are
processing chunks, not a recording-duration limit. Boundary decisions can affect punctuation
or words in continuous speech. Model/runtime memory is separate from the audio buffer and
still depends on the model and device. Text updates after each phrase decode. Recording starts only from an explicit user action.

## Layout and license

android/: native Kotlin/JNI app and voice IME. ios/: SwiftUI app and Swift package.
shared/: manifests, runtime pins, benchmark fixtures and licenses. scripts/: build/test tools.
docs/: architecture and mobile research moved from desktop.

No sibling checkout or desktop Rust app code is required. Application code: Apache-2.0.
Model weights and bundled dependencies have their own licenses; see
[third-party notices](shared/THIRD_PARTY_NOTICES.md) and shared/licenses/.

All authored repository and interface text is English; see [contribution guidance](CONTRIBUTING.md). Non-English ASR reference data uses JSON Unicode escapes.
