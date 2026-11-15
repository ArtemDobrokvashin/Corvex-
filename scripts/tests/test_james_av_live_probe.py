"""Structural checks; neither actual James nor ClamAV is exercised here."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class JamesAvLiveProbeTests(unittest.TestCase):
    def test_probe_scopes_findings(self):
        ps = (ROOT / 'scripts/test-james-av-live.ps1').read_bytes().decode('ascii')
        java = (ROOT / 'scripts/src/JamesAvLiveProbe.java').read_bytes().decode('ascii')
        for marker in ('Mounted artifact missing or changed.', 'james-cli ListDomains',
                       'Mailet ru.corvex.james.av.CorvexClamAVGate instantiated.',
                       'Never print raw logs', 'INCONCLUSIVE:', 'JamesAvLiveProbe',
                       'No message, account, queue, network or configuration was changed.'):
            self.assertIn(marker, ps)
        for marker in ('EICAR-STANDARD-ANTIVIRUS-TEST-FILE', 'newGate("clamav", "3310")',
                       'newGate("127.0.0.1", "1")', 'separate gate instance',
                       'NOT TESTED: live spool delivery'):
            self.assertIn(marker, java)
        for forbidden in ('docker compose up', 'docker compose down', 'docker network connect',
                          'docker stop', 'docker restart', 'purge_queue'):
            self.assertNotIn(forbidden, ps)


if __name__ == '__main__':
    unittest.main()
