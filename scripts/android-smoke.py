"""Install a debug APK into an explicitly selected emulator and run actual offline inference."""
import argparse
import json
import shutil
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True, help="An emulator serial, e.g. emulator-5580")
    parser.add_argument("--model-dir", type=Path, default=ROOT / ".cache/models/parakeet-tdt-0.6b-v3")
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Select a test emulator; this script does not install on personal phones")
    base = [args.adb, "-s", args.serial]
    def adb(*command, **kwargs):
        return subprocess.run(base + list(command), check=True, **kwargs)
    apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
    if not apk.is_file():
        parser.error("Build the debug APK first")
    sys.path.insert(0, str(ROOT / "scripts"))
    from prepare import model_files, verified
    manifest = json.loads((ROOT / "shared/models/parakeet-tdt-0.6b-v3.json").read_text())
    specs = model_files(manifest)
    for spec in specs:
        if not verified(args.model_dir / spec["path"], spec):
            parser.error("Model file is absent or corrupt: " + spec["path"])
    adb("install", "-r", str(apk))
    target = "no_backup/models/parakeet-tdt-0.6b-v3"
    adb("shell", "run-as", "ai.owlwhisp.mobile", "mkdir", "-p", target)
    for spec in specs:
        print("Copying", spec["path"], flush=True)
        temporary = "/data/local/tmp/owlwhisp-smoke-" + spec["path"]
        adb("push", str(args.model_dir / spec["path"]), temporary)
        adb("shell", "run-as", "ai.owlwhisp.mobile", "cp", temporary, target + "/" + spec["path"])
        adb("shell", "rm", temporary)
    report = adb("shell", "am", "instrument", "-w",
                 "ai.owlwhisp.mobile/ai.owlwhisp.mobile.SmokeInstrumentation",
                 capture_output=True, text=True, encoding="utf-8")
    print(report.stdout)
    if "INSTRUMENTATION_CODE: -1" not in report.stdout or "failure=" in report.stdout:
        raise SystemExit("Offline inference smoke check failed")
    (ROOT / ".cache/android-smoke.txt").write_text(report.stdout, encoding="utf-8")

if __name__ == "__main__":
    main()
