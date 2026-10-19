First Android release of OwlWhisp Mobile.

- Ordinary Parakeet TDT 0.6B v3 INT8 is the default model.
- Model catalog: Parakeet, Redux, Whistle and Phonon-2, with downloads, SHA-256 verification, repair and removal.
- OwlWhisp branding, dark palette and five familiar sections.
- Android voice input method, editable transcripts and clipboard copy.
- Local EN/RU benchmark with WER, RTF, elapsed time, memory sampling and JSON reports.
- Model-process unloading after each job, with optional 30-second or 5-minute Parakeet residency.

Requires Android 9+. ARM64 and x86_64. Models download separately; audio is processed locally.

Validated on a Pixel 6 / API 34 emulator: real Parakeet, Redux and Phonon-2 recognition, benchmarks, model installation, microphone capture, voice IME and memory unloading. APK signing and 16 KiB alignment checks passed.

Limitations: Whistle is ARM64-only, does not support Russian and still requires phone validation. Phonon-2 supports English. Samsung controls its keyboard microphone providers; use the system keyboard picker to select OwlWhisp.

iOS: SwiftUI sources with ordinary Parakeet, matching sections and a benchmark. Swift tests and simulator compilation pass in CI. There is no signed IPA; the smaller-model adapters and keyboard extension have not yet been ported to iOS.

The original 0.1.0 APK uses Russian interface copy. Install 0.1.1 for the English interface.

Download the APK below. SHA256SUMS contains its checksum; source-commit.txt identifies the source revision.
