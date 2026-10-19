# iOS

SwiftUI app and OwlWhispCore Swift package. Minimum iOS 16.0.

The app has OwlWhisp branding and the same five sections as desktop, ordinary Parakeet INT8,
verified downloads/deletion, microphone capture, copy/share and a six-clip FLEURS benchmark.
Model sessions are scoped to one job. Background/interruption invalidates capture and delivery.

## Release downloads

- `OwlWhisp-Mobile-<version>-ios-unsigned.ipa`: Release build for ARM64 iPhones/iPads.
  **Requires signing before installation.** It is not a TestFlight or App Store distribution.
- `OwlWhisp-Mobile-<version>-ios-simulator.app.zip`: Xcode simulator app for macOS.
  It does not install on an iPhone. Unzip, boot a simulator and run
  `xcrun simctl install booted OwlWhispMobile.app`.
- `ios-build-info.json`: source revision, Xcode version, architectures and Mach-O platform checks.

GitHub Actions runs Swift package tests, archives the device app and builds the simulator app.
The workflow uploads both packages as the `ios-release` artifact; tested packages are attached
to GitHub releases. Models download separately after installation.

## Personal signing from Windows or macOS

A paid developer account is not required for personal sideloading with
[Sideloadly](https://sideloadly.io/). This is a third-party signing tool, separate from OwlWhisp.

1. Download the release's `ios-unsigned.ipa` file to your computer.
2. Install Sideloadly from its official site. On Windows, follow its instructions for the
   web versions of Apple's iTunes and iCloud; its site provides the required Apple links.
3. Connect and unlock the iPhone by USB, trust the computer and select it in Sideloadly.
4. Load the IPA, use your own Apple account in Sideloadly and start installation. Never send
   your Apple password, verification code or signing identity to this repository or its chat.
5. If iOS requests it, trust the developer in Settings > General > VPN & Device Management
   and enable Developer Mode in Settings > Privacy & Security, then restart as prompted.
6. Launch OwlWhisp, download Parakeet in Models, allow the microphone and dictate. Copy or
   share the transcript into another app.

With a free account the signature expires after seven days; re-sign/refresh with the same
account before it expires. Sideloadly offers automatic refresh while the computer is available.
The iPhone still needs iOS 16 or later. Do not lower the IPA's minimum OS version in the tool.
This repository's IPA packaging is validated; signing and microphone operation on a physical
iPhone still need device testing. See Sideloadly's official installation guide and FAQ for its
current requirements. A simulator ZIP is not the file to use for personal signing.

## Build and install on an iPhone

On macOS install Xcode and XcodeGen, then from the repository root:

    cd ios
    swift test
    xcodegen generate
    open OwlWhispMobile.xcodeproj

In Xcode, select the OwlWhispMobile target, enable Automatically manage signing, select your
Apple team, choose a unique bundle identifier if required, connect the iPhone and run the app.
A free Personal Team can be used for development, subject to Apple's provisioning limits.
Enable Developer Mode on the iPhone if Xcode requests it.

For TestFlight/App Store distribution, the repository owner must configure an Apple Developer
Program team and App Store Connect app, then archive and distribute through Xcode Organizer.
No signing certificates, provisioning profiles or Apple account credentials are stored in Git.
Do not paste signing credentials into issues, logs or chat.

For the same unsigned packages as CI, run `python3 scripts/build_ios.py` on macOS. Output is
`.cache/ios-release/`. These builds do not prove physical-iPhone runtime behavior.

## Current scope

No physical-iPhone validation or signed IPA is provided. Redux, Whistle and Phonon native
adapters and a keyboard extension remain future work for iOS. Apple custom keyboard
extensions cannot directly access the microphone; dictation runs in the main app, followed
by copy or share into another app.

Weights are CC-BY-4.0 and download separately. Audio is bounded to 60 seconds and retained in
memory during a job. The app sends neither audio nor transcripts to a transcription service.

References: [Apple device distribution](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices),
[Apple developer accounts](https://developer.apple.com/help/account/basics/about-your-developer-account),
[custom keyboard restrictions](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html).
