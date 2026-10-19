Unreleased: Android voice panel redesign, keyboard setup and reproducible iOS packages.

- Replace the stacked voice-IME buttons with a large circular microphone, a smaller keyboard button on the left and settings on the right.
- Add a recording timer, a stop icon and a processing indicator, with accessible button descriptions.
- Keep controls above gesture and three-button system navigation; avoid counting insets twice when Android already reserves the navigation area.
- Exercise both navigation modes, both host inset behaviors and ready/recording states in debug rendering instrumentation.
- Replace the auxiliary ru_RU input subtype with a regular, language-neutral selectable keyboard.
- Add Voice keyboard setup with model readiness, microphone permission, keyboard enablement/selection status, system settings and picker buttons, and a test field.
- Open the same setup screen from Android's IME settings shortcut and the voice input panel.
- Disable voice capture when microphone permission is missing and provide setup guidance.
- Verify real system-settings enablement and picker selection on an Android API 34 emulator without ADB IME commands.
- Build and upload unsigned ARM64 iPhone IPAs and universal simulator app ZIPs in CI, with package checksums and platform metadata.
- Add personal-signing instructions for iPhone and a workflow to backfill existing releases.

The Android update must use the existing release signing identity before publication. No signed
0.1.2 APK has been published yet. iOS device packages require signing before installation;
there is no TestFlight distribution or iOS keyboard extension. Physical Samsung/iPhone runtime
validation remains outstanding.
