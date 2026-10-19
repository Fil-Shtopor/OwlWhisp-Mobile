"""Check circular voice controls against real gesture and three-button navigation in a test emulator."""
import argparse
import json
import pathlib
import re
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("An explicitly selected disposable emulator is required")
    destination = ROOT / ".cache/panel-smoke"
    destination.mkdir(parents=True, exist_ok=True)
    def adb(*cmd):
        return subprocess.check_output([args.adb, "-s", args.serial, *cmd], timeout=120)
    adb("install", "-r", str(ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"))
    rows = {}
    try:
        for mode, expected in [("gestural", "2"), ("threebutton", "0")]:
            adb("shell", "cmd", "overlay", "enable-exclusive", "--category", "--user", "0", "com.android.internal.systemui.navbar." + mode)
            time.sleep(1)
            actual = adb("shell", "settings", "get", "secure", "navigation_mode").decode().strip()
            assert actual == expected, (mode, actual)
            output = adb("shell", "am", "instrument", "-w", "ai.owlwhisp.mobile/ai.owlwhisp.mobile.PanelInsetsInstrumentation").decode()
            (destination / (mode + ".txt")).write_text(output, encoding="utf-8")
            assert "INSTRUMENTATION_CODE: -1" in output and "failure=" not in output, output
            match = re.search(r"INSTRUMENTATION_RESULT: layout=(.*)", output)
            assert match, output
            rows[mode] = json.loads(match.group(1))
            for host in ["edge", "reserved"]:
                for phase in ["ready", "recording"]:
                    name = "panel-" + host + "-" + phase + ".png"
                    image = adb("exec-out", "run-as", "ai.owlwhisp.mobile", "cat", "files/" + name)
                    (destination / (mode + "-" + name)).write_bytes(image)
            print(mode + ": all four real-window layout checks passed", flush=True)
    finally:
        adb("shell", "cmd", "overlay", "enable-exclusive", "--category", "--user", "0", "com.android.internal.systemui.navbar.gestural")
    report = {"status": "passed", "serial": args.serial, "scope": "debug panel rendering, real navigation bars, both host inset behaviors", "rows": rows}
    (destination / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
