import contextlib
import io
import os
from pathlib import Path
import runpy
import sys
import unittest
from unittest.mock import Mock, patch

SCRIPT = str(Path(__file__).with_name('seed.py'))


class SeedTest(unittest.TestCase):
    def run_seed(self, mode, confirm='synthetic-only'):
        probe = Mock()
        probe.ok.side_effect = lambda result, operation: result[1]
        client = probe.connect.return_value
        client.select.return_value = ('OK', [b'1'])
        client.append.return_value = ('OK', [b''])
        client.uid.side_effect = lambda *a: ('OK', [b'7'])
        with patch.dict(os.environ, {'JAMES_LAB_CONFIRM': confirm, 'JAMES_LAB_SEED_MODE': mode}, clear=True), \
                patch.dict(sys.modules, {'probe': probe}), contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(SCRIPT, run_name='__main__')
        return probe, client

    def test_append_uses_fixed_demo_account_and_one_message(self):
        probe, client = self.run_seed('append')
        self.assertEqual('user01@james.local', probe.connect.call_args.args[0])
        self.assertEqual(1, client.append.call_count)
        self.assertIn(b'Corvex James live synthetic message', client.append.call_args.args[3])
        client.logout.assert_called_once()

    def test_flag_touches_only_first_fixed_subject(self):
        _, client = self.run_seed('seen')
        self.assertEqual(2, client.uid.call_count)
        self.assertEqual(('STORE', b'7', '+FLAGS.SILENT', r'(\Seen)'), client.uid.call_args.args)
        client.logout.assert_called_once()

    def test_refuses_missing_confirmation_or_unknown_mode(self):
        with self.assertRaises(SystemExit): self.run_seed('append', '')
        with self.assertRaises(SystemExit): self.run_seed('delete')


if __name__ == '__main__':
    unittest.main()
