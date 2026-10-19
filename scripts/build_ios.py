"""Build device and simulator iOS artifacts on macOS. Device IPA requires signing."""
import argparse
import hashlib
import json
import pathlib
import plistlib
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

def run(*args, cwd=ROOT):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)

def output(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True).strip()

def inspect(app, platform):
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    binary = app / info["CFBundleExecutable"]
    archs = output("lipo", "-archs", binary).split()
    build = output("xcrun", "vtool", "-show-build", binary)
    assert info["CFBundleIdentifier"] == "ai.owlwhisp.mobile", info
    assert re.search(r"\bplatform\s+" + platform + r"\s", build + "\n"), build
    assert "arm64" in archs, archs
    return {"architectures": archs, "version": info["CFBundleShortVersionString"],
            "build": info["CFBundleVersion"], "minimum_os": info["MinimumOSVersion"],
            "platform": platform, "load_commands": build}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / ".cache/ios-release")
    args = parser.parse_args()
    if output("uname", "-s") != "Darwin":
        parser.error("macOS with Xcode and XcodeGen is required")
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    build_root = ROOT / ".cache/ios"
    run("xcodegen", "generate", cwd=ROOT / "ios")
    common = ["xcodebuild", "-project", "OwlWhispMobile.xcodeproj", "-scheme", "OwlWhispMobile",
              "-configuration", "Release", "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO"]
    archive = build_root / "OwlWhispMobile.xcarchive"
    run(*common, "-sdk", "iphoneos", "-destination", "generic/platform=iOS",
        "-derivedDataPath", build_root / "device", "-archivePath", archive,
        "archive", cwd=ROOT / "ios")
    device_app = archive / "Products/Applications/OwlWhispMobile.app"
    device = inspect(device_app, "IOS")
    version = device["version"]
    ipa = destination / f"OwlWhisp-Mobile-{version}-ios-unsigned.ipa"
    with tempfile.TemporaryDirectory(prefix="owlwhisp-ipa-") as temp:
        payload = pathlib.Path(temp) / "Payload"
        payload.mkdir()
        shutil.copytree(device_app, payload / device_app.name, symlinks=True)
        run("ditto", "-c", "-k", "--keepParent", payload, ipa)
    run(*common, "-sdk", "iphonesimulator", "-destination", "generic/platform=iOS Simulator",
        "-derivedDataPath", build_root / "simulator", "build", cwd=ROOT / "ios")
    simulator_app = build_root / "simulator/Build/Products/Release-iphonesimulator/OwlWhispMobile.app"
    simulator = inspect(simulator_app, "IOSSIMULATOR")
    simulator_zip = destination / f"OwlWhisp-Mobile-{version}-ios-simulator.app.zip"
    run("ditto", "-c", "-k", "--keepParent", simulator_app, simulator_zip)
    report = {"source_commit": output("git", "rev-parse", "HEAD"),
              "xcode": output("xcodebuild", "-version"), "device": device, "simulator": simulator,
              "device_signing": "Unsigned. Requires an Apple signing identity and provisioning before installation.",
              "simulator_install": "macOS/Xcode only. Unzip, then xcrun simctl install booted OwlWhispMobile.app.",
              "physical_iphone_validated": False}
    report_path = destination / "ios-build-info.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    files = [ipa, simulator_zip, report_path]
    (destination / "iOS-SHA256SUMS").write_text("".join(
        hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in files), encoding="utf-8")
    print("Artifacts:", *files, sep="\n")
    print("The device IPA is unsigned and cannot be installed directly on an iPhone.")

if __name__ == "__main__":
    main()
