"""Enable and select the voice IME through Android API 34 system UI, without ime set/enable."""
import argparse
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "ai.owlwhisp.mobile"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk", type=pathlib.Path, default=ROOT / "android/app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--fresh", action="store_true", help="Remove only OwlWhisp from this disposable emulator")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("An explicitly selected disposable emulator is required")
    destination = ROOT / ".cache/keyboard-smoke"
    destination.mkdir(parents=True, exist_ok=True)
    def adb(*cmd, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *cmd], check=check, capture_output=True, timeout=45).stdout
    def dump():
        for _ in range(5):
            message = adb("shell", "uiautomator", "dump", "/data/local/tmp/owlwhisp-keyboard-smoke.xml")
            if b"dumped to" in message:
                raw = adb("exec-out", "cat", "/data/local/tmp/owlwhisp-keyboard-smoke.xml")
                (destination / "latest.xml").write_bytes(raw)
                return ET.fromstring(raw)
            time.sleep(1)
        raise AssertionError("Android did not provide a UI hierarchy")
    def wait(predicate):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            tree = dump()
            if predicate(tree):
                return tree
            time.sleep(0.5)
        raise AssertionError("Expected screen did not appear")
    def has_text(tree, value):
        return any(n.get("text") == value for n in tree.iter("node"))
    def tap_node(node):
        a, b, c, d = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        adb("shell", "input", "tap", str((a+c)//2), str((b+d)//2))
        time.sleep(0.5)
    def tap(value):
        tree = wait(lambda tree: has_text(tree, value))
        node = next(n for n in tree.iter("node") if n.get("text") == value)
        assert node.get("enabled") == "true", value
        tap_node(node)
    def screenshot(name):
        (destination / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    if args.fresh:
        adb("uninstall", PACKAGE, check=False)
    adb("install", "-r", str(args.apk))
    adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    tap("Set up voice keyboard")
    tree = wait(lambda tree: has_text(tree, "Voice keyboard setup"))
    if has_text(tree, "Microphone: permission required"):
        tap("2. Allow microphone access")
        tap("While using the app")
    wait(lambda tree: has_text(tree, "Microphone: allowed"))
    screenshot("setup")
    tap("3. Enable OwlWhisp in keyboard settings")
    tree = wait(lambda tree: any(n.get("text", "").startswith("OwlWhisp") for n in tree.iter("node")))
    # The row label opens the IME settings activity. Toggle its actual switch.
    row = next(n for n in tree.iter("node") if n.get("clickable") == "true"
               and any(c.get("text", "").startswith("OwlWhisp") for c in n.iter("node"))
               and any(c.get("checkable") == "true" for c in n.iter("node")))
    switch = next(c for c in row.iter("node") if c.get("checkable") == "true")
    screenshot("system-keyboard-list")
    if switch.get("checked") != "true":
        tap_node(switch)
        tap("OK")
        tree = dump()
        if any("After a reboot" in n.get("text", "") for n in tree.iter("node")):
            tap("OK")
    enabled = adb("shell", "settings", "get", "secure", "enabled_input_methods").decode()
    assert PACKAGE + "/.VoiceInputService" in enabled, enabled
    adb("shell", "input", "keyevent", "4")
    wait(lambda tree: has_text(tree, "Keyboard: enabled; choose OwlWhisp to dictate")
         or has_text(tree, "Keyboard: enabled and selected"))
    tap("4. Choose OwlWhisp")
    tree = wait(lambda tree: has_text(tree, "Choose input method"))
    screenshot("system-keyboard-picker")
    tap_node(next(n for n in tree.iter("node") if n.get("text", "").startswith("OwlWhisp")))
    wait(lambda tree: has_text(tree, "Keyboard: enabled and selected"))
    assert adb("shell", "settings", "get", "secure", "default_input_method").decode().strip() == PACKAGE + "/.VoiceInputService"
    # Focus a real app editor to display the selected IME.
    adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", "--ei", "tab", "0",
        "-f", "0x24000000")
    tree = wait(lambda tree: has_text(tree, "Set up voice keyboard"))
    tap_node(next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText"))
    time.sleep(1)
    state = adb("shell", "dumpsys", "input_method").decode()
    assert "mCurMethodId=" + PACKAGE + "/.VoiceInputService" in state and "mInputShown=true" in state
    screenshot("voice-keyboard")
    adb("shell", "input", "keyevent", "4")
    result = {"status": "passed", "serial": args.serial, "apk": args.apk.name,
              "apk_sha256": __import__("hashlib").sha256(args.apk.read_bytes()).hexdigest(),
              "checks": ["setup microphone permission", "IME listed in Android settings",
                         "enabled with visible system switch and confirmations",
                         "selected from system picker", "setup status refreshed",
                         "IME shown in an editable text field"],
              "adb_ime_enable_or_set_used": False, "samsung_device_validated": False}
    (destination / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main()
