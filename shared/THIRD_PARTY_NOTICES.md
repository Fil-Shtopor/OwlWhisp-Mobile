# Third-party notices

NVIDIA Parakeet TDT 0.6B v3, NVIDIA and respective contributors; weights: CC-BY-4.0.
https://creativecommons.org/licenses/by/4.0/
https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3

Published ONNX export by Fangjun Kuang for k2-fsa/sherpa-onnx:
https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
The prototype uses this INT8 export and does not retrain its weights.

sherpa-onnx 1.13.6, k2-fsa and contributors, Apache-2.0:
https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.6/LICENSE

ONNX Runtime, Microsoft and contributors, MIT:
https://github.com/microsoft/onnxruntime/blob/main/LICENSE

OwlWhisp branding is reused from Fil-Shtopor/OwlWhisp (Apache-2.0).
The FLEURS fixture is CC-BY-4.0; see tests/fixtures/ATTRIBUTION.md.
Only debug builds include that test audio.

## Additional model runtimes

- Parakeet Redux: Moondream, derived from NVIDIA Parakeet TDT 0.6B v3, CC-BY-4.0. https://huggingface.co/moondream/parakeet-redux
- voxrt: Maksym L., Apache-2.0, revision 4fab626c00708d9bc97f837d309e0d362094282b. https://github.com/lmaksym/voxrt
- Phonon-2: Fermion Research, derived from NVIDIA Parakeet, CC-BY-4.0. https://huggingface.co/FermionResearch/Phonon-2
- phonon-2-c runtime and C weight conversion: Eric Schmidbauer, MIT (runtime) and Apache-2.0 (upstream code); exact notices are included in licenses/. https://github.com/eschmidbauer/phonon-2-c
- Whistle and Needle runtime: Cactus Compute, Apache-2.0. https://huggingface.co/Cactus-Compute/whistle and https://huggingface.co/Cactus-Compute/needle3

All model weights download separately. The Android benchmark bundles six short FLEURS EN/RU samples from the desktop repository; CC-BY-4.0, Conneau et al., 2022. Individual source IDs and transcripts are in benchmark/fixtures.json.
