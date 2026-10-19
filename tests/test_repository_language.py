"""Keep authored repository text English while preserving multilingual ASR data."""
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RepositoryLanguageTests(unittest.TestCase):
    def test_tracked_text_has_no_literal_cyrillic(self):
        names = subprocess.run(
            ["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True
        ).stdout.decode("utf-8").split("\0")
        violations = []
        for name in filter(None, names):
            path = ROOT / name
            if not path.is_file():
                continue
            data = path.read_bytes()
            if b"\0" in data:
                continue
            try:
                content = data.decode("utf-8")
            except UnicodeDecodeError:
                continue
            for number, line in enumerate(content.splitlines(), 1):
                if re.search(r"[\u0400-\u052f]", line):
                    violations.append(f"{name}:{number}")
        self.assertEqual([], violations, "Use English or Unicode escapes: " + ", ".join(violations))


if __name__ == "__main__":
    unittest.main()
