"""Static checks only; Windows PowerShell and RabbitMQ remain separate tests."""
from pathlib import Path
import unittest


class QueueInventoryTest(unittest.TestCase):
    def test_ascii_read_only_and_sanitized_failures(self):
        text = (Path(__file__).resolve().parents[1] / 'inspect-av-queues.ps1').read_bytes().decode('ascii')
        self.assertIn('list_queues name messages_ready messages_unacknowledged consumers type --formatter json', text)
        self.assertIn('$rows = @($parsed)', text)
        self.assertIn('$rows[0] -is [array]', text)
        self.assertIn('$stage = \'rabbitmq-command\'', text)
        self.assertIn('$stage = \'json-decoding\'', text)
        self.assertIn('$stage = \'queue-validation\'', text)
        self.assertIn('2>$null', text)
        self.assertNotIn('$_.Exception.Message', text)
        for forbidden in ('docker compose up', 'docker compose stop', 'docker compose down',
                          'purge_queue', 'get_messages', 'Remove-Item', '.Config.Env'):
            self.assertNotIn(forbidden, text)


if __name__ == '__main__':
    unittest.main()
