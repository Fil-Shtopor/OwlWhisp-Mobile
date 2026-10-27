"""Keep shared recommendation data aligned with the shipped platform catalogs."""
from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]

class ModelGuideTests(unittest.TestCase):
    def test_guide_references_shipped_models_and_memory_profiles(self):
        guide = json.loads((ROOT / "shared/model-guide.json").read_text(encoding="utf-8"))
        catalog = json.loads((ROOT / "shared/models/catalog.json").read_text(encoding="utf-8"))
        entries = {row["id"]: row for row in guide["models"]}
        self.assertEqual(set(catalog["android"]), set(entries))
        self.assertEqual(len(entries), len(guide["models"]))
        tiers = {"compact", "balanced", "roomy"}
        self.assertEqual({profile["tier"] for profile in guide["profiles"]}, tiers)
        for language, rules in guide["recommendations"].items():
            self.assertEqual(set(rules), tiers, language)
            for order in rules.values():
                self.assertTrue(order)
                self.assertEqual(len(order), len(set(order)))
                self.assertTrue(set(order) <= entries.keys())
        for entry in entries.values():
            self.assertTrue(entry["name"] and entry["purpose"])
            self.assertTrue(entry["source"].startswith("https://"))
        for profile in guide["profiles"]:
            self.assertTrue(profile["sources"] and profile["examples"])

if __name__ == "__main__":
    unittest.main()
