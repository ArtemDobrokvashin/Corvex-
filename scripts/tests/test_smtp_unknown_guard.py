"""Structural guard, not an SMTP response-loss or RabbitMQ integration test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'services/mail-smtp-service/src/main/java/ru/corvex/mailsmtp/outbound'


class SmtpUnknownGuard(unittest.TestCase):
    def test_both_clients_mark_send_before_call_and_complete_after_return(self):
        for name in ('SmtpRelayClient.java', 'ExternalSmtpClient.java'):
            with self.subTest(name=name):
                source = (ROOT / name).read_text(encoding='utf-8')
                start = source.index('sendStarted = true;')
                send = source.index('transport.sendMessage(message, addresses);')
                complete = source.index('sendCompleted = true;')
                self.assertLess(start, send)
                self.assertLess(send, complete)
                self.assertIn('sendStarted ?', source)
                self.assertIn('sendOutcome(sendCompleted)', source)

    def test_unknown_stops_both_workers_before_result_processing(self):
        for name in ('OutboundWorker.java', 'ExternalOutboundWorker.java'):
            with self.subTest(name=name):
                source = (ROOT / name).read_text(encoding='utf-8')
                self.assertLess(source.index('result.status() == SmtpRelayClient.Status.UNKNOWN'),
                                source.index('switch (result.status())'))
                self.assertIn('manual reconciliation required', source)


if __name__ == '__main__':
    unittest.main()
