"""Structural checks only, not a RabbitMQ, ClamAV, or Java runtime test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
AV = (ROOT / 'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/pipeline/AntivirusCheckListener.java').read_text()
STORAGE = (ROOT / 'services/mail-storage-service/src/main/java/ru/corvex/mailstorage/pipeline/AvCheckedListener.java').read_text()

class VerdictGuards(unittest.TestCase):
    def test_av_refuses_unknown_before_publish(self):
        self.assertLess(AV.index('scan == null'), AV.index('var checked ='))
        self.assertIn('scan.result() != ClamAvClient.Result.CLEAN', AV)
        self.assertIn('scan.result() != ClamAvClient.Result.INFECTED', AV)
        self.assertIn('throw new IOException("Antivirus verdict unavailable', AV)

    def test_storage_refuses_unknown_before_delivery_or_delete(self):
        check = STORAGE.index('!"CLEAN".equalsIgnoreCase(event.avStatus())')
        self.assertIn('!"INFECTED".equalsIgnoreCase(event.avStatus())', STORAGE)
        self.assertLess(check, STORAGE.index('delivery.deliver('))
        self.assertLess(check, STORAGE.index('spool.delete('))

if __name__ == '__main__':
    unittest.main()
