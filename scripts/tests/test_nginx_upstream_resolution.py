"""Guard: nginx must resolve upstream names at request time, not at startup.

Symptom this prevents: after `docker compose up -d --build frontend` the edge
nginx keeps the old container IP and returns 502 for the SPA until someone
restarts nginx. The fix is a resolver plus a variable in proxy_pass; this test
keeps that pattern from being reverted by accident.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "infra" / "nginx" / "conf.d" / "corvex.conf"


class NginxUpstreamResolutionTest(unittest.TestCase):
    def setUp(self):
        self.text = CONFIG.read_text(encoding="utf-8")

    def test_docker_resolver_is_configured(self):
        self.assertIn("resolver 127.0.0.11", self.text)

    def test_every_proxy_pass_uses_a_variable(self):
        directives = re.findall(r"^\s*proxy_pass\s+([^;]+);", self.text, re.M)
        self.assertTrue(directives, "no proxy_pass directives found")
        for target in directives:
            with self.subTest(target=target):
                self.assertTrue(
                    target.strip().startswith("$"),
                    f"proxy_pass {target!r} resolves the name at startup; "
                    "use `set $name http://service:port;` and proxy_pass $name",
                )

    def test_variable_is_declared_inside_the_same_location(self):
        # nginx variables set in one location do not exist in another, so each
        # location that proxies must set its own upstream variable.
        for block in re.findall(r"location\s+[^{]+\{([^}]*)\}", self.text, re.S):
            if "proxy_pass" in block:
                with self.subTest(block=block.strip()[:40]):
                    self.assertIn("set $", block)

    def test_frontend_and_gateway_are_covered(self):
        for service in ("frontend:80", "web-gateway:8080"):
            with self.subTest(service=service):
                self.assertIn(service, self.text)


if __name__ == "__main__":
    unittest.main()
