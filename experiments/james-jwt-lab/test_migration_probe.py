import importlib.util
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

probe = Mock()
spec = importlib.util.spec_from_file_location('migration_probe', Path(__file__).with_name('migration_probe.py'))
with patch.dict(sys.modules, {'probe': probe}):
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

class MigrationProbeTests(unittest.TestCase):
    def setUp(self):
        probe.reset_mock()
        probe.ok.side_effect = lambda result, operation: result[1]
        probe.uid_search.return_value = [b'1', b'2']
        probe.body.return_value = b'wire bytes'
        self.client = Mock()
        self.client.uid.side_effect = [('OK', [b'1 (FLAGS (\\Flagged))']),
                                       ('OK', [b'2 (FLAGS (\\Seen \\Flagged))'])]

    def test_two_copies_with_distinct_flags(self):
        module.check(self.client, 'fixed', b'wire bytes')

    def test_duplicate_uid_rejected(self):
        probe.uid_search.return_value = [b'1', b'1']
        with self.assertRaises(RuntimeError): module.check(self.client, 'fixed', b'wire bytes')

    def test_changed_bytes_rejected(self):
        probe.body.return_value = b'changed'
        with self.assertRaises(RuntimeError): module.check(self.client, 'fixed', b'wire bytes')

    def test_unexpected_flag_rejected(self):
        self.client.uid.side_effect = [('OK', [b'1 (FLAGS (\\Flagged))']),
                                       ('OK', [b'2 (FLAGS (\\Flagged))'])]
        with self.assertRaises(RuntimeError): module.check(self.client, 'fixed', b'wire bytes')

    def test_requires_explicit_confirmation(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(SystemExit): module.main()
        probe.connect.assert_not_called()

if __name__ == '__main__': unittest.main()
