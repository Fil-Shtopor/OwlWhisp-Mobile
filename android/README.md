# Android

Kotlin/JNI application for API 28+, compile/target 36. ARM64 and x86_64 APK.

Run python scripts/build_android.py from the repository root with JDK 17+, Rust and the SDK/NDK
installed. Release signing is documented in ../docs/releasing.md.

The five sections mirror desktop: Dictate, Settings, Models, Benchmark, Diagnostics. Models:
ordinary Parakeet, Redux, Whistle (ARM64 only) and English Phonon-2. Weights download separately
with size/SHA-256 checks. The private :inference process is released by default after every job.
The Parakeet keep-ready option expires after 30 or 300 seconds.

Installing an APK does not automatically enable its keyboard. Voice input on main is a regular selectable IME. Open **Set up voice keyboard** in Dictate or Settings.
The setup screen checks model availability, microphone permission, keyboard enablement and
selection. Enable OwlWhisp in system keyboard settings, then choose it with the system picker.
On Samsung: Settings > General management > Keyboard list and default. Enable the keyboard
button on the navigation bar so you can switch while a text field is focused. Samsung's own
Voice input provider page may offer only Samsung and Google; use the system keyboard list.
The IME settings shortcut also opens this setup screen. No ADB commands are needed.

Capture is limited to 60 seconds. Leaving the capture surface cancels delivery. PCM moves to
inference through a private temporary file that is deleted after processing; no audio is uploaded.

See ../docs/validation.md for emulator checks, known limits and raw benchmark results.
