# 0.1.2 recording policy

The release records without a time limit by default on both platforms, with optional 1, 5 and
15 minute auto-stop settings. Capture streams private mono 16 kHz PCM16 to disk, approximately
1.83 MiB per minute. Inference receives at most 30 seconds per request, with pause-aware
boundaries and overlap stitching. The complete recording is not kept in a RAM array.

Swift package checks include 80-second file coverage, pause boundaries, overlap handling,
empty/truncated audio and the unlimited default. Android debug instrumentation contains the
same coverage checks and a real microphone/Parakeet long-recording test. Reproduction:

    adb -s emulator-5580 shell am instrument -w -e capture_seconds 170 -e recognize true ai.owlwhisp.mobile/ai.owlwhisp.mobile.LongRecordingInstrumentation

Install the debug APK, grant microphone permission and stage the verified ordinary Parakeet
files with the existing smoke-test tooling first. The capture test opens its activity so the
microphone remains in the foreground. It removes its private temporary audio after testing.

Recorded 2026-10-19 on the isolated Pixel 6 API 34 x86_64 emulator (4 GiB RAM).
A real microphone capture ran for 170 seconds with the unlimited policy, produced
5,439,488 bytes of PCM16 and did not call the auto-stop callback. Its silent audio
produced no transcript. A separate 72-second file containing three quiet English FLEURS
utterances separated by long pauses was transcribed with real ordinary Parakeet weights:
all three utterances appeared, WER was 15%, and sampled peak inference PSS was 816,083 KiB.
These are emulator observations, not phone performance estimates. The model's RAM use
is separate from the small recording buffers and disk-file size.

The long-file check initially exposed a whole-chunk energy gate discarding quiet speech
surrounded by silence. The final implementation measures short audio frames and trims
quiet margins with 500 ms padding before inference. The corrected Android test passed;
the equivalent Swift regression test and device/simulator compilation run in CI.
See validation/android-long-recording-0.1.2.json. The 170-second capture path was unchanged
by the energy-gate correction; recognition was rerun against the corrected build.

The sections below preserve earlier development measurements. Their duration limits and
pending-signing statements apply to those earlier builds, before the 0.1.2 recording changes.

# Voice panel redesign

Android 0.1.2 now uses a 92 dp circular microphone and 48 dp keyboard/settings icon buttons.
Debug and unsigned release builds and both lint variants passed. Layout instrumentation on
the isolated Pixel 6 API 34 x86_64 emulator exercised eight combinations: gesture/three-button
navigation, a host drawing behind navigation/a host already reserving it, and ready/recording
states. All controls stayed above the real system navigation bounds. Insets were counted once,
and the centered microphone remained larger than the side buttons. The test uses the production
panel inside debug-only host activities; it does not claim to exercise ASR. Separate real-IME
checks used the cached, verified ordinary Parakeet files: the microphone started actual capture,
the left button cancelled capture and restored Gboard, and the right button cancelled capture
and opened setup. See validation/android-voice-panel-actions-0.1.2.json and the ready/recording
PNG previews in validation/. The host activities
and rendering instrumentation are excluded from release builds.

The first check exposed a stale measurement when padding changed during layout. Insets now
update after that layout pass, allowing a fresh measurement before displaying controls.
See validation/android-voice-panel-0.1.2.json and scripts/android-panel-smoke.py. Physical Samsung
validation of the new panel remains outstanding. The UI update does not change the 60-second
capture limit, which is a prototype bound rather than an OS restriction.

# Keyboard setup and iOS packaging follow-up

Recorded 2026-10-19. Android 0.1.2 application sources at commit 2e4dea7 were compiled as debug
and unsigned release; debug and release lint passed. On the isolated Pixel 6 API 34 emulator, the new
setup flow requested microphone permission, opened Android keyboard management, enabled
OwlWhisp with its actual system switch, accepted the system confirmations, selected it from
the system picker and displayed it in a text editor. No `adb shell ime enable/set` commands
were used. Returning from system settings refreshed the app's enabled/selected status.
See validation/android-keyboard-setup-0.1.2.json; the recorded APK hash is a debug build.
The user confirmed that the existing signed 0.1.1 app appears in Samsung's Choose input method
picker after enabling its system keyboard switch. The new 0.1.2 source has not been tested on
that physical Samsung. The new Return to keyboard button behavior was also verified on the
emulator: tapping it restored Gboard, confirmed by system state. A signed 0.1.2 Android update is pending
access to the existing Windows-protected signing credential; no replacement key was created.

