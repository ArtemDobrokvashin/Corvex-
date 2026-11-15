"""Static guards for the one-shot AV quarantine proof; nothing is executed."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/test-james-av-quarantine.ps1'
PROBE = ROOT / 'scripts/JamesAvQuarantineProbe.java'
CANDIDATE = ROOT / 'infra/james/candidates/smtp-quarantine-proof.xml'


class QuarantineProofTest(unittest.TestCase):
    def setUp(self):
        self.raw = SCRIPT.read_bytes()
        self.text = self.raw.decode('ascii')
        self.probe = PROBE.read_text(encoding='ascii')
        self.smtp = CANDIDATE.read_text(encoding='ascii')

    def test_sources_are_ascii(self):
        for blob in (self.raw, PROBE.read_bytes(), CANDIDATE.read_bytes()):
            self.assertTrue(all(byte < 128 for byte in blob))

    def test_candidate_smtp_is_loopback_only(self):
        root = ET.fromstring(self.smtp)
        server = root.find('smtpserver')
        self.assertEqual(server.findtext('bind'), '127.0.0.1:2525')
        self.assertEqual(server.findtext('authorizedAddresses'), '127.0.0.1/32')
        self.assertIsNotNone(server.find('auth'))
        self.assertNotIn('ports', self.text.lower())

    def test_only_the_synthetic_account_is_allowed(self):
        self.assertIn('^cvx-[0-9a-f]{32}@localhost$', self.text)
        self.assertIn('only the synthetic cvx-', self.text)
        self.assertIn('cvx-[0-9a-f]{32}@localhost', self.probe)

    def test_deep_archive_is_twenty_five_layers(self):
        self.assertIn('DEPTH = 25', self.probe)
        self.assertIn('for (int layer = 0; layer < DEPTH; layer++)', self.probe)
        self.assertIn('new ZipEntry("layer-" + layer', self.probe)

    def test_single_send_is_marked_before_it_happens(self):
        marker = self.text.index("New-Item -ItemType Directory -Path $Marker")
        send = self.text.index('JamesAvQuarantineProbe send')
        self.assertLess(marker, send)
        self.assertIn('do not repeat automatically', self.text)
        self.assertIn('no automatic retry', self.text)

    def test_candidate_listener_is_staged_and_verified(self):
        self.assertIn("$candidateSource = Join-Path $PSScriptRoot", self.text)
        self.assertIn("Copy-Item -LiteralPath $candidateSource -Destination $candidateFile", self.text)
        self.assertIn('$candidateFile.Replace([char]92, \'/\')', self.text)
        self.assertLess(self.text.index('candidate SMTP config is missing'),
                        self.text.index('$Changed = $true'))
        self.assertIn("$active -notmatch 'synthetic-quarantine-smtp'", self.text)
        self.assertLess(self.text.index('force-recreate james'),
                        self.text.index('James is not running the candidate loopback listener'))

    def test_compose_directory_is_explicit(self):
        self.assertIn('[string]$ComposeDir', self.text)
        self.assertIn("(Resolve-Path -LiteralPath $ComposeDir).Path", self.text)
        self.assertNotIn("$Repo = (Resolve-Path (Join-Path $PSScriptRoot", self.text)
        self.assertIn("'docker-compose.yml', '.env'", self.text)

    def test_account_comes_from_verified_record_or_explicit_override(self):
        self.assertIn('james-provision-live-probe', self.text)
        self.assertIn('acknowledged-and-jmap-verified', self.text)
        self.assertIn('pass -Login', self.text)
        self.assertIn('JamesAvQuarantineProbe check', self.text)
        check = self.text.index('JamesAvQuarantineProbe check')
        candidate = self.text.index("$candidateFile = Join-Path $Temp")
        self.assertLess(check, candidate)

    def test_absence_and_repository_delta_are_both_required(self):
        self.assertIn('JamesAvQuarantineProbe absent', self.text)
        self.assertIn('mail_repository_content', self.text)
        self.assertIn('exactly one quarantined mail was expected', self.text)
        self.assertIn('$quarantineDelta -ne 1', self.text)

    def test_disabled_listener_is_restored(self):
        self.assertIn("'./infra/james/protocols/smtpserver.xml'", self.text)
        restore = self.text.index('Restoring disabled James SMTP')
        self.assertLess(restore, self.text.index('if (-not $RestoreOk) { throw'))
        self.assertIn('restoration incomplete; do not retry', self.text)

    def test_no_destructive_or_queue_actions(self):
        for forbidden in ('down -v', 'docker rm', 'network rm', 'Remove-Item -LiteralPath $Repo',
                          'mailqueue', 'Reprocess', 'down --volumes'):
            self.assertNotIn(forbidden, self.text)
        self.assertFalse(re.search(r'-Login\s+\S*@(?!localhost)', self.text))


if __name__ == '__main__':
    unittest.main()
