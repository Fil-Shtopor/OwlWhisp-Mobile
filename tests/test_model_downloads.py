import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare import fetch, model_files, verified

class DownloadIntegrityTests(unittest.TestCase):
    def spec(self, data):
        return {"url": "https://example.com/model", "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest()}

    def test_failed_integrity_does_not_replace_existing_model(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "model.onnx"
            destination.write_bytes(b"old")
            with patch("urllib.request.urlopen", return_value=io.BytesIO(b"bad")):
                with self.assertRaises(ValueError):
                    fetch(self.spec(b"new"), destination)
            self.assertEqual(destination.read_bytes(), b"old")
            self.assertFalse(destination.with_suffix(".onnx.part").exists())

    def test_oversized_download_is_rejected_and_partial_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "model.onnx"
            with patch("urllib.request.urlopen", return_value=io.BytesIO(b"too large")):
                with self.assertRaises(ValueError):
                    fetch(self.spec(b"ok"), destination)
            self.assertFalse(destination.exists())
            self.assertFalse(destination.with_suffix(".onnx.part").exists())

    def test_valid_cache_does_not_contact_network(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "model.onnx"
            destination.write_bytes(b"valid")
            with patch("urllib.request.urlopen", side_effect=AssertionError("Unexpected network")):
                self.assertEqual(fetch(self.spec(b"valid"), destination), destination)

    def test_all_catalog_downloads_and_recognizer_paths(self):
        directory = ROOT / "shared/models"
        catalog = json.loads((directory / "catalog.json").read_text(encoding="utf-8"))
        self.assertIn("gigaam-v3-ru", catalog["android"])
        self.assertIn("gigaam-v3-ru", catalog["ios"])
        self.assertEqual(len(catalog["android"]), len(set(catalog["android"])))
        for name in catalog["android"]:
            manifest = json.loads((directory / (name + ".json")).read_text(encoding="utf-8"))
            specs = model_files(manifest)
            self.assertEqual(manifest["id"], name)
            if name in catalog["ios"]:
                recognition = manifest["recognition"]
                files = {row["path"] for row in specs}
                for key, value in recognition.items():
                    if key not in {"family", "featureDim", "modelType"}:
                        if key == "tokenizer":
                            self.assertTrue({value + "/" + suffix for suffix in ["vocab.json", "merges.txt", "tokenizer_config.json"]} <= files)
                        else:
                            self.assertIn(value, files)
        unsafe = {"common": [{"path": "tokenizer/../escape", **self.spec(b"x")}], "artifacts": [{"target": "cpu_int8", "files": []}]}
        with self.assertRaises(ValueError):
            model_files(unsafe)

    def test_manifest_has_pinned_regular_parakeet_and_safe_paths(self):
        manifest = json.loads((ROOT / "shared/models/parakeet-tdt-0.6b-v3.json").read_text())
        specs = model_files(manifest)
        self.assertEqual(manifest["id"], "parakeet-tdt-0.6b-v3")
        self.assertEqual(len(manifest["languages"]), 25)
        self.assertIn("ru", manifest["languages"])
        self.assertEqual(len(specs), 4)
        specs[0]["path"] = "../escape.onnx"
        with self.assertRaises(ValueError):
            model_files(manifest)

if __name__ == "__main__":
    unittest.main()
