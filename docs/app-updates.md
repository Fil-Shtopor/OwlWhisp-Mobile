# App updates

OwlWhisp Mobile checks the latest stable GitHub release when the main app becomes active.
Automatic checks are enabled by default, run off the UI thread, and are limited to once per
hour while the process stays alive. Settings > App updates offers a manual check and an
automatic-check toggle. Offline failures do not stop recording or show a startup modal.
Preview releases and equal/older versions are ignored. Downloading or installing requires
an explicit action.

## Android

A newer version appears as a banner in the app. Open it to review the release and download
the APK. The app accepts only the expected APK asset from Fil-Shtopor/OwlWhisp-Mobile,
requires a SHA-256 asset digest, bounds download size, restricts redirects to GitHub HTTPS
download hosts, and checks the package name, newer version code, minimum Android version
and the same signing identity as the installed app.

After verification, tap Install. Android may ask you to allow installation from OwlWhisp.
Return to the app and tap Install again; the system installer still requires confirmation.
The app never replaces itself silently or installs during an active dictation or benchmark.
Cancelled, truncated or corrupt downloads are not offered for installation.

The APK is shared through a non-exported FileProvider restricted to the private update cache.
Debug APKs have a different certificate and cannot install public release APKs as updates.
Install the public signed build to use this update path.

Version 0.1.6 is the first build with update checks, so users on older versions must install
it manually once. Future stable GitHub releases are discovered by this build.

## iOS

The app checks the same GitHub feed and offers the release page and unsigned IPA download.
With personal Apple signing, each new IPA must be signed and installed using the owner's
signing tool. The app cannot silently replace a personally signed installation. Keep the
same bundle ID and signing identity when updating. See [iOS installation](../ios/README.md).

Platform references:
- [Android installation permission](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())
- [Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
- [Apple developer account and personal testing](https://developer.apple.com/help/account/basics/about-your-developer-account)
