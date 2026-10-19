# Validation

Android baseline, 2026-10-19: Gradle assembleDebug and lintDebug passed on Windows. A headless Pixel 6 API 34 x86_64 emulator transcribed the bundled Russian FLEURS sample with ordinary Parakeet TDT 0.6B v3 INT8 exactly (WER 0.0 on one sample; 13.479 s including cold loading). The instrumentation also rejected same-size file tampering and checked silence and the recording length limit. These are emulator checks, not phone performance measurements.

iOS sources are provided for Xcode and are not yet validated on a device. Additional model adapters, benchmark UI and release validation are in progress.
