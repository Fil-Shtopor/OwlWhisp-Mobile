# Mobile architecture

OwlWhisp-Mobile is a standalone repository. Native Android and iOS hosts share model metadata,
checksums, benchmark recordings, attribution and visual identity. The historical desktop audit
in research/desktop-audit-2026-10-19.md predates the user's separate-repository decision.

## Android

The Kotlin host owns permissions, capture, editor identity, model storage, settings and UI.
One serial application worker coordinates downloads and jobs. Recording is bounded to 60 s at
16 kHz mono; its owner and generation prevent a late result from reaching a different editor.

A private bound service in :inference owns model sessions. The app passes bounded float PCM
through a private cache file, not through Binder's limited message buffer. Files are removed in
finally blocks; the main process cleans interrupted jobs on startup. Models use noBackupFilesDir;
audio, model weights and settings are excluded from backup.

The service uses sherpa-onnx for ordinary Parakeet; JNI calls voxrt for Redux, phonon-2-c for
Phonon and the pinned Needle C API for Whistle. Whistle is ARM64-only. Its telemetry opt-outs
NEEDLE_TELEMETRY=0 and DO_NOT_TRACK=1 are set before the first runtime call.

Immediate unloading is the default. Closing the separate process also frees global native
pools and Whistle's process-global model, for which no unload API exists. Ordinary Parakeet
can remain loaded for 30 or 300 seconds; there is a manual unload action and memory-pressure
cleanup. Native inference is synchronous within the worker; cancellation discards stale output.

Downloads are bounded by expected size, staged to .part, hashed and promoted only after
verification. Phonon's pinned tar.gz is streamed to flat, expected files; each unpacked file
has its own size/hash pin. Installed files are verified again before constructing a session.

Benchmark runs only fixtures whose language the selected model supports. Each clip uses a cold
session. Elapsed time includes integrity checking, loading and decoding. PSS is sampled every
100 ms in the inference process: the sampled maximum is distinct from post-decode PSS, and
does not guarantee capture of every short allocation spike. Results are local JSON.

The voice IME uses Android's ordinary input-method selection. Its editor-generation guard
prevents committing text into an editor that replaced the original. Samsung's provider button
is outside the app's control.

## iOS

SwiftUI owns the same five tabs; AVAudioEngine/AVAudioConverter produce bounded mono 16 kHz
audio. Actors isolate downloads and recognition. The ordinary Parakeet session is local to
each recognition call, then released. Background/interruption events stop capture and invalidate
delivery. Permission callbacks also check the active application state.

The iOS baseline includes model installation/deletion and the six-clip Parakeet benchmark.
It does not yet embed Redux/Phonon/Needle or implement a keyboard extension. Apple keyboard
extensions cannot directly access the microphone, so Android's IME design cannot be copied
unchanged. Simulator compilation is CI-validated; real-device validation remains outstanding.

## Reuse boundary

Desktop platform automation, tray/hotkeys, path conventions, update services and GUI code stay
in the desktop repository. The mobile app reuses branding, model identity and attributed test
audio. Native runtime dependencies are pinned upstream packages, not sibling-path dependencies.
A shared policy SDK may be extracted later if it reduces duplication between the hosts.
