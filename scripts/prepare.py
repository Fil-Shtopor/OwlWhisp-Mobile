"""Fetch the pinned Android runtime; model weights are downloaded by the app."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def verified(path, spec):
    if not path.is_file() or path.stat().st_size != spec["bytes"]:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == spec["sha256"]

def fetch(spec, destination, progress=None):
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if verified(destination, spec):
        return destination
    temporary = destination.with_suffix(destination.suffix + ".part")
    digest = hashlib.sha256()
    count = 0
    try:
        request = urllib.request.Request(spec["url"], headers={"User-Agent": "OwlWhisp-Mobile/0.1"})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > spec["bytes"]:
                    raise ValueError("Download exceeds the pinned size")
                digest.update(chunk)
                output.write(chunk)
                if progress:
                    progress(count, spec["bytes"])
        if count != spec["bytes"] or digest.hexdigest() != spec["sha256"]:
            raise ValueError("Downloaded file failed size/SHA-256 verification")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination

def model_files(manifest):
    variants = [item for item in manifest["artifacts"] if item["target"] == "cpu_int8"]
    if len(variants) != 1:
        raise ValueError("Expected one CPU INT8 artifact set")
    files = manifest["common"] + variants[0]["files"]
    names = set()
    for item in files:
        name = item["path"]
        if not all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) and part not in {".", ".."} for part in name.split("/")) or name in names:
            raise ValueError("Unsafe or duplicate model file path")
        if not item["url"].startswith("https://"):
            raise ValueError("Model downloads must use HTTPS")
        if item["bytes"] <= 0 or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]):
            raise ValueError("Invalid file pin")
        names.add(name)
    return files

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, help="Also fetch the Russian model into this directory")
    args = parser.parse_args()
    lock = json.loads((ROOT / "shared/runtime-lock.json").read_text())
    print(fetch(lock["android"], ROOT / "android/app/libs/sherpa-onnx.aar"), flush=True)
    if args.model_dir:
        manifest = json.loads((ROOT / "shared/models/parakeet-tdt-0.6b-v3.json").read_text())
        for spec in model_files(manifest):
            print(fetch(spec, args.model_dir / spec["path"]), flush=True)

if __name__ == "__main__":
    main()
