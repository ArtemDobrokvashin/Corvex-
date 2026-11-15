"""Checks script guardrails only; does not run PowerShell, Maven or Docker."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class JamesAvClasspathProbeTests(unittest.TestCase):
    def test_guarded_probe_does_not_change_james_or_mail(self):
        script = (ROOT / 'experiments/james-av-gate/test-container-classpath.ps1').read_text(encoding='utf-8')
        self.assertIn('-ConfirmLaptop required', script)
        self.assertIn('docker compose ps --status running -q james', script)
        self.assertIn('docker exec $container mkdir $remote', script)
        self.assertIn('CLAMAV_PARENT_PRESENT', script)
        self.assertIn('CLAMAV_PARENT_ABSENT', script)
        self.assertIn("'^/tmp/corvex-james-avcp-[0-9a-f]{32}$'", script)
        self.assertIn('docker exec -u 0 $container', script)
        for forbidden in ('docker compose up', 'docker compose down', 'docker restart', 'docker network connect', 'docker rm', 'docker stop'):
            self.assertNotIn(forbidden, script)

    def test_java_probe_does_not_scan_or_send_mail(self):
        java = (ROOT / 'experiments/james-av-gate/src/main/java/ru/corvex/james/av/JamesAvClasspathCheck.java').read_text(encoding='utf-8')
        self.assertIn('NOT TESTED: running James loader', java)
        self.assertIn('Class.forName', java)
        self.assertNotIn('getMessage(', java)
        self.assertNotIn('new Socket(', java)


if __name__ == '__main__':
    unittest.main()
