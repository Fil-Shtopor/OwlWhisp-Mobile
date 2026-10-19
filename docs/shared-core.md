# Reuse and dependency boundary

This repository builds without the desktop checkout. What was carried over:

- OwlWhisp branding and desktop section names/palette.
- Ordinary Parakeet as the user's baseline model.
- Attributed FLEURS EN/RU fixtures and the evaluation approach.
- Mobile architecture/model research, moved out of desktop docs.

The desktop Rust application is not a mobile SDK: it includes desktop automation, audio,
configuration paths and service dependencies. None of those crates is copied into the app.

Android uses official sherpa-onnx 1.13.6, a pinned voxrt Rust C ABI for Redux, a pinned pure-C
Phonon runtime, and a checksum-pinned ARM64 Needle library for Whistle. Those are implementation
dependencies, not a newly extracted OwlWhisp SDK. Kotlin and Swift remain thin native hosts
around model storage and inference.

Manifests, fixture references, binary pins and license notices live in shared/. Android embeds
all four adapters; iOS currently uses the ordinary Parakeet baseline. A future shared SDK
should accept explicit sandbox paths/configuration and leave permissions, audio sessions,
IME/editor lifecycle, background work and platform updates in the native hosts.
