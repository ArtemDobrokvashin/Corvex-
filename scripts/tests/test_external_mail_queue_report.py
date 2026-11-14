import contextlib
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import external_mail_queue_report as report

GOOD = {'queued': 2, 'queued_older_than_hour': 1, 'queued_without_raw_ref': 0,
        'failed': 1, 'other_status': 0}


class QueueReportTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(GOOD, report.parse_report(json.dumps(GOOD).encode()))

    def test_invalid_never_becomes_success(self):
        bad = [b'', b'not-json', b'{}', b'[]', b'\xff',
               json.dumps({**GOOD, 'queued': True}).encode(),
               json.dumps({**GOOD, 'failed': -1}).encode(),
               json.dumps({**GOOD, 'queued_older_than_hour': 3}).encode(),
               json.dumps({**GOOD, 'extra': 1}).encode()]
        for raw in bad:
            with self.subTest(raw=raw), self.assertRaises(report.ReportError):
                report.parse_report(raw)

    def test_read_only_and_no_identifying_data(self):
        self.assertIn('BEGIN READ ONLY', report.SQL)
        self.assertIn('ROLLBACK', report.SQL)
        for private_field in ('email_address', 'remote_folder', 'remote_uid', 'message_id_header', 'sha256'):
            self.assertNotIn(private_field, report.SQL)
        calls = [b'postgres\n', json.dumps(GOOD).encode()]
        with patch.object(report, 'execute', side_effect=calls) as run:
            self.assertEqual(GOOD, report.collect('corvex'))
            self.assertEqual(report.SQL.encode(), run.call_args.kwargs['payload'])
            self.assertIn('ON_ERROR_STOP=1', run.call_args.args[0])

    def test_fail_closed_on_command_errors_and_bad_user(self):
        with patch.object(report.subprocess, 'run', side_effect=subprocess.TimeoutExpired('docker', 30)):
            with self.assertRaises(report.ReportError):
                report.execute(['docker'])
        with patch.object(report, 'execute', return_value=b'-bad\n'):
            with self.assertRaises(report.ReportError):
                report.collect(None)
        with patch.object(report, 'collect', side_effect=report.ReportError('не удалось')):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, report.main([]))

    def test_no_approval_from_zero_counts(self):
        zero = {key: 0 for key in report.KEYS}
        out = io.StringIO()
        with patch.object(report, 'collect', return_value=zero), contextlib.redirect_stdout(out):
            self.assertEqual(0, report.main([]))
        self.assertIn('не доказывает', out.getvalue())
        self.assertNotIn('PASS', out.getvalue())


if __name__ == '__main__':
    unittest.main()
