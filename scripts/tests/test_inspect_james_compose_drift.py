"""Static safety checks, not a Windows/Docker execution test."""
from pathlib import Path
import unittest


class JamesComposeDriftTest(unittest.TestCase):
    def test_reports_only_aggregate_sensitive_values_and_is_read_only(self):
        script = (Path(__file__).resolve().parents[1] / 'inspect-james-compose-drift.ps1').read_bytes().decode('ascii')
        for item in ('config --format json james', 'Compose service hash: DIFFERENT',
                     'Compose environment keys checked=', '(values withheld)',
                     'Compose mounts checked=', 'Compose networks checked=',
                     'partial comparison, not proof that recreation is safe', 'No files, networks, containers'):
            self.assertIn(item, script)
        for item in ('docker compose up', 'docker compose down', 'docker network connect', 'docker cp',
                     'Remove-Item', 'Set-Content', 'WriteAllText', 'Write-Host $cfg', 'Write-Host $raw',
                     'Write-Host $actualEnv'):
            self.assertNotIn(item, script)


if __name__ == '__main__':
    unittest.main()
