"""Static guard for the rollback drill; no PowerShell, Docker or mail is executed."""
from pathlib import Path
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class RollbackDrillTest(unittest.TestCase):
    def setUp(self):
        self.raw = (SCRIPTS / 'drill-james-av-rollback.ps1').read_bytes()
        self.text = self.raw.decode('ascii')

    def test_script_is_ascii_and_confirmed(self):
        self.assertIn("-not $ConfirmLaptop", self.text)

    def test_journal_is_durable_and_never_overwritten(self):
        self.assertIn('[IO.FileMode]::CreateNew', self.text)
        self.assertIn('$stream.Flush($true)', self.text)
        self.assertNotIn('WriteAllText($journal', self.text)

    def test_verification_precedes_reenable_and_mounts_are_checked(self):
        self.assertLess(self.text.index('ROLLBACK VERIFIED'), self.text.index('PREPARE START'))
        self.assertLess(self.text.index('PREPARE VERIFIED'), self.text.index('APPLY START'))
        self.assertIn('Assert-Volumes', self.text)
        self.assertIn('Restored mailet configuration differs from the original.', self.text)
        self.assertIn('Assert-NoGateJars $container', self.text)
        self.assertIn('Extension jar present after rollback: ', self.text)

    def test_children_run_in_isolated_processes_and_are_not_retried(self):
        self.assertEqual(3, self.text.count('Invoke-Child '))
        self.assertEqual(1, self.text.count('Start-Process'))  # one isolated child runner
        self.assertIn("'-NoProfile'", self.text)
        self.assertIn('Do not retry blindly', self.text)

    def test_no_destructive_or_network_commands(self):
        for forbidden in ('compose down', 'down -v', 'docker rm', 'docker network rm', 'system prune',
                          'Remove-Item', 'docker volume', 'git reset', 'git clean', 'mailqueue', 'Reprocess'):
            self.assertNotIn(forbidden, self.text)

    def test_child_output_encoding_and_quoting_are_handled(self):
        self.assertIn('UTF8.GetString($bytes)', self.text)
        self.assertIn('Unicode.GetString($bytes', self.text)
        self.assertIn("'-NoProfile'", self.text)
        self.assertIn('if ($_ -match ', self.text)

    def test_journal_opens_before_preflight_checks(self):
        self.assertLess(self.text.index('Journal opened; manifest validated.'),
                        self.text.index('Gate mailet configuration is not the deployed one.'))
        self.assertLess(self.text.index('$stream = [IO.File]::Open'),
                        self.text.index('Get-RunningJames $m.composeDir'))

    def test_preflight_diagnostic_is_read_only(self):
        text = (SCRIPTS / 'inspect-james-av-preflight.ps1').read_bytes().decode('ascii')
        for forbidden in ('compose up', 'compose down', 'docker rm', 'network connect', 'network create',
                          'Remove-Item', 'WriteAllBytes', 'WriteAllText', 'Set-Content'):
            self.assertNotIn(forbidden, text)
        self.assertIn('RESULT failures=', text)
        self.assertIn('composeDir differs', text)

    def test_native_docker_stderr_cannot_abort_the_drill(self):
        # Windows PowerShell 5.1 turns native stderr into a terminating error;
        # james-cli writes SLF4J warnings there, so all calls use one helper.
        self.assertEqual(1, self.text.count('& docker '))
        helper = self.text.index('function Invoke-Docker')
        call = self.text.index('& docker ')
        self.assertGreater(call, helper)
        self.assertIn("$ErrorActionPreference = 'Continue'", self.text[helper:call + 200])
        self.assertIn("Invoke-Docker @('exec', $container, 'james-cli', 'ListDomains')", self.text)

    def test_rollback_child_also_guards_native_stderr(self):
        text = (SCRIPTS / 'rollback-james-av.ps1').read_text(encoding='utf-8')
        self.assertIn('function Invoke-Docker', text)
        raw = [line for line in text.splitlines() if '& docker ' in line and 'Invoke-Docker' not in line]
        # One helper implementation plus the explicit recreate wrapped in Continue.
        self.assertEqual(2, len(raw), raw)
        self.assertIn('& docker @Arguments', raw[0])
        self.assertIn("$ErrorActionPreference = 'Continue'", text)
        self.assertTrue(any('force-recreate' in line for line in raw), raw)

    def test_resume_mode_verifies_instead_of_repeating_rollback(self):
        self.assertIn('[switch]$ResumeAfterRollback', self.text)
        self.assertIn('if (-not $ResumeAfterRollback)', self.text)
        self.assertIn('Resume requested, but Compose is not the recorded pre-gate file.', self.text)
        self.assertIn('rollback not repeated', self.text)

    def test_zero_byte_mount_stubs_are_not_loadable_jars(self):
        self.assertIn("'-printf', '%s %f", self.text)
        self.assertIn('$entry.Size -eq 0', self.text)
        enable = (SCRIPTS / 'enable-james-av.ps1').read_text(encoding='utf-8')
        self.assertIn('Zero-byte mount-point stubs from earlier runs', enable)
        self.assertIn('Existing extension jars found or not inspectable', enable)

    def test_reads_status_without_printing_full_inspect(self):
        self.assertIn('$result.Output[0] | ConvertFrom-Json', self.text)
        self.assertNotIn('Write-Host $raw', self.text)
        self.assertNotIn('ConvertTo-Json -Depth 0', self.text)


if __name__ == '__main__':
    unittest.main()
