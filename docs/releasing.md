# Releasing mobile apps

Version 0.1.2 uses versionCode 3, min SDK 28 and target SDK 36. The GitHub release contains
a universal ARM64/x86_64 APK; models are downloaded separately.

1. Run scripts/build_android.py and appropriate emulator checks.
2. On the release Windows account run scripts/build_release.ps1.
3. Verify APK signing with apksigner and ZIP alignment with zipalign -c -P 16 4.
4. Publish the tested APK, SHA256SUMS and source-commit.txt to the matching GitHub tag.

The script creates .signing/owlwhisp-mobile.jks only if absent. Its password is stored in
.signing/password.xml, encrypted with Windows DPAPI for the current Windows account.
Both are ignored by Git. Keep this signing identity for future updates; an unrelated new key
cannot update an existing installation. Back up signing material through a secure channel;
the DPAPI file is tied to its Windows identity.

Release certificate SHA-256:

    696e893793258815eba47f3240fa4a8b0ae98446eb947e98370ddd57f70d143c

For another trusted build environment, supply OWLWHISP_KEYSTORE, OWLWHISP_STORE_PASSWORD,
OWLWHISP_KEY_PASSWORD and OWLWHISP_KEY_ALIAS. Never commit a key or credentials. A release
built without signing variables is unsigned and should not be distributed.

Commits in this repository use Fil-Shtopor and the GitHub noreply email
48886239+Fil-Shtopor@users.noreply.github.com. iOS simulator builds require no signing.
Physical iOS distribution requires the owner's Apple developer signing setup.

## iOS artifacts

The `ios` job builds both Release device and simulator apps using `scripts/build_ios.py`.
Download the `ios-release` Actions artifact for the exact release commit. Attach the unsigned
IPA, simulator app ZIP and `ios-build-info.json` to the GitHub release; include their hashes in
SHA256SUMS alongside the Android APK. The script verifies ARM64 and the Mach-O device/simulator
platform distinction. Keep `source-commit.txt` synchronized with the tested source revision.

The unsigned IPA is a device build for subsequent signing. Label it **unsigned; signing required**
in every download description. A simulator build does not run on a phone. Neither artifact is
an install-ready signed iPhone release. Apple distribution signing is a separate owner setup;
see [iOS signing and installation](../ios/README.md). Never publish credentials or private keys.

To backfill iOS packages on an existing version, dispatch **iOS release packages** with its
existing tag. The workflow checks out that tag, verifies the marketing version, runs Swift
tests, builds both packages and attaches them to the existing release without overwriting
assets. Build tooling comes from the workflow revision; application sources come from the
release tag. The separate `iOS-SHA256SUMS` covers the iOS assets. Release notes must explain
that the IPA requires signing before installation.
