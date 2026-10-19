"""Reproduce Android native dependencies from immutable upstream revisions."""
import argparse, json, os, pathlib, shutil, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
REVISIONS = {
    "voxrt": ("lmaksym/voxrt", "4fab626c00708d9bc97f837d309e0d362094282b"),
    "phonon-2-c": ("eschmidbauer/phonon-2-c", "c4b860f6ad745369d6f21599d6e23d381c1fcc7a"),
}
def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-rust", action="store_true")
    args = parser.parse_args()
    for name, (repo, revision) in REVISIONS.items():
        dest = ROOT / "third_party" / name
        if not (dest / ".git").exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            run("git", "clone", "--no-checkout", "https://github.com/" + repo, str(dest))
        run("git", "-C", str(dest), "fetch", "--depth", "1", "origin", revision)
        run("git", "-C", str(dest), "checkout", "--detach", revision)
    from prepare import fetch
    lock = json.loads((ROOT / "shared/native-lock.json").read_text())
    for name in ("needle_header", "needle_arm64"):
        spec = lock[name]
        fetch(spec, ROOT / "third_party/needle" / spec["path"])
    if args.skip_rust:
        return
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or pathlib.Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    ndk = sdk / "ndk/27.1.12297006/toolchains/llvm/prebuilt"
    host = "windows-x86_64" if os.name == "nt" else "darwin-x86_64" if __import__("sys").platform == "darwin" else "linux-x86_64"
    toolchain = ndk / host / "bin"
    env = os.environ.copy()
    env["CARGO_TARGET_DIR"] = str(ROOT / ".cache/voxrt-target")
    env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
    for abi, target, compiler in (("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android28-clang"), ("x86_64", "x86_64-linux-android", "x86_64-linux-android28-clang")):
        run("rustup", "target", "add", target)
        env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(toolchain / (compiler + (".cmd" if os.name == "nt" else "")))
        run("cargo", "build", "--manifest-path", str(ROOT / "third_party/voxrt/Cargo.toml"), "--locked", "--release", "-p", "voxrt-ffi", "--no-default-features", "--target", target, env=env)
        dest = ROOT / "android/app/src/main/jniLibs" / abi
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / ".cache/voxrt-target" / target / "release/libvoxrt.so", dest / "libvoxrt.so")
if __name__ == "__main__":
    main()
