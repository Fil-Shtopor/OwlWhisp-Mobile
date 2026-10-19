"""The release builder must resolve metadata from its checkout, not the caller's directory."""
import os
import pathlib
import runpy
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class IOSPackagingTests(unittest.TestCase):
    def test_source_commit_is_read_from_the_builders_checkout(self):
        builder = runpy.run_path(str(ROOT / "scripts/build_ios.py"))
        expected = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        previous = pathlib.Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                actual = builder["output"]("git", "rev-parse", "HEAD")
            finally:
                os.chdir(previous)
        self.assertEqual(actual, expected)

if __name__ == "__main__":
    unittest.main()
