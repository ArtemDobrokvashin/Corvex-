"""Static guard for ClamAV limit-alert activation; no Docker or ClamAV is executed."""
from pathlib import Path
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class ClamavLimitAlertTest(unittest.TestCase):
    def setUp(self):
        self.enable_raw = (SCRIPTS / 'enable-clamav-limit-alert.ps1').read_bytes()
        self.enable = self.enable_raw.decode('ascii')
        self.rollback = (SCRIPTS / 'rollback-clamav-limit-alert.ps1').read_bytes().decode('ascii')

    def test_dry_run_is_default_and_confirmation_required(self):
        self.assertIn("[switch]$Apply", self.enable)
        self.assertIn("if (-not $Apply)", self.enable)
        self.assertIn("if (-not $ConfirmLaptop)", self.enable)
        self.assertIn("No files, containers or networks were changed.", self.enable)

    def test_only_clamav_is_recreated_and_networks_are_restored(self):
        self.assertIn("'--force-recreate', 'clamav'", self.enable)
        self.assertEqual(1, self.enable.count("'--force-recreate', 'clamav'"))
        self.assertIn("'network', 'connect', '--alias', 'clamav'", self.enable)
        self.assertIn('Connect-AvNetworks', self.enable)
        self.assertIn('corvex-james-av-', self.enable)

    def test_manual_av_network_drift_is_explained_not_fatal(self):
        self.assertNotIn("throw 'Compose differs from the running ClamAV.'", self.enable)
        self.assertIn('configuration hash differs only because the James AV network is declared in Compose', self.enable)
        self.assertIn("'config', '--images', 'clamav'", self.enable)
        self.assertIn('Compose image for clamav differs from the running container.', self.enable)

    def test_database_volume_and_networks_are_preserved(self):
        self.assertIn('ClamAV database volume changed after recreation.', self.enable)
        self.assertIn("$expectedNetworks = @('app') + $avNetworks", self.enable)
        self.assertIn('Missing ClamAV network after recreation: ', self.enable)

    def test_activation_verifies_both_config_and_compose_identity(self):
        self.assertIn("Assert-ConfigReport $root 'AlertExceedsMax: yes'", self.enable)
        self.assertIn('New ClamAV does not match the edited Compose.', self.enable)

    def test_manifest_is_written_before_the_change(self):
        write_manifest = self.enable.index("$state | ConvertTo-Json")
        recreate = self.enable.index("'--force-recreate'")
        self.assertLess(write_manifest, recreate)
        self.assertIn('composeBeforeSha256', self.enable)
        self.assertIn('previousContainerId', self.enable)

    def test_no_destructive_commands(self):
        for text in (self.enable, self.rollback):
            for forbidden in ('down -v', 'compose down', 'docker rm', 'docker network rm', 'system prune',
                              'docker volume', 'Remove-Item -Recurse', 'git reset', 'git clean'):
                self.assertNotIn(forbidden, text)

    def test_rollback_restores_bytes_and_detects_independent_edits(self):
        self.assertIn('[IO.File]::WriteAllBytes($composePath, [IO.File]::ReadAllBytes($m.composeBackup))', self.rollback)
        self.assertIn('Compose was changed independently', self.rollback)
        self.assertIn("AlertExceedsMax: not explicitly set", self.rollback)
        self.assertIn('if (-not $m.applied)', self.rollback)


if __name__ == '__main__':
    unittest.main()
