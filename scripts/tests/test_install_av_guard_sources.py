"""Static guards; not a real PowerShell installation or backup/restore test."""
from pathlib import Path
import unittest


class GuardSourceInstallTest(unittest.TestCase):
    def test_exact_base_candidate_only_and_owned_backup_before_writes(self):
        text = (Path(__file__).resolve().parents[1] / 'install-av-guard-sources.ps1').read_bytes().decode('ascii')
        for required in ("3e208a549fbfd7bb02f7d3ee8d5fc4489f3dd134", "$candidate = 'eda8c7d'",
                         '$wantedTree = Read-Tree $candidate', '$baseTree = Read-Tree $base',
                         'Source has independent edits; preserve and inspect',
                         'Extra source file; preserve and inspect',
                         'Reparse points are not supported', 'if (-not $Apply)',
                         'Source changed during preflight', 'source-backup-manifest.json',
                         'Some source files may have changed', 'No container, image tag, queue'):
            self.assertIn(required, text)
        self.assertLess(text.index("$stage = 'backup-and-stage'"), text.index("$stage = 'source-install'"))
        self.assertLess(text.index('source-backup-manifest.json'), text.index("$stage = 'source-install'"))
        for forbidden in ('docker compose up', 'docker compose down', 'docker compose stop', 'docker tag ',
                          'docker image rm', 'docker volume', 'Remove-Item', 'git reset', 'git clean'):
            self.assertNotIn(forbidden, text)


if __name__ == '__main__':
    unittest.main()
