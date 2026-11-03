from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class MonitoringConfigTest(unittest.TestCase):
    def test_loki_retention_configures_matching_delete_request_store(self):
        config = (ROOT / "infra/loki/loki.yml").read_text(encoding="utf-8")
        compactor = re.search(r"^compactor:\s*\{([^}]+)\}\s*$", config, re.MULTILINE)
        self.assertIsNotNone(
            compactor, "compactor config is missing or not inline YAML"
        )
        settings = compactor.group(1)
        self.assertIn("retention_enabled: true", settings)
        self.assertIn("delete_request_store: filesystem", settings)
        self.assertIn("object_store: filesystem", config)

    def test_alertmanager_has_independent_internal_and_external_deliveries(self):
        config = (ROOT / "infra/prometheus/alertmanager.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            "http://notification-service:8091/internal/alerts/webhook", config
        )
        self.assertIn("http://alert-relay:8092/alerts/webhook", config)
        self.assertEqual(config.count("send_resolved: true"), 2)
        self.assertIn("group_by: [alertname, severity]", config)

    def test_repeat_intervals_are_validated_before_alertmanager_starts(self):
        template = (ROOT / "infra/prometheus/alertmanager.yml").read_text(
            encoding="utf-8"
        )
        entrypoint = (ROOT / "infra/prometheus/alertmanager-entrypoint.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("repeat_interval: __CORVEX_ALERT_REPEAT_INTERVAL__", template)
        self.assertIn(
            "repeat_interval: __CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL__", template
        )
        self.assertIn("/bin/amtool check-config", entrypoint)
        self.assertLess(
            entrypoint.index("/bin/amtool check-config"),
            entrypoint.index("exec /bin/alertmanager"),
        )
        self.assertNotIn("BOT_TOKEN", entrypoint)
        self.assertNotIn("SMTP_PASSWORD", entrypoint)

    def test_external_relay_is_internal_only_and_opt_in(self):
        compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
        relay = compose.split("  alert-relay:\n", 1)[1].split("\n  alertmanager:\n", 1)[
            0
        ]
        self.assertIn("profiles: [monitoring]", relay)
        self.assertIn("networks: [monitoring]", relay)
        self.assertNotIn("ports:", relay)
        self.assertIn("CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED:-false", relay)
        self.assertIn("CORVEX_ALERT_TELEGRAM_ENABLED:-false", relay)
        self.assertIn("CORVEX_ALERT_MAX_ENABLED:-false", relay)

    def test_silences_persist_and_have_bounded_management_commands(self):
        compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
        script = (ROOT / "scripts/alerts.ps1").read_text(encoding="utf-8")
        self.assertIn("alertmanager-data:/alertmanager", compose)
        self.assertIn("alertmanager-data: {}", compose)
        self.assertIn('"silence", "add"', script)
        self.assertIn('"silence", "expire"', script)
        self.assertIn("ValidateRange(1, 43200)", script)
        self.assertIn('"alertname=~.+"', script)
        self.assertNotIn("down -v", script)
        self.assertNotIn("prune", script)

    def test_alertmanager_data_is_not_mounted_into_external_relay(self):
        compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
        relay = compose.split("  alert-relay:\n", 1)[1].split("\n  alertmanager:\n", 1)[
            0
        ]
        self.assertNotIn("alertmanager-data", relay)
        self.assertNotIn("docker.sock", relay)
        self.assertIn(":/etc/corvex/alert-ca:ro", relay)
        self.assertNotIn("CORVEX_CERTS_DIR", relay)

    def test_standard_verify_gates_include_alert_relay_tests(self):
        windows = (ROOT / "scripts/verify.ps1").read_text(encoding="utf-8")
        linux = (ROOT / "scripts/verify.sh").read_text(encoding="utf-8")
        self.assertIn('"unittest", "discover"', windows)
        self.assertIn("python3 -m unittest discover", linux)
        self.assertIn("sh -n infra/prometheus/alertmanager-entrypoint.sh", linux)

    def test_windows_checkout_cannot_turn_container_shell_scripts_into_crlf(self):
        attributes = (ROOT / ".gitattributes").read_text(encoding="utf-8")
        self.assertIn("*.sh text eol=lf", attributes)
        for script in ROOT.rglob("*.sh"):
            self.assertNotIn(b"\r\n", script.read_bytes(), str(script))
        entrypoint = (ROOT / "infra/prometheus/alertmanager-entrypoint.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            ".gitattributes keeps this bind-mounted script LF-only", entrypoint
        )


if __name__ == "__main__":
    unittest.main()
