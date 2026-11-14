"""Structural guards only; not an SMTP, security, or delivery test."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class JamesNativeCandidateTest(unittest.TestCase):
    def test_candidate_is_only_loopback_and_not_wired_into_compose(self):
        candidate = ROOT / 'infra/james/candidates/smtp-loopback.xml'
        config = ET.parse(candidate).getroot()
        servers = config.findall('smtpserver')
        self.assertEqual(len(servers), 1)
        server = servers[0]
        self.assertEqual(server.findtext('bind'), '127.0.0.1:2525')
        self.assertEqual(server.findtext('authorizedAddresses'), '127.0.0.1/32')
        self.assertEqual(server.findtext('auth/announce'), 'never')
        self.assertEqual(server.findtext('auth/plainAuthEnabled'), 'false')
        self.assertEqual(server.findtext('maxmessagesize'), '25600')  # James 3.9.1: KiB
        compose = (ROOT / 'docker-compose.yml').read_text(encoding='utf8')
        self.assertNotIn('infra/james/candidates/smtp-loopback.xml', compose)
        active = ET.parse(ROOT / 'infra/james/protocols/smtpserver.xml').getroot()
        self.assertEqual(len(active), 0)

    def test_smtp_probe_targets_only_synthetic_loopback(self):
        source = (ROOT / 'scripts/JamesNativeSmtpProbe.java').read_text(encoding='utf8')
        self.assertIn('transport.connect("127.0.0.1", 2525, null, null)', source)
        self.assertIn('cvx-[0-9a-f]{32}@localhost', source)
        self.assertIn('!args[2].matches("[0-9a-f]{32}")', source)
        self.assertEqual(source.count('transport.sendMessage('), 1)
        self.assertNotIn('System.err.println(error', source)
        self.assertNotIn('printStackTrace', source)


    def test_laptop_orchestrator_preserves_one_attempt_and_disabled_baseline(self):
        text = (ROOT / 'scripts/test-james-native-smtp.ps1').read_text(encoding='utf8')
        self.assertIn('if (Test-Path -LiteralPath $Marker)', text)
        self.assertIn('[IO.FileMode]::CreateNew', text)
        self.assertLess(text.index("Write-NewAscii (Join-Path $Marker 'intent.txt')"),
                        text.index('JamesNativeSmtpProbe send $Login $ProbeId'))
        self.assertEqual(text.count('JamesNativeSmtpProbe send $Login $ProbeId'), 1)
        self.assertIn("$env:CORVEX_JAMES_SMTP_CONFIG_FILE = './infra/james/protocols/smtpserver.xml'", text)
        self.assertIn('acknowledged-and-jmap-verified', text)
        self.assertIn('if ($Changed)', text)
        self.assertNotIn('down -v', text.lower())
        self.assertNotIn('prune', text.lower())
        self.assertNotIn('prepare-james-laptop.ps1', text.lower())


if __name__ == '__main__':
    unittest.main()
