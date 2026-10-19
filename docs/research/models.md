# Small-model research

Reviewed 2026-10-19. Ordinary Parakeet TDT 0.6B v3 is the user's first-prototype choice.

| Model | Published download | Findings |
|---|---|---|
| Ordinary Parakeet TDT 0.6B v3 | About 639 MiB, pinned mobile INT8 files | 25 languages including Russian; implemented baseline. |
| Whistle | 16.9 MB | Seven languages without Russian in the reviewed release. ARM64 Android adapter implemented with the pinned Needle C API; needs phone validation. |
| Phonon-2 | 164 MB | English. Native Android libraries published; Android 0.1.0 uses the tested pure-C community runtime and its 178 MiB converted download. |
| Parakeet Redux | 178 MB | 25 languages; Android adapter implemented with voxrt. Publisher's Russian FLEURS WER: 7.91 versus 5.91 for ordinary Parakeet. |

Download size does not establish phone RAM consumption, latency or power use. Android now includes all four choices. Emulator checks cover ordinary Parakeet, Redux and Phonon; Whistle is ARM64-only. iOS currently supports only ordinary Parakeet.

Runtime pins and implementation details are in shared/models/, shared/native-lock.json and scripts/prepare_native.py.

## Sources

- https://cactuscompute.com/blog/whistle
- https://www.youtube.com/watch?v=eskgvQYchd0
- https://huggingface.co/FermionResearch/Phonon-2
- https://huggingface.co/moondream/parakeet-redux
- https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html

FUTO documents Samsung Keyboard's restriction to Samsung/Google voice providers:
https://github.com/futo-org/voice-input. The prototype uses Android's IME picker.

Apple custom keyboards lack direct microphone access and have a separate memory budget:
https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html
and https://developer.apple.com/documentation/uikit/creating-a-custom-keyboard.
