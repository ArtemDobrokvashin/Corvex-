"""Guard: the SPA entry point must never be served stale.

Symptom this prevents: after `docker compose up -d --build frontend` the browser
keeps an old index.html that references hashed assets which no longer exist.
The user then sees the previous interface (or a blank page) and concludes the
rebuild did nothing. Hashed assets stay immutable; index.html must revalidate.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "frontend" / "nginx-spa.conf"


class SpaCacheHeaderTest(unittest.TestCase):
    def setUp(self):
        self.text = CONFIG.read_text(encoding="utf-8")

    def test_entry_point_revalidates(self):
        blocks = re.findall(r"location\s*=?\s*/index\.html\s*\{(.*?)\}", self.text, re.S)
        self.assertTrue(blocks, "no explicit location for index.html")
        for block in blocks:
            with self.subTest(block=block[:40]):
                self.assertIn("no-cache", block)

    def test_root_location_does_not_cache_html(self):
        match = re.search(r"location\s+/\s*\{(.*?)\}", self.text, re.S)
        self.assertIsNotNone(match)
        self.assertIn("no-cache", match.group(1))

    def test_hashed_assets_stay_immutable(self):
        match = re.search(r"location\s+/assets/\s*\{(.*?)\}", self.text, re.S)
        self.assertIsNotNone(match)
        block = match.group(1)
        self.assertIn("immutable", block)
        self.assertIn("expires 1y", block)


if __name__ == "__main__":
    unittest.main()
