# Android

Kotlin/JNI application for API 28+, compile/target 36. ARM64 and x86_64 APK.

Run python scripts/build_android.py from the repository root with JDK 17+, Rust and the SDK/NDK
installed. Release signing is documented in ../docs/releasing.md.

The five sections mirror desktop: Dictate, Settings, Models, Benchmark, Diagnostics. Models:
ordinary Parakeet, Redux, Whistle (ARM64 only) and English Phonon-2. Weights download separately
with size/SHA-256 checks. The private :inference process is released by default after every job.
The Parakeet keep-ready option expires after 30 or 300 seconds.

Voice input is an auxiliary IME: enable it in system keyboard settings, grant microphone access
in the app and switch to OwlWhisp with Android's keyboard picker. Samsung's own microphone
provider selection is controlled by Samsung.

Capture is limited to 60 seconds. Leaving the capture surface cancels delivery. PCM moves to
inference through a private temporary file that is deleted after processing; no audio is uploaded.

See ../docs/validation.md for emulator checks, known limits and raw benchmark results.
