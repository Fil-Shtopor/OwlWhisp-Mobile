"""Prepare an Android debug build with the installed SDK and Java."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

def main():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and os.name == "nt":
        sdk = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    if not sdk or not Path(sdk).is_dir():
        raise SystemExit("Install Android SDK and set ANDROID_HOME first.")
    java = shutil.which("java")
    if not java:
        raise SystemExit("Install JDK 17 or newer and add java to PATH.")
    properties = ROOT / "android/local.properties"
    if not properties.exists():
        properties.write_text("sdk.dir=" + Path(sdk).as_posix().replace(":", "\\:") + "\n", encoding="utf-8")
    subprocess.run([sys.executable, str(ROOT / "scripts/prepare.py")], check=True)
    subprocess.run([java, "-classpath", str(ROOT / "android/gradle/wrapper/gradle-wrapper.jar"),
                    "org.gradle.wrapper.GradleWrapperMain", ":app:assembleDebug", ":app:lintDebug",
                    "--no-daemon", "--console", "plain"], cwd=ROOT / "android", check=True)

if __name__ == "__main__":
    main()
