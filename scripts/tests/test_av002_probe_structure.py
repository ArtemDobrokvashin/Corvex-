"""Structural checks only; live Docker and PowerShell remain separate acceptance tests."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class Av002ProbeStructureTest(unittest.TestCase):
    def test_harness_uses_three_java_clients_and_requires_nine(self):
        source = (ROOT / 'scripts/src/Av002ClientCheck.java').read_text(encoding='utf-8')
        for package in ('ru.corvex.mailav', 'ru.corvex.mailstorage.av', 'ru.corvex.chatfile.av'):
            self.assertIn('new ' + package + '.ClamAvClient(', source)
        self.assertIn('if (passed != 9) System.exit(1)', source)
        self.assertIn('127.0.0.1', source)
        self.assertIn('simulated ERROR', source)

    def test_orchestrator_no_service_modification_and_owned_cleanup(self):
        source = (ROOT / 'scripts/av-002-java-clients-check.ps1').read_text(encoding='utf-8')
        self.assertNotIn('compose up', source)
        self.assertNotIn('compose down', source)
        self.assertIn('$RemoteOwned = $false', source)
        self.assertIn('docker exec $Container mkdir $Remote', source)
        self.assertIn('if ($RemoteOwned -and', source)
        self.assertIn('Av002ClientCheck clamav 3310', source)
        self.assertIn('$Remote/lib/*', source)


if __name__ == '__main__':
    unittest.main()
