Voice keyboard redesign and longer offline dictation on Android and iOS.

- Replace the fixed 60-second recording cutoff with **No time limit by default**. Settings offers optional 1, 5 and 15 minute auto-stop limits.
- Stream microphone audio to private PCM16 files instead of retaining a complete recording in RAM. Disk usage is approximately 1.83 MiB per minute; temporary audio is removed after completion/cancellation or recovered at startup.
- Transcribe bounded chunks of at most 30 seconds after stopping, preferring pause boundaries. Continuous speech uses a 500 ms overlap with duplicate-word stitching and progress reporting. Framewise energy detection preserves quiet speech surrounded by long pauses.
- Redesign Android voice input with a large circular microphone, smaller keyboard/settings icons, a recording timer and a stop icon.
- Keep controls above gesture and three-button system navigation. Add guided setup for models, microphone permission and keyboard selection.
- Add a prominent README download table with direct Android APK, iPhone IPA and simulator links.

Downloads:

- `OwlWhisp-Mobile-0.1.2-android.apk`: signed Android 9+ APK for ARM64 and x86_64, using the existing signing identity. Install over 0.1.0/0.1.1 to update.
- `OwlWhisp-Mobile-0.1.2-ios-unsigned.ipa`: ARM64 iPhone/iPad build for iOS 16+. **Requires personal signing before installation**; it is not a TestFlight/App Store build. See [iPhone installation](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/blob/main/ios/README.md#personal-signing-from-windows-or-macos).
- `OwlWhisp-Mobile-0.1.2-ios-simulator.app.zip`: macOS/Xcode simulator app for ARM64/x86_64; does not install on an iPhone.
- `SHA256SUMS`, `iOS-SHA256SUMS`, `source-commit.txt` and `ios-build-info.json`: checksums and build provenance.

Models download separately. Ordinary Parakeet is the default. Whistle remains ARM64-only and
needs physical-phone recognition validation. iOS currently supports ordinary Parakeet in the
main app with copy/share; there is no iOS keyboard extension. Longer recordings still require
foreground capture and enough local storage; processing begins after you stop. Model memory
is separate from audio-buffer memory, and chunk boundaries can affect punctuation or words.
Physical iPhone runtime validation remains outstanding.
