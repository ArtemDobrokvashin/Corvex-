"""Bash-версии эксплуатационных скриптов для Ubuntu.

Проверяется не «файл существует», а те правила, которые появились после настоящих
ошибок в PowerShell-версиях — и которые bash-версия обязана унаследовать:

* дамп бинарный: `pg_dump -Fc -f` внутри контейнера и `docker compose cp`, а не
  перенаправление вывода (на Windows оно портило формат);
* тома монтируются только для чтения;
* манифест и SHA-256 проверяются до того, как тронута конфигурация или база;
* `--data-only` не может работать с рабочим проектом `corvex`;
* успех сверки засчитывается только по маркеру `<db>_logical_match`;
* пароль базы берётся у контейнера, а не из `.env`, и не попадает в аргументы.
"""

from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"

BACKUP = (SCRIPTS / "backup.sh").read_text(encoding="utf-8")
RESTORE = (SCRIPTS / "restore.sh").read_text(encoding="utf-8")
VERIFY = (SCRIPTS / "restore_verify.sh").read_text(encoding="utf-8")
ACCEPTANCE = (SCRIPTS / "acceptance.sh").read_text(encoding="utf-8")


class SyntaxTest(unittest.TestCase):
    def test_every_bash_script_parses(self):
        scripts = sorted(SCRIPTS.glob("*.sh"))
        self.assertGreaterEqual(len(scripts), 4)
        for script in scripts:
            with self.subTest(script=script.name):
                result = subprocess.run(
                    ["bash", "-n", str(script)], capture_output=True, text=True, check=False
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_strict_mode_is_enabled(self):
        # Без `-e` ошибка внутри цикла не останавливает скрипт, и «Backup OK»
        # печатается после провалившегося pg_dump.
        for script in (BACKUP, RESTORE, VERIFY):
            self.assertIn("set -Eeuo pipefail", script)


class BackupTest(unittest.TestCase):
    def test_dump_goes_through_a_file_not_through_redirection(self):
        self.assertIn("pg_dump -U \"$PG_USER\" -Fc -f \"$container_dump\" \"$db\"", BACKUP)
        self.assertIn('docker compose -p "$PROJECT_NAME" cp "postgres:$container_dump" "$host_dump"', BACKUP)
        self.assertNotRegex(BACKUP, r"pg_dump[^\n]*>")

    def test_volumes_are_mounted_read_only_and_archives_are_checked(self):
        self.assertIn(":/data:ro", BACKUP)
        self.assertIn('[[ -s "$DIR/$archive" ]] || die', BACKUP)

    def test_secrets_are_copied_and_manifest_is_written(self):
        self.assertIn('cp .env docker-compose.yml "$DIR/"', BACKUP)
        self.assertIn("infra/certs infra/secrets", BACKUP)
        self.assertIn("manifest.sha256", BACKUP)
        # Формат sha256sum, чтобы проверка не требовала Python.
        self.assertIn("sha256sum -c manifest.sha256 --quiet", BACKUP)

    def test_backup_refuses_a_bad_project_name(self):
        self.assertIn('[[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]]', BACKUP)


class RestoreTest(unittest.TestCase):
    def test_without_force_nothing_happens(self):
        self.assertIn('[[ "$FORCE" -eq 1 ]] || die "отказываюсь восстанавливать без --force"', RESTORE)

    def test_data_only_requires_an_isolated_project(self):
        self.assertIn('if [[ "$DATA_ONLY" -eq 1 && "$PROJECT_NAME" == "corvex" ]]', RESTORE)
        self.assertIn('if [[ "$DATA_ONLY" -eq 0 && "$PROJECT_NAME" != "corvex" ]]', RESTORE)

    def test_manifest_is_verified_before_configuration_is_touched(self):
        self.assertLess(
            RESTORE.index("sha256sum -c manifest.sha256"),
            RESTORE.index("Текущая конфигурация сохраняется"),
        )
        self.assertLess(
            RESTORE.index("sha256sum -c manifest.sha256"),
            RESTORE.index('up -d postgres'),
        )

    def test_project_label_is_proved_before_volumes_are_cleared(self):
        self.assertLess(
            RESTORE.index("com.docker.compose.project"),
            RESTORE.index("find /data -mindepth 1 -delete"),
        )

    def test_manifest_path_traversal_is_rejected(self):
        self.assertIn('if Path(relative).is_absolute() or ".." in Path(relative).parts', RESTORE)


class VerifyTest(unittest.TestCase):
    def test_success_requires_a_marker_per_database(self):
        self.assertIn("${db}_logical_match", VERIFY)
        self.assertIn("Успех без маркера не засчитывается", VERIFY)
        # Маркер печатается только после успешного diff.
        diff_index = VERIFY.index("if diff -q /tmp/expected.norm /tmp/actual.norm")
        self.assertLess(diff_index, VERIFY.index("echo '${db}_logical_match'"))

    def test_tcp_check_uses_the_running_container_credentials(self):
        self.assertIn("printenv POSTGRES_PASSWORD", VERIFY)
        self.assertNotIn("--env-file", VERIFY)
        self.assertIn('psql -h postgres -U "$PG_USER" -d postgres -tAc "select 1"', VERIFY)
        # Пароль передаётся через окружение процесса, а не как -e PGPASSWORD=значение.
        self.assertIn('-e PGPASSWORD="$PG_PASSWORD"', VERIFY)

    def test_working_project_is_refused(self):
        self.assertIn('[[ "$PROJECT_NAME" != "corvex" ]]', VERIFY)

    def test_empty_volume_is_not_a_match(self):
        # `diff -qr` двух пустых каталогов возвращает 0: пустой том — не доказательство.
        self.assertIn('files=', VERIFY)
        self.assertIn('die "том $volume пуст: сверять нечего"', VERIFY)


class AcceptanceTest(unittest.TestCase):
    def test_step_functions_are_defined_before_first_use(self):
        # Первая версия ловила `finish: command not found`, потому что функция
        # объявлялась ниже вызова: bash читает файл сверху вниз.
        definition = ACCEPTANCE.index("finish() {")
        self.assertLess(definition, ACCEPTANCE.index("invoke_step \"toolchain\""))

    def test_arguments_of_smoke_scripts_are_passed_as_an_array(self):
        self.assertIn("scripts/runtime_smoke.py \"${args[@]}\"", ACCEPTANCE)
        self.assertNotIn("bash -c", ACCEPTANCE)

    def test_skipped_step_is_not_a_failure_but_changes_the_exit_code(self):
        self.assertIn('3) status="SKIP"', ACCEPTANCE)
        self.assertIn('if [[ "${#SKIPPED_STEPS[@]}" -gt 0 ]]; then', ACCEPTANCE)

    def test_reports_are_written_next_to_the_log(self):
        self.assertIn("acceptance-$STAMP.json", ACCEPTANCE)
        self.assertIn("acceptance-$STAMP.log", ACCEPTANCE)


class DocumentationTest(unittest.TestCase):
    def test_install_guide_no_longer_promises_no_linux_backup(self):
        install = (ROOT / "docs" / "INSTALL.md").read_text(encoding="utf-8")
        self.assertNotIn("Linux-VM: скриптов пока нет", install)
        self.assertIn("backup.sh", install)
        self.assertIn("restore_verify.sh", install)

    def test_scripts_readme_lists_every_bash_script(self):
        readme = (SCRIPTS / "README.md").read_text(encoding="utf-8")
        for name in ("first-run.sh", "backup.sh", "restore.sh", "restore_verify.sh", "acceptance.sh"):
            with self.subTest(script=name):
                self.assertIn(name, readme)


if __name__ == "__main__":
    unittest.main()
