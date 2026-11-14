"""Static guard only: SMTP and RabbitMQ failures require separate integration tests."""
from pathlib import Path
import unittest


class OutboundRetryGuard(unittest.TestCase):
    def test_no_interceptor_repeats_whole_smtp_listener(self):
        root = Path(__file__).resolve().parents[2] / 'services/mail-smtp-service/src/main/java/ru/corvex/mailsmtp/outbound'
        config = (root / 'OutboundAmqpConfig.java').read_text(encoding='utf-8')
        for name in ('OutboundWorker.java', 'ExternalOutboundWorker.java'):
            self.assertIn('containerFactory = "mailListenerFactory"', (root / name).read_text(encoding='utf-8'))
        factory = config.split('SimpleRabbitListenerContainerFactory mailListenerFactory(', 1)[1]
        self.assertIn('setDefaultRequeueRejected(false)', factory)
        self.assertNotIn('setAdviceChain', factory)
        self.assertNotIn('RetryInterceptorBuilder', config)


if __name__ == '__main__':
    unittest.main()
