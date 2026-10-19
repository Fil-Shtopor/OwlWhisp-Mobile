English interface and repository text.

- Translate Android and iOS navigation, controls, statuses, errors, microphone permission prompts and voice-keyboard copy into English.
- Translate release notes and update UI automation to use English labels.
- Keep non-English ASR reference utterances as JSON Unicode escapes, preserving the original recognition tests.
- Add repository language guidance and a CI check against Cyrillic text in tracked UTF-8 files.

Model support and offline recognition behavior are unchanged from 0.1.0. This update uses the same Android signing identity and can update an existing installation.

Android 9+, ARM64 and x86_64. Models download separately. Whistle is ARM64-only and still needs phone validation; Phonon-2 supports English. iOS supports ordinary Parakeet only. The release now also includes device and simulator packages built from this tag.

Downloads:

- `OwlWhisp-Mobile-0.1.1-android.apk`: signed Android APK. `SHA256SUMS` contains its checksum; `source-commit.txt` identifies the source revision.
- `OwlWhisp-Mobile-0.1.1-ios-unsigned.ipa`: ARM64 iPhone/iPad Release build, iOS 16+. **Personal signing is required before installation.** It is not a TestFlight or App Store build. A free Apple account can be used with [Sideloadly](https://sideloadly.io/) on Windows or macOS; free signatures require renewal after seven days.
- `OwlWhisp-Mobile-0.1.1-ios-simulator.app.zip`: macOS/Xcode simulator app, ARM64 and x86_64. It does not install on an iPhone.
- `iOS-SHA256SUMS` and `ios-build-info.json`: iOS checksums, source commit, Xcode version, architecture and platform checks.

See [iPhone installation and signing](https://github.com/Fil-Shtopor/OwlWhisp-Mobile/blob/main/ios/README.md#personal-signing-from-windows-or-macos). The iOS app currently dictates in the main app and copies/shares text; it has no keyboard extension. Physical-iPhone recognition is not yet validated.

If OwlWhisp is missing from Android's input picker, open OwlWhisp > Settings > Enable OwlWhisp in Android settings and turn on its switch, then return to the picker.
