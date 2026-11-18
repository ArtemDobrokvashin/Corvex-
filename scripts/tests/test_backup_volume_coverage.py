"""Бэкап покрывает все долговременные тома, которые нельзя восстановить из данных.

Две ошибки, которые этот тест ловит, обе тихие и обе дорогие:

1. Добавили том в `docker-compose.yml`, а в `backup.ps1` — нет. Бэкап проходит
   успешно, просто конкретных файлов в нём нет. Обнаруживается при восстановлении.
2. Опечатка в имени тома внутри `backup.ps1`. `docker run -v <несуществующий>:/data`
   создаёт **новый пустой** том и архивирует его. Скрипт печатает успех, архив
   непустой (там gzip-заголовок), а данных нет.

Поэтому проверяется не «есть ли список», а согласованность трёх источников:
тома compose ↔ то, что архивирует бэкап ↔ то, что восстанавливает restore.
Всё остальное перечислено в `REBUILDABLE` с причиной: это не «забыли», это решение.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
BACKUP = (ROOT / "scripts/backup.ps1").read_text(encoding="utf-8")
RESTORE = (ROOT / "scripts/restore.ps1").read_text(encoding="utf-8")
VERIFY = (ROOT / "scripts/restore_verify.ps1").read_text(encoding="utf-8")

# Тома, которые в бэкап сознательно не попадают. Бэкапить их — значит хранить
# копию того, что восстанавливается само, и раздувать архив на гигабайты.
REBUILDABLE = {
    "postgres-data": "логический дамп pg_dump уже в бэкапе",
    "james-postgres-data": "James не развёрнут: этап переноса отложен",
    "james-data": "James не развёрнут: этап переноса отложен",
    "redis-data": "кэш, наполняется заново",
    "rabbitmq-data": "очереди: 45 сообщений DLQ переносятся вручную, см. docs/OPERATIONS.md",
    "elasticsearch-data": "индекс перестраивается из БД, см. комментарий в backup.ps1",
    "office-preview-cache": "кэш превью, пересоздаётся при открытии документа",
    "spamd-data": "обучение spamd восстанавливается из почты",
    "clamav-data": "определения антивируса скачиваются заново",
    "prometheus-data": "метрики: история наблюдений, не данные сервиса",
    "alertmanager-data": "silences и история алертов; их конфигурация приходит из infra/prometheus",
    "grafana-data": "дашборды приходят из infra/grafana/provisioning",
    "loki-data": "журналы: история наблюдений, не данные сервиса",
}


def compose_volumes() -> set[str]:
    """Имена томов из верхнеуровневого блока `volumes:` compose-файла."""
    lines = COMPOSE.splitlines()
    start = next(i for i, line in enumerate(lines) if line.rstrip() == "volumes:")
    names: set[str] = set()
    for line in lines[start + 1:]:
        if line and not line.startswith(" "):
            break
        match = re.match(r"^  ([a-z0-9][a-z0-9_.-]*):", line)
        if match:
            names.add(match.group(1))
    return names


def archived_volumes(script: str) -> dict[str, str]:
    """`@{ Volume = "${ProjectName}<имя>"; Archive = ... }` → том → архив."""
    result: dict[str, str] = {}
    pattern = re.compile(
        r'Volume\s*=\s*"\$\{ProjectName\}[_-]([a-z0-9][a-z0-9_.-]*)";\s*'
        r'Archive\s*=\s*"([^"]+)"',
        re.IGNORECASE,
    )
    for match in pattern.finditer(script):
        result[match.group(1)] = match.group(2)
    return result


class BackupVolumeCoverageTest(unittest.TestCase):
    def test_every_compose_volume_is_either_backed_up_or_declared_rebuildable(self):
        volumes = compose_volumes()
        self.assertGreaterEqual(len(volumes), 10, "блок volumes: разобран неправильно")

        backed_up = set(archived_volumes(BACKUP))
        uncovered = sorted(volumes - backed_up - set(REBUILDABLE))
        self.assertEqual(
            [],
            uncovered,
            "новый том не попадает в бэкап и не объявлен восстановимым: "
            + ", ".join(uncovered),
        )

    def test_rebuildable_list_has_reasons_and_no_stale_entries(self):
        for name, reason in REBUILDABLE.items():
            self.assertTrue(reason.strip(), f"для тома {name} не указана причина")
        stale = sorted(set(REBUILDABLE) - compose_volumes())
        self.assertEqual([], stale, "в REBUILDABLE остались удалённые тома: " + ", ".join(stale))
        overlap = sorted(set(REBUILDABLE) & set(archived_volumes(BACKUP)))
        self.assertEqual([], overlap, "том одновременно в бэкапе и в списке восстановимых")

    def test_backup_never_names_a_volume_that_compose_does_not_define(self):
        # Опечатка здесь молча создаёт пустой том и «успешный» пустой архив.
        unknown = sorted(set(archived_volumes(BACKUP)) - compose_volumes())
        self.assertEqual(
            [],
            unknown,
            "backup.ps1 архивирует том, которого нет в compose: " + ", ".join(unknown),
        )

    def test_restore_expects_exactly_the_volumes_backup_produces(self):
        self.assertEqual(archived_volumes(BACKUP), archived_volumes(RESTORE))
        self.assertEqual(archived_volumes(BACKUP), archived_volumes(VERIFY))

    def test_guard_is_not_vacuous(self):
        backed_up = archived_volumes(BACKUP)
        self.assertEqual(
            {"mail-data", "attachment-data", "chat-file-data", "alert-relay-data"},
            set(backed_up),
        )
        for archive in backed_up.values():
            self.assertTrue(archive.endswith(".tar.gz"), archive)
            self.assertIn(archive, RESTORE)
            self.assertIn(archive, VERIFY)


if __name__ == "__main__":
    unittest.main()
