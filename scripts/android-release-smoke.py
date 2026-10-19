"""UI smoke check for the signed APK on a disposable emulator. Downloads Phonon-2."""
import argparse, pathlib, subprocess, time, xml.etree.ElementTree as ET, re, json, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--fresh", action="store_true", help="Remove only the test app and its data on this emulator")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial): parser.error("A disposable emulator is required")
    out = ROOT / ".cache/release-ui"; out.mkdir(parents=True, exist_ok=True)
    def adb(*cmd, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *cmd], check=check, capture_output=True, timeout=45).stdout
    def dump():
        adb("shell", "uiautomator", "dump", "/data/local/tmp/owlwhisp-release-ui.xml")
        b=adb("exec-out", "cat", "/data/local/tmp/owlwhisp-release-ui.xml")
        (out/"latest.xml").write_bytes(b)
        return list(ET.fromstring(b).iter("node"))
    def text(node):return node.get("text","")
    def tap_node(node):
        bounds=list(map(int,re.findall(r"\d+",node.attrib["bounds"])))
        adb("shell","input","tap",str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
    def tap(value):
        for n in dump():
            if text(n)==value and n.get("enabled")=="true": tap_node(n);return
        raise AssertionError("Control unavailable: "+value)
    def wait(value, timeout=90):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if any(value in text(n) for n in dump()):return
            time.sleep(2)
        raise AssertionError("Timeout waiting for "+value)
    def screenshot(name):
        (out/(name+".png")).write_bytes(adb("exec-out","screencap","-p"))
    if args.fresh: adb("uninstall","ai.owlwhisp.mobile",check=False)
    adb("install","-r",str(ROOT/"android/app/build/outputs/apk/release/app-release.apk"))
    adb("shell","am","start","-n","ai.owlwhisp.mobile/.MainActivity")
    wait("Dictate")
    labels={text(n) for n in dump()}
    assert {"Dictate","Settings","Models","Benchmark","Diagnostics"}.issubset(labels)
    screenshot("dictation")
    tap("Models")
    for _ in range(8):
        nodes=dump()
        match=next((i for i,n in enumerate(nodes) if text(n)=="Phonon-2"),None)
        if match is not None:break
        adb("shell","input","swipe","450","1500","450","600","350")
    else:raise AssertionError("Phonon-2 card absent")
    def model_action(value):
        for _ in range(8):
            nodes=dump()
            model=next((i for i,n in enumerate(nodes) if text(n)=="Phonon-2"),None)
            if model is not None:
                button=next((n for n in nodes[model+1:] if text(n)==value and n.get("enabled")=="true"),None)
                if button is not None:tap_node(button);return
            adb("shell","input","swipe","450","1500","450","600","350")
        raise AssertionError("Phonon-2 action unavailable: "+value)
    model_action("Select")
    model_action("Download")
    print("Downloading and verifying the real Phonon archive...",flush=True)
    wait("Model verified",timeout=420)
    screenshot("model-installed")
    tap("Benchmark");tap("Run benchmark")
    print("Running the release benchmark on three English clips...",flush=True)
    wait("Benchmark complete",timeout=480)
    screenshot("benchmark")
    assert any("WER " in text(n) and "RTF " in text(n) for n in dump())
    time.sleep(1)
    assert not adb("shell","pidof","ai.owlwhisp.mobile:inference",check=False).strip(), "Inference process remained resident"
    tap("Diagnostics")
    assert any("Model process: unloaded" in text(n) for n in dump())
    screenshot("diagnostics")
    memory=adb("shell","dumpsys","meminfo","ai.owlwhisp.mobile").decode()
    (out/"idle-memory.txt").write_text(memory,encoding="utf-8")
    adb("shell","pm","grant","ai.owlwhisp.mobile","android.permission.RECORD_AUDIO")
    tap("Dictate");tap("Start dictation");wait("Listening")
    # Changing foreground cancels microphone capture.
    adb("shell","input","keyevent","3")
    adb("shell","am","start","-n","ai.owlwhisp.mobile/.MainActivity")
    wait("Start dictation")
    adb("shell","ime","enable","ai.owlwhisp.mobile/.VoiceInputService")
    adb("shell","ime","set","ai.owlwhisp.mobile/.VoiceInputService")
    nodes=dump();editor=next(n for n in nodes if n.get("class")=="android.widget.EditText")
    tap_node(editor);time.sleep(1)
    ime = adb("shell","dumpsys","input_method").decode()
    assert "mCurMethodId=ai.owlwhisp.mobile/.VoiceInputService" in ime and "mInputShown=true" in ime
    screenshot("voice-keyboard")
    adb("shell","input","keyevent","4")
    time.sleep(1)
    adb("shell","am","force-stop","ai.owlwhisp.mobile")
    adb("shell","am","start","-n","ai.owlwhisp.mobile/.MainActivity")
    wait("Start dictation")
    print("PASS: signed release download/extraction, native benchmark, process unload, microphone cancellation, voice IME.",flush=True)
    (out/"result.json").write_text(json.dumps({"status":"passed","serial":args.serial,"model":"phonon-2","checks":["five tabs","verified archive download and extraction","three-clip release benchmark","inference process exit","microphone foreground cancellation","voice IME enable/show/cancel"]},indent=2)+"\n")
if __name__=="__main__":main()

