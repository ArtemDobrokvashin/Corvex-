import contextlib
import importlib.util
import io
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

probe = Mock()
spec = importlib.util.spec_from_file_location('imap_import_probe', Path(__file__).with_name('imap_import_probe.py'))
with patch.dict(sys.modules, {'probe': probe}):
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

class ImapImportTests(unittest.TestCase):
    def setUp(self):
        probe.reset_mock()
        probe.ok.side_effect = lambda result, operation: result[1]
        probe.body.return_value = module.RAW
        self.client = Mock()
        self.client.uid.side_effect = [('OK', [b'1 2']), ('OK', [b'2 (FLAGS (\\Seen \\Flagged))'])]

    def test_identifies_new_uid_and_matches_exact_bytes(self):
        with contextlib.redirect_stdout(io.StringIO()): module.verify(self.client, {b'1'})
        probe.body.assert_called_once_with(self.client, b'2')

    def test_ambiguous_append_rejected(self):
        self.client.uid.side_effect = [('OK', [b'1 2 3'])]
        with self.assertRaises(RuntimeError): module.verify(self.client, {b'1'})

    def test_changed_bytes_rejected(self):
        probe.body.return_value = b'changed'
        with self.assertRaisesRegex(RuntimeError, 'bytes differ'): module.verify(self.client, {b'1'})

    def test_requires_confirmation(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(SystemExit): module.main()
        probe.connect.assert_not_called()

if __name__ == '__main__': unittest.main()
