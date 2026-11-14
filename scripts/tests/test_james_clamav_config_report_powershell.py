"""Static checks only. Windows PowerShell/Docker execution is a separate test."""
from pathlib import Path
import unittest


class PowerShellClamavReportTest(unittest.TestCase):
    def test_only_fixed_read_only_container_command(self):
        text = (Path(__file__).resolve().parents[1] / 'james-clamav-config-report.ps1').read_text(encoding='utf-8')
        self.assertIn("@('exec', '-T', 'clamav', 'cat', '/etc/clamav/clamd.conf')", text)
        self.assertIn('$exitCode = $LASTEXITCODE', text)
        self.assertIn('2>$null', text)
        self.assertNotIn('docker compose up', text)
        self.assertNotIn('Set-Content', text)
        self.assertNotIn('Remove-Item', text)
        self.assertIn('$keys -ccontains $key', text)
        self.assertIn('не задано явно', text)
        self.assertIn('не подтверждает runtime-настройки', text)


if __name__ == '__main__':
    unittest.main()
