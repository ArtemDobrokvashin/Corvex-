from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKUP = (ROOT / "scripts/backup.ps1").read_text(encoding="utf-8")
RESTORE = (ROOT / "scripts/restore.ps1").read_text(encoding="utf-8")
VERIFY = (ROOT / "scripts/restore_verify.ps1").read_text(encoding="utf-8")


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

    def test_alert_relay_cache_is_backed_up_and_legacy_backups_remain_restorable(self):
        archive = "alert-relay-data.tar.gz"
        self.assertIn('${ProjectName}_alert-relay-data', BACKUP)
        self.assertIn(archive, BACKUP)
        for script in (RESTORE, VERIFY):
            self.assertIn(f'if (Test-Path (Join-Path $BackupDir "{archive}"))', script)
            self.assertIn('${ProjectName}_alert-relay-data', script)
        self.assertIn("admin_db + .env key can distribute the configuration again", RESTORE)
        self.assertIn("$($Volumes.Count) file volumes", VERIFY)

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
        self.assertIn("--format '{{json .Config.Labels}}'", RESTORE)
        self.assertNotIn("index .Config.Labels", RESTORE)
        self.assertIn("$Labels.'com.docker.compose.project'", RESTORE)
        self.assertLess(
            RESTORE.index("Compose project mismatch"),
            RESTORE.index('Write-Host "Restoring volume'),
        )
        self.assertLess(
            RESTORE.index("Compose project mismatch"),
            RESTORE.index('"pg_restore"'),
        )
        self.assertNotIn('down -v', RESTORE)

    def test_restore_verify_forbids_source_and_never_starts_or_stops_application(self):
        self.assertIn("Refusing to verify the source corvex project", VERIFY)
        self.assertIn("Application services were not started", VERIFY)
        self.assertNotIn('"down"', VERIFY)
        self.assertNotIn('"up"', VERIFY)
        self.assertNotIn("prune", VERIFY)

    def test_restore_verify_uses_readonly_file_diffs_and_project_guard(self):
        self.assertIn(":/restored:ro", VERIFY)
        self.assertIn(":/backup:ro", VERIFY)
        self.assertIn("diff -qr /expected /restored", VERIFY)
        self.assertIn("--format '{{json .Config.Labels}}'", VERIFY)
        self.assertIn("Compose project mismatch", VERIFY)

    def test_reference_db_is_dropped_only_after_canonical_diff_passes(self):
        self.assertIn("createdb -h postgres", VERIFY)
        self.assertIn("pg_restore -h postgres", VERIFY)
        self.assertIn("pg_dump -h postgres", VERIFY)
        self.assertIn("/tmp/actual.sql $Db", VERIFY)
        self.assertIn("/tmp/expected.sql $ReferenceDb", VERIFY)
        self.assertLess(VERIFY.index("diff -q /tmp/expected.norm"), VERIFY.index("dropdb"))
        self.assertIn("${Db}_logical_match", VERIFY)


if __name__ == "__main__":
    unittest.main()