The macOS CI build at the same commit passed Swift tests and produced a Release ARM64
`iPhoneOS` IPA and a separate universal ARM64/x86_64 `iPhoneSimulator` app ZIP using Xcode 16.4.
The build script verifies Mach-O LC_BUILD_VERSION platform values and bundle versioning.
Local verification checked both ZIP archives, Info.plist platform values, model manifests,
absence of a device signing profile/signature and all package SHA-256 checksums.
See validation/ios-build-0.1.2.json. The device IPA requires personal or distribution signing;
packaging is not a physical-iPhone microphone/inference test. The release workflow also built the original 0.1.1 tag and attached matching iOS assets to
release v0.1.1 without changing the Android APK. The published iOS-SHA256SUMS matches GitHub's
asset digests; the device bundle is ARM64/iPhoneOS 16+, and its source commit matches the
Android release (86fd009). See validation/ios-release-0.1.1.json. The release description links
personal-signing instructions and identifies the IPA as requiring signing before installation.

# Validation for 0.1.0

Recorded 2026-10-19 on Windows with an isolated, headless Pixel 6 API 34 x86_64 emulator
(4 GiB RAM, four virtual cores, SwiftShader). These are emulator measurements, not phone
latency or battery measurements.

## Native integration

The debug instrumentation uses real pinned weights and real CPU engines through the private
inference service. No ASR or model loader is mocked.

| Model / fixture | WER | Cold elapsed |
|---|---:|---:|
| Ordinary Parakeet / fleurs_ru_1 | 0% | 14.610 s |
| Redux / fleurs_ru_1 | 0% | 11.847 s |
| Phonon-2 / fleurs_en_1 | 10% | 35.444 s |

Every model also passed checksum verification, rejection of same-size file corruption,
silence suppression and process unloading. The 60-second limit and WER normalization/edit
distance cases were checked. See validation/engines-smoke.txt.

The complete six-clip Parakeet benchmark completed; raw report:
validation/android-emulator-parakeet.json. Its earlier inference_pss_kib field is PSS after
the decode call, not peak RAM. The final app reports post-decode PSS and a separate sampled
maximum (100 ms sampling) to avoid comparing a still-loaded session with a freed one.

## Signed release

A clean install of the signed release APK was tested through its UI:

- All five sections and the owl icon are present.
- Phonon-2 was selected and downloaded over HTTPS from its pinned source. Archive verification,
  extraction and verification of all three unpacked files completed.
- Its three English benchmark clips ran in the release app.
- The inference process exited afterwards; foreground UI PSS was 33,795 KiB, about 33 MiB.
- Runtime microphone permission, main-app recording and foreground-loss cancellation worked.
- The voice IME was enabled and selected, appeared in a text field and started recording.
  Closing it cancelled capture; stopping a silent recording added no text.

The first UI script encountered an unrelated System UI ANR in the software-rendered emulator;
System UI was restarted. Two test-wait issues were corrected: unloaded idle status can replace
the brief ready status, and the CLI UIAutomator dump omits the IME window. The remaining input
steps were completed separately on the same APK with screenshot/observed-button validation.
The repository's UI test uses input-method system state for the IME visibility check.

Measured Phonon release rows:

| Fixture | WER | Elapsed | RTF | Sampled max PSS |
|---|---:|---:|---:|---:|
| fleurs_en_1 | 10.0% | 38.30 s | 6.38 | 327 MiB |
| fleurs_en_2 | 8.7% | 47.60 s | 6.35 | 330 MiB |
| fleurs_en_3 | 9.1% | 50.27 s | 6.35 | 330 MiB |

WER uses lowercased Unicode letters/numbers, punctuation separation and U+0451 is folded to U+0435. It is not the
full Whisper/leaderboard normalizer. Six short samples cannot establish overall accuracy.

See validation/android-release-ui.json and validation/android-release-idle-memory.txt.
Release signing verification passed (APK signature v2); ZIP and all six ELF libraries have
16-KiB-compatible alignment. Signed APK contains no debug instrumentation.

## Build checks

- Android debug and release compilation plus lint passed locally.
- Four Python download-integrity tests passed.
- GitHub Actions builds Android from pinned dependencies, runs the Python tests and lint.
- macOS CI runs Swift package tests (manifest validation and benchmark metrics) and successfully
  compiles the SwiftUI application for an iOS simulator.

## Limits

Whistle's ARM64 JNI adapter and linked runtime compile. This x86_64 emulator cannot run that
binary; ARM64 phone recognition remains unvalidated. Samsung's exact keyboard-provider behavior
has not been tested on a Samsung device. Android uses the standard IME picker.

iOS has only the ordinary Parakeet adapter. Its microphone behavior and benchmark have not
been exercised on a physical iPhone; simulator compilation is not device validation. There
is no signed iOS distribution, streaming transcript, resumable download or always-on capture.
