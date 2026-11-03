from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class MonitoringConfigTest(unittest.TestCase):
    def test_loki_retention_configures_matching_delete_request_store(self):
        config = (ROOT / "infra/loki/loki.yml").read_text(encoding="utf-8")
        compactor = re.search(r"^compactor:\s*\{([^}]+)\}\s*$", config, re.MULTILINE)
        self.assertIsNotNone(compactor, "compactor config is missing or not inline YAML")
        settings = compactor.group(1)
        self.assertIn("retention_enabled: true", settings)
        self.assertIn("delete_request_store: filesystem", settings)
        self.assertIn("object_store: filesystem", config)


if __name__ == "__main__":
    unittest.main()
