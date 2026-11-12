from __future__ import annotations
import contextlib
import io
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mail_inventory as inv

ID = '415f54ee-c441-4a43-9c39-2b2713f7d783'
PATH = 'admin@company.local/Junk/new/example.eml'
ROOT = '/data/mail/'

class MailInventoryTests(unittest.TestCase):
    def test_match_and_ignore_spool_and_tmp(self):
        result = inv.compare([{'id': ID, 'path': PATH}], [ROOT + PATH, ROOT + '_spool/new/example.eml', ROOT + 'user/Junk/tmp/x.eml'])
        self.assertEqual((1, (), (), ()), (result.records, result.missing, result.duplicates, result.orphans))

    def test_missing_duplicate_and_orphan(self):
        result = inv.compare([{'id': ID, 'path': PATH}, {'id': ID, 'path': PATH}], [ROOT + 'user/Trash/new/copy.eml'])
        self.assertEqual(2, len(result.missing))
        self.assertEqual(((PATH, 2),), result.duplicates)
        self.assertEqual(('user/Trash/new/copy.eml',), result.orphans)

    def test_case_sensitive_and_unsafe_paths(self):
        self.assertEqual(1, len(inv.compare([{'id': ID, 'path': PATH}], [ROOT + PATH.upper()]).missing))
        for bad in ('../escape', '/absolute', 'user//new/x', 'user/../x', 'a\\b', ''):
            with self.subTest(bad=bad), self.assertRaises(inv.InventoryError):
                inv.compare([{'id': ID, 'path': bad}], [])

    def test_bad_outputs_never_report_success(self):
        for answers in ([b'corvex\n', b'/data/mail', b'not-json\n', b''], [b'corvex\n', b'/data/mail', b'', b'/data/mail/a'], [b'corvex\n', b'/other'], [b'\n']):
            with self.subTest(answers=answers), patch.object(inv, 'run', side_effect=answers):
                with self.assertRaises(inv.InventoryError):
                    inv.collect(None)

    def test_default_output_does_not_disclose_paths(self):
        result = inv.Inventory(1, ((ID, PATH),), (), ())
        out = io.StringIO()
        with patch.object(inv, 'collect', return_value=result), contextlib.redirect_stdout(out):
            self.assertEqual(1, inv.main([]))
        self.assertNotIn(PATH, out.getvalue())
        with patch.object(inv, 'collect', return_value=result), contextlib.redirect_stdout(out):
            self.assertEqual(1, inv.main(['--show-paths']))
        self.assertIn(PATH, out.getvalue())

    def test_failed_docker_and_null_delimiters(self):
        rows = ('{"id":"' + ID + '","path":"' + PATH + '"}\n').encode()
        with patch.object(inv, 'run', side_effect=[b'corvex\n', b'/data/mail', rows, (ROOT + PATH).encode() + b'\0']):
            self.assertEqual((), inv.collect('corvex').missing)
        with patch.object(inv.subprocess, 'run', side_effect=FileNotFoundError('docker')):
            with self.assertRaises(inv.InventoryError):
                inv.run(['docker'])

if __name__ == '__main__':
    unittest.main()
