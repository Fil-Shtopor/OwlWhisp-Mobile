# Mobile architecture

Android and iOS live in OwlWhisp-Mobile, independently of desktop releases. This supersedes
the earlier single-repository proposal.

## First prototype: ordinary Parakeet

The baseline is ordinary NVIDIA Parakeet TDT 0.6B v3 in the sherpa-onnx INT8 export, preserving
25 languages. It is not Redux or Phonon. Both apps consume the same manifest under shared/models,
with revision-pinned URLs, sizes and SHA-256.

Android uses native Kotlin, AudioRecord and InputMethodService with the official sherpa AAR.
iOS uses SwiftUI, AVAudioEngine and the official sherpa Swift package, with copy/share delivery.
Both use the same native engine version and model.

This build proof reuses the engine choice, base model, branding and test audio from OwlWhisp.
It does not copy desktop Rust app code. The proposed shared Rust SDK is a future extraction;
see shared-core.md.

## Session and storage boundaries

Native hosts own permissions, microphone, text delivery, UI and suspension. Inference runs off
the UI thread. Audio stays in memory, is limited to 60 seconds and is cancelled when its editor
or foreground session is lost. Cancellation invalidates pending results; native decoding may
finish before resources release. The model session is scoped to each transcription.

Download files are verified before replacing installed copies, and the complete model is verified
before native initialization. Model storage is private and excluded from backups. Downloads need
the foreground; resuming partial/background downloads is not implemented.

Android inserts into the editor session that started recording and can return to the previous
IME. Samsung Keyboard's own microphone provider is not replaced.

iOS records in its main application. Keyboard extensions have microphone, memory and host-app
communication constraints requiring a separate feasibility proof.

## Subsequent work

- Compile/validate on an iPhone and test interruptions.
- Verify IME insertion/return on the target Samsung/One UI version.
- Add model selection and measure alternative lightweight runtimes.
- Extract reusable Rust SDK policy only where this benefits the native hosts.
- Add streaming and accelerators after establishing phone latency/memory baselines.

## References

- https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
- https://k2-fsa.github.io/sherpa/onnx/android/index.html
- https://k2-fsa.github.io/sherpa/onnx/ios/index.html
- https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html
- https://developer.apple.com/app-store/review/guidelines/#extensions
