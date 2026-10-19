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
