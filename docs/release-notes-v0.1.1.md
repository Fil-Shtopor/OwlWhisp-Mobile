English interface and repository text.

- Translate Android and iOS navigation, controls, statuses, errors, microphone permission prompts and voice-keyboard copy into English.
- Translate release notes and update UI automation to use English labels.
- Keep non-English ASR reference utterances as JSON Unicode escapes, preserving the original recognition tests.
- Add repository language guidance and a CI check against Cyrillic text in tracked UTF-8 files.

Model support and offline recognition behavior are unchanged from 0.1.0. This update uses the same Android signing identity and can update an existing installation.

Android 9+, ARM64 and x86_64. Models download separately. Whistle is ARM64-only and still needs phone validation; Phonon-2 supports English. iOS sources build in CI, with ordinary Parakeet only and no signed IPA.

Download the signed APK below. SHA256SUMS contains its checksum; source-commit.txt identifies the source revision.
