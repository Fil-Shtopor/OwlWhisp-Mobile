# Releasing Android

Version 0.1.0 uses versionCode 1, min SDK 28 and target SDK 36. The GitHub release contains
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
