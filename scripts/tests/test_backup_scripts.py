from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKUP = (ROOT / "scripts/backup.ps1").read_text(encoding="utf-8")
RESTORE = (ROOT / "scripts/restore.ps1").read_text(encoding="utf-8")


class BackupScriptsTest(unittest.TestCase):
    def test_pg_dump_custom_format_never_uses_powershell_redirection(self):
        self.assertIn('"pg_dump", "-U", $PgUser, "-Fc", "-f", $ContainerDump, $Db', BACKUP)
        self.assertIn('"cp", "postgres:$ContainerDump", $HostDump', BACKUP)
        self.assertNotRegex(BACKUP, r"pg_dump[^\n]*>")

    def test_backup_uses_effective_user_project_volumes_and_readonly_sources(self):
        self.assertIn("printenv POSTGRES_USER", BACKUP)
        self.assertIn('"compose", "-p", $ProjectName', BACKUP)
        self.assertIn('${ProjectName}_mail-data', BACKUP)
        self.assertIn(':/data:ro', BACKUP)
        self.assertIn('Set-Location (Join-Path $PSScriptRoot "..")', BACKUP)

    def test_backup_manifest_covers_hashes_and_restore_verifies_before_mutation(self):
        self.assertIn("Get-FileHash -Algorithm SHA256", BACKUP)
        self.assertIn("manifest.json", BACKUP)
        self.assertIn("Backup SHA-256 mismatch", RESTORE)
        self.assertLess(
            RESTORE.index("Backup manifest and SHA-256 hashes verified"),
            RESTORE.index("Starting isolated PostgreSQL"),
        )
        self.assertLess(
            RESTORE.index("Backup manifest and SHA-256 hashes verified"),
            RESTORE.index("Stopping only Compose project"),
        )

    def test_isolated_dataonly_is_mandatory_for_non_source_project(self):
        self.assertIn("DataOnly requires an isolated -ProjectName", RESTORE)
        self.assertIn("An isolated project supports only -DataOnly", RESTORE)
        self.assertIn("Application services were not started", RESTORE)
        self.assertNotIn('"corvex_mail-data"', RESTORE)
        self.assertNotIn('"corvex_attachment-data"', RESTORE)
        self.assertNotIn('"corvex_chat-file-data"', RESTORE)

    def test_project_label_guard_precedes_file_restore_and_pg_restore(self):
        self.assertIn('com.docker.compose.project', RESTORE)
        self.assertLess(
            RESTORE.index("Compose project mismatch"),
            RESTORE.index('Write-Host "Restoring volume'),
        )
        self.assertLess(
            RESTORE.index("Compose project mismatch"),
            RESTORE.index('"pg_restore"'),
        )
        self.assertNotIn('down -v', RESTORE)


if __name__ == "__main__":
    unittest.main()
