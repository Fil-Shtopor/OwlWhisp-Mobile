# Android

Native Kotlin prototype for Android 9/API 28+, ARM64 and x86_64; compile/target API 36.
Uses standard Android views and the official sherpa-onnx AAR.

Run python scripts/build_android.py from the repository root. Gradle 8.14.2, its distribution
checksum and the native runtime digest are pinned.

Open the app, download ordinary Parakeet TDT 0.6B v3 and grant microphone permission.
Enable OwlWhisp in keyboard settings and select it with the system input-method picker.
Dictate, then press Stop to insert text. The panel can return to the previous keyboard.
Editor changes and hiding the panel cancel the session.

Recording is bounded to 60 seconds and held in memory. Losing foreground capture cancels it.
Inference uses CPU, two threads; the model session releases after each job. Cancelling inference
discards the result but does not interrupt the active native decode call.

Debug builds include a test-only instrumentation component and FLEURS fixture. Release builds
exclude both. Release signing/publication is not configured.
