import contextlib
import io
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import james_clamav_config_report as report


class ClamavConfigReportTest(unittest.TestCase):
    def test_only_whitelisted_values_and_explicit_vs_default(self):
        config = b'''# AlertExceedsMax yes\nAlertExceedsMax no\nScanMail YES  # comment\nMaxScanSize 100M\nMaxFileSize 25MB\nPrivateSecret please-never-print\n'''
        result = report.parse_config(config)
        self.assertEqual('no', result['AlertExceedsMax'])
        self.assertEqual('YES', result['ScanMail'])
        self.assertEqual('100M', result['MaxScanSize'])
        self.assertEqual('25MB', result['MaxFileSize'])
        self.assertIsNone(result['MaxFiles'])
        self.assertNotIn('PrivateSecret', result)
        self.assertNotIn('please-never-print', str(result))

    def test_duplicate_invalid_and_oversized_are_errors(self):
        samples = (b'AlertExceedsMax yes\nAlertExceedsMax no', b'MaxFiles 12 extra',
                   b'MaxScanTime -1', b'ScanMail ${SECRET}', b'\xff', b'x' * (256 * 1024 + 1))
        for sample in samples:
            with self.subTest(sample=sample[:40]), self.assertRaises(report.ReportError):
                report.parse_config(sample)

    def test_only_read_only_compose_exec_is_issued(self):
        cp = subprocess.CompletedProcess([], 0, stdout=b'MaxFiles 12\n', stderr=b'')
        with patch.object(report.subprocess, 'run', return_value=cp) as run:
            self.assertEqual('12', report.collect(Path('/work'), 'project')['MaxFiles'])
            self.assertEqual(['docker', 'compose', '-p', 'project', 'exec', '-T',
                              'clamav', 'cat', '/etc/clamav/clamd.conf'], run.call_args.args[0])
            self.assertEqual(Path('/work'), run.call_args.kwargs['cwd'])

    def test_command_errors_never_include_native_stderr(self):
        cp = subprocess.CompletedProcess([], 1, stdout=b'', stderr=b'SECRET')
        with patch.object(report.subprocess, 'run', return_value=cp):
            with self.assertRaises(report.ReportError) as exc:
                report.collect(Path('.'), None)
        self.assertNotIn('SECRET', str(exc.exception))
        with patch.object(report.subprocess, 'run', side_effect=subprocess.TimeoutExpired('docker', 30)):
            with self.assertRaises(report.ReportError):
                report.collect(Path('.'), None)

    def test_zero_or_default_config_is_not_readiness(self):
        with patch.object(report, 'collect', return_value=report.parse_config(b'')), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, report.main([]))
        self.assertIn('не задано явно', out.getvalue())
        self.assertIn('не подтверждает', out.getvalue())
        self.assertNotIn('PASS', out.getvalue())


if __name__ == '__main__':
    unittest.main()
