"""Static guard only; does not simulate a running Spring listener or RabbitMQ."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / 'services/mail-storage-service/src/main/java/ru/corvex/mailstorage/pipeline'


class BounceGuardTest(unittest.TestCase):
    def test_no_automatic_listener_retry_or_spool_delete_on_publish_failure(self):
        listener = (ROOT / 'OutboundBounceListener.java').read_text(encoding='utf-8')
        config = (ROOT / 'StoragePipelineAmqpConfig.java').read_text(encoding='utf-8')
        self.assertIn('containerFactory = "bounceListenerFactory"', listener)
        publish = listener.split('events.publishConfirmed(', 1)[1].split('private static String header', 1)[0]
        self.assertNotIn('spool.delete', publish)
        self.assertIn('throw e;', publish)
        factory = config.split('SimpleRabbitListenerContainerFactory bounceListenerFactory(', 1)[1].split('private Advice mailRetryAdvice', 1)[0]
        self.assertIn('setDefaultRequeueRejected(false)', factory)
        self.assertNotIn('setAdviceChain', factory)


if __name__ == '__main__':
    unittest.main()
