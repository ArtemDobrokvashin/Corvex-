"""Structural safeguards only; this does not run Docker, PowerShell or test rollback."""
from pathlib import Path
import unittest


class AvGuardDeploymentTest(unittest.TestCase):
    def test_deployment_guards(self):
        text = (Path(__file__).resolve().parents[1] / 'deploy-av-guard.ps1').read_bytes().decode('ascii')
        for marker in ('if (-not $Apply)', 'Source differs:', 'Extra source file.',
                       'Service tag no longer points to old image.', 'Compose configuration changed.',
                       'messages_unacknowledged', 'Queue is not idle:', 'deployment-manifest.json',
                       'Backup tag verification failed.', 'Candidate tag verification failed.',
                       '--no-deps --no-build --pull never --force-recreate mail-antivirus-service',
                       'Do not auto-rollback', 'not an EICAR/outage test'):
            self.assertIn(marker, text)
        self.assertLess(text.index("$stage = 'queue-snapshot'"), text.index("$stage = 'activate-tag'"))
        self.assertLess(text.index('Backup tag verification failed.'), text.index("$stage = 'activate-tag'"))
        for prohibited in ('docker compose down', 'docker compose build', 'docker image rm', 'Remove-Item', 'purge_queue', 'docker volume'):
            self.assertNotIn(prohibited, text)

    def test_rollback_is_explicit_and_preserves_queue(self):
        text = (Path(__file__).resolve().parents[1] / 'rollback-av-guard.ps1').read_bytes().decode('ascii')
        for marker in ('-not $ConfirmLaptop', 'Backup image unavailable.', 'Compose configuration changed.',
                       'Service tag changed independently.', 'Unexpected service image.',
                       '--no-deps --no-build --pull never --force-recreate mail-antivirus-service',
                       'Mail already processed is not undone'):
            self.assertIn(marker, text)
        for prohibited in ('docker compose down', 'docker compose build', 'docker image rm', 'Remove-Item', 'purge_queue', 'docker volume'):
            self.assertNotIn(prohibited, text)


if __name__ == '__main__':
    unittest.main()
