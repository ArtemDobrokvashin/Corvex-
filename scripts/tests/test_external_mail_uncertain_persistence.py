"""Static ordering guard, not a PostgreSQL, RabbitMQ or crash-recovery test."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[2] / 'services/mail-storage-service/src/main/java/ru/corvex/mailstorage/external/ExternalMailSyncService.java').read_text(encoding='utf-8')


class UncertainPersistenceGuard(unittest.TestCase):
    def test_spool_cleanup_is_only_before_persistence_may_have_started(self):
        section = SOURCE.split('boolean persistenceMayHaveStarted = false;', 1)[1].split('private ExternalMailImportedMessage baseImported', 1)[0]
        intent = section.index('persistenceMayHaveStarted = true;')
        write = section.index('record = imported.saveAndFlush(record);')
        publish = section.index('publicationMayHaveStarted = true;')
        self.assertLess(intent, write)
        self.assertLess(write, publish)
        cleanup = section.split('catch (Exception e)', 1)[1]
        self.assertIn('if (!persistenceMayHaveStarted)', cleanup)
        self.assertIn('spool.delete(stored.rawRef())', cleanup.split('} else {', 1)[0])
        self.assertNotIn('spool.delete', cleanup.split('} else {', 1)[1])


if __name__ == '__main__':
    unittest.main()
