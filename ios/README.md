# iOS

SwiftUI app and OwlWhispCore Swift package. Minimum iOS 16.0.

The app has OwlWhisp branding and the same five sections as desktop, ordinary Parakeet INT8,
verified downloads/deletion, microphone capture, copy/share and a six-clip FLEURS benchmark.
Model sessions are scoped to one job. Background/interruption invalidates capture and delivery.

On macOS install XcodeGen, then run swift test, xcodegen generate and xcodebuild as described
in ../README.md. GitHub Actions runs package tests and an unsigned simulator build.

No physical-iPhone validation or signed IPA is provided. Redux, Whistle and Phonon native
adapters and a keyboard extension remain Android-only/future work for iOS. Apple custom keyboard
extensions cannot directly access the microphone; dictation currently runs in the main app.

Weights are CC-BY-4.0 and download separately. Audio is bounded to 60 seconds and retained in
memory during a job. The app sends neither audio nor transcripts to a transcription service.
