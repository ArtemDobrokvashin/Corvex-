"""Static guard for the one-shot ClamAV limit run; no PowerShell or Docker is executed."""
from pathlib import Path
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class ClamavLimitRunWrapperTest(unittest.TestCase):
    def setUp(self):
        self.raw = (SCRIPTS / 'run-clamav-limit-and-probe.ps1').read_bytes()
        self.text = self.raw.decode('ascii')

    def test_requires_confirmation_and_ascii(self):
        self.assertIn('-not $ConfirmLaptop', self.text)
        self.assertTrue(all(byte < 128 for byte in self.raw))

    def test_single_durable_log_and_distinct_exit_codes(self):
        self.assertIn("$log = Join-Path $logDir 'run.log'", self.text)
        self.assertIn('[IO.File]::AppendAllText', self.text)
        for code in ('$code = 1', '$code = 2', '$code = 3', '$code = 0'):
            self.assertIn(code, self.text)

    def test_no_retry_and_no_destructive_commands(self):
        for forbidden in ('compose down', 'down -v', 'docker rm', 'docker network rm', 'Remove-Item',
                          'git reset', 'git clean', 'mailqueue', 'Reprocess'):
            self.assertNotIn(forbidden, self.text)
        self.assertIn('does not', self.text)

    def test_steps_run_in_order_and_stop_on_failure(self):
        self.assertLess(self.text.index("Invoke-Step 'dry'"), self.text.index("Invoke-Step 'apply'"))
        self.assertLess(self.text.index("Invoke-Step 'apply'"), self.text.index("Invoke-Step 'probe'"))
        self.assertIn('Dry-run failed; see the log above.', self.text)
        self.assertIn('Apply failed; see the log above.', self.text)


if __name__ == '__main__':
    unittest.main()
