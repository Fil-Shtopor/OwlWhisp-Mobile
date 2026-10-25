Keep recording across Settings and see text during dictation.

- Android recordings continue through Settings, keyboard dismissal, app switching, Activity recreation and screen lock. An ongoing recording notification offers Stop and Cancel.
- Text appears in short phrases while recording continues on Android and iOS. The first update needs about eight seconds of audio plus model loading/decoding; this is not word-by-word streaming.
- Keep recognition backlog on disk and finish the remaining audio after Stop. Ordinary Parakeet reuses its loaded model across live chunks.
- Update the original Android editor's composition while it remains attached. Leaving the editor or moving its cursor detaches insertion without cancelling capture. The full transcript remains in Dictate and is never automatically inserted into a different field.
- Enable iOS background audio for app switching and screen lock. Audio interruptions finish captured speech instead of discarding it.
- Remove repeated offline/on-device promotional labels from both apps and keep the circular voice-panel controls.

Downloads:

- [Android APK](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/download/v0.1.3/OwlWhisp-Mobile-0.1.3-android.apk): Android 9+, ARM64/x86_64, signed with the existing identity. Install over previous releases to update.
- [iPhone/iPad IPA](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/download/v0.1.3/OwlWhisp-Mobile-0.1.3-ios-unsigned.ipa): iOS 16+, ARM64. **Personal signing required before installation.** See [installation instructions](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/blob/main/ios/README.md#personal-signing-from-windows-or-macos).
- [iOS simulator ZIP](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases/download/v0.1.3/OwlWhisp-Mobile-0.1.3-ios-simulator.app.zip): ARM64/x86_64, macOS/Xcode only.
- SHA256SUMS, iOS-SHA256SUMS, source-commit.txt and build-info JSON files provide checksums and source/build provenance.

No time limit by default; optional 1/5/15-minute auto-stop remains in Settings. Models download
separately and ordinary Parakeet remains the default. The microphone foreground service and
wake lock run only for an explicitly started dictation; nothing starts at boot.

Android emulator checks cover Settings, Activity recreation, Home, screen lock, notification
Stop and cleanup. Real ordinary Parakeet produced three text updates before a growing
24-second test recording completed. Physical Samsung/iPhone background and lock behavior
still needs device validation. iOS uses main-app dictation with copy/share, without a keyboard
extension. Finite iOS execution time after Stop may defer the final tail until returning to
the app. Force-stopping or terminating either app does not resume a recording.
