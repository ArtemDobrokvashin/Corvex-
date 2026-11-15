"""Static guard; this does not execute PowerShell, Docker, RabbitMQ or deployment."""
from pathlib import Path
import unittest


class AvRolloutInventoryTest(unittest.TestCase):
    def test_ascii_read_only_commands_and_no_secrets_or_queue_bodies(self):
        text = (Path(__file__).resolve().parents[1] / 'inspect-av-rollout.ps1').read_bytes().decode('ascii')
        for expected in ('git rev-parse', 'git hash-object -- $file', 'docker compose ps --status running -q',
                         'docker inspect --format', 'list_queues name messages_ready messages_unacknowledged consumers type',
                         'not a consistent snapshot', 'not deployed-image verification'):
            self.assertIn(expected, text)
        for forbidden in ('docker compose up', 'docker compose down', 'docker compose stop', 'docker compose restart',
                          'docker volume', 'purge_queue', 'Set-Content', 'Remove-Item', '.Config.Env', 'get_messages'):
            self.assertNotIn(forbidden, text)
        self.assertIn('mail.av.spam-checked.dlq', text)
        self.assertIn('mail.storage.av-checked.dlq', text)


if __name__ == '__main__':
    unittest.main()
