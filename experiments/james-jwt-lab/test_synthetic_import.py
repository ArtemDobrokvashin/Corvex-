import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'james-migration'))
probe = Mock()
spec = importlib.util.spec_from_file_location('synthetic_import', Path(__file__).with_name('synthetic_import.py'))
with patch.dict(sys.modules, {'probe': probe}):
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
from import_journal import Journal, JournalError

class SyntheticImportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.j = Journal(Path(self.tmp.name)/'journal.db')
        probe.reset_mock()
        probe.ok.side_effect = lambda result, _: result[1]
        probe.body.return_value = module.RAW
        self.client = Mock()
        self.client.uid.return_value = ('OK', [b'2 (FLAGS (\\Seen \\Flagged))'])
        self.client.select.return_value = ('OK', [b'1'])
        self.client.response.side_effect = [('UIDVALIDITY', [b'99']), ('APPENDUID', [None]), ('APPENDUID', [b'99 2']), ('UIDVALIDITY', [b'99'])]
        self.client.append.return_value = ('OK', [b''])

    def tearDown(self):
        self.j.close()
        self.tmp.cleanup()

    def test_import_verify_and_prevent_second_append(self):
        uid = module.attempt(self.client, self.j, module.SOURCE_ID, module.RAW)
        self.assertEqual(uid, '99:2')
        self.assertEqual(self.j.state(module.SOURCE_ID), ('verified', '99:2'))
        self.client.response.side_effect = [('UIDVALIDITY', [b'99']), ('APPENDUID', [None])]
        with self.assertRaises(JournalError): module.attempt(self.client, self.j, module.SOURCE_ID, module.RAW)
        self.assertEqual(self.client.append.call_count, 1)

    def test_timeout_after_append_blocks_retry(self):
        self.client.append.side_effect = TimeoutError('unknown')
        with self.assertRaises(TimeoutError): module.attempt(self.client, self.j, module.SOURCE_ID, module.RAW)
        self.assertEqual(self.j.state(module.SOURCE_ID), ('uncertain', None))
        self.client.response.side_effect = [('UIDVALIDITY', [b'99']), ('APPENDUID', [None])]
        with self.assertRaises(JournalError): module.attempt(self.client, self.j, module.SOURCE_ID, module.RAW)
        self.assertEqual(self.client.append.call_count, 1)

    def test_wrong_bytes_never_mark_verified(self):
        probe.body.return_value = b'changed'
        with self.assertRaises(JournalError): module.attempt(self.client, self.j, module.SOURCE_ID, module.RAW)
        self.assertEqual(self.j.state(module.SOURCE_ID), ('imported', '99:2'))

    def test_missing_appenduid_is_uncertain(self):
        self.client.response.side_effect = [('UIDVALIDITY', [b'99']), ('APPENDUID', [None]), ('APPENDUID', [None])]
        with self.assertRaises(RuntimeError): module.attempt(self.client, self.j, module.SOURCE_ID, module.RAW)
        self.assertEqual(self.j.state(module.SOURCE_ID), ('uncertain', None))

    def test_changed_uidvalidity_is_uncertain(self):
        self.client.response.side_effect = [('UIDVALIDITY', [b'99']), ('APPENDUID', [None]), ('APPENDUID', [b'99 2']), ('UIDVALIDITY', [b'100'])]
        with self.assertRaises(RuntimeError): module.attempt(self.client, self.j, module.SOURCE_ID, module.RAW)
        self.assertEqual(self.j.state(module.SOURCE_ID), ('uncertain', None))

    def test_requires_explicit_confirmation(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(SystemExit): module.main()
        probe.connect.assert_not_called()

if __name__ == '__main__': unittest.main()
