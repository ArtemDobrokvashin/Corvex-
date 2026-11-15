"""The AV readiness inventory must stay read-only and body-free."""
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'inspect-av-live-readiness.ps1'


class ReadinessInventoryTest(unittest.TestCase):
    def setUp(self):
        self.raw = SCRIPT.read_bytes()
        self.text = self.raw.decode('ascii')

    def test_ascii_and_confirmation(self):
        self.assertTrue(all(byte < 128 for byte in self.raw))
        self.assertIn('-not $ConfirmLaptop', self.text)

    def test_read_only(self):
        for forbidden in ('compose up', 'compose down', 'compose restart', 'docker stop', 'docker start',
                          'docker rm', 'docker exec -u 0', 'mailqueue', 'psql', 'Reprocess'):
            self.assertNotIn(forbidden, self.text)
        self.assertIn('Read-only inventory finished', self.text)

    def test_reports_states_switches_and_queues(self):
        for service in ('mail-smtp-service', 'mail-antivirus-service', 'mail-storage-service', 'rabbitmq', 'clamav'):
            self.assertIn(service, self.text)
        self.assertIn('CORVEX_SMTP_ENABLED', self.text)
        self.assertIn('list_queues name messages_ready messages_unacknowledged', self.text)

    def test_no_message_bodies(self):
        for forbidden in ('docker cp', 'cat ', 'Select-String', 'Get-Content', 'find "', 'Attachments'):
            self.assertNotIn(forbidden, self.text)


if __name__ == '__main__':
    unittest.main()
