#!/usr/bin/env python3
"""Read-only comparison of mail metadata with Maildir files; never repairs data."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from uuid import UUID

ROOT = Path(__file__).resolve().parent.parent
MAIL_ROOT = '/data/mail/'
SQL = "SELECT json_build_object('id', id, 'path', maildir_path)::text FROM message_metadata;"


class InventoryError(Exception):
    pass


@dataclass(frozen=True)
class Inventory:
    records: int
    missing: tuple[tuple[str, str], ...]
    duplicates: tuple[tuple[str, int], ...]
    orphans: tuple[str, ...]


def valid_path(path: str) -> bool:
    if not isinstance(path, str) or not path or '\\' in path or '\x00' in path:
        return False
    parts = path.split('/')
    return all(part not in ('', '.', '..') for part in parts) and not PurePosixPath(path).is_absolute()


def compare(rows: list[dict], files: list[str]) -> Inventory:
    counts: Counter[str] = Counter()
    missing = []
    existing = set(files)
    for row in rows:
        if not isinstance(row, dict) or set(row) != {'id', 'path'}:
            raise InventoryError('Некорректная строка метаданных.')
        try:
            message_id = str(UUID(row['id']))
        except (ValueError, TypeError, AttributeError) as exc:
            raise InventoryError('Некорректный ID в метаданных.') from exc
        path = row['path']
        if not valid_path(path):
            raise InventoryError('Обнаружен некорректный путь в метаданных; проверка остановлена.')
        full = MAIL_ROOT + path
        counts[full] += 1
        if full not in existing:
            missing.append((message_id, path))
    duplicates = tuple(sorted((p[len(MAIL_ROOT):], count) for p, count in counts.items() if count > 1))
    # Only normal Maildir message files are candidates; exclude spool, tmp and other data.
    candidates = set()
    for full in existing:
        if not full.startswith(MAIL_ROOT):
            raise InventoryError('Список файлов содержит путь вне /data/mail.')
        rel = full[len(MAIL_ROOT):]
        parts = rel.split('/')
        if (len(parts) == 4 and parts[0] != '_spool' and parts[2] in ('new', 'cur')
                and parts[3].endswith('.eml') and valid_path(rel)):
            candidates.add(full)
    orphans = tuple(sorted(p[len(MAIL_ROOT):] for p in candidates if p not in counts))
    return Inventory(len(rows), tuple(sorted(missing)), duplicates, orphans)


def run(args: list[str], *, input_text: str | None = None) -> bytes:
    try:
        result = subprocess.run(args, cwd=ROOT, input=None if input_text is None else input_text.encode('utf-8'),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    except OSError as exc:
        raise InventoryError(f'Не удалось запустить Docker: {exc}') from exc
    if result.returncode:
        raise InventoryError(f'Команда Docker завершилась с кодом {result.returncode}: '
                             + result.stderr.decode('utf-8', errors='replace').strip())
    return result.stdout


def collect(project: str | None) -> Inventory:
    compose = ['docker', 'compose'] + (['-p', project] if project else [])
    user = run(compose + ['exec', '-T', 'postgres', 'printenv', 'POSTGRES_USER']).decode('utf-8').strip()
    if not user or '\n' in user or '\r' in user:
        raise InventoryError('Некорректный POSTGRES_USER.')
    configured_root = run(compose + ['exec', '-T', 'mail-storage-service', 'sh', '-c',
                                     'printf %s "${CORVEX_MAIL_ROOT:-/data/mail}"']).decode('utf-8').strip()
    if configured_root.rstrip('/') != '/data/mail':
        raise InventoryError('Нестандартный CORVEX_MAIL_ROOT: автоматическая проверка остановлена.')
    raw_rows = run(compose + ['exec', '-T', 'postgres', 'psql', '-X', '-U', user, '-d', 'mail_db',
                              '-A', '-t', '-v', 'ON_ERROR_STOP=1'], input_text=SQL + '\n')
    raw_files = run(compose + ['exec', '-T', 'mail-storage-service', 'find', '/data/mail', '-type', 'f', '-print0'])
    try:
        rows = [json.loads(line) for line in raw_rows.decode('utf-8').splitlines() if line.strip()]
        if raw_files and not raw_files.endswith(b'\0'):
            raise InventoryError('Неполный список файлов.')
        files = [part.decode('utf-8') for part in raw_files.split(b'\0') if part]
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InventoryError('Не удалось разобрать ответ базы или список файлов.') from exc
    return compare(rows, files)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', help='Имя Compose-проекта, если требуется явно')
    parser.add_argument('--show-paths', action='store_true', help='Вывести ID и пути; могут содержать адреса ящиков')
    args = parser.parse_args(argv)
    try:
        result = collect(args.project)
    except InventoryError as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 2
    print('ВНИМАНИЕ: база и файлы прочитаны последовательно, не как единый снимок.')
    print(f'Записей: {result.records}; отсутствующих файлов: {len(result.missing)}; '
          f'повторных путей: {len(result.duplicates)}; файлов-кандидатов без записи: {len(result.orphans)}')
    print('Кандидат без записи не обязательно мусор: он может быть частью незавершённой операции. Ничего не удаляйте.')
    if args.show_paths:
        for message_id, path in result.missing:
            print(f'MISSING {message_id} {path}')
        for path, count in result.duplicates:
            print(f'DUPLICATE {count} {path}')
        for path in result.orphans:
            print(f'ORPHAN-CANDIDATE {path}')
    return 1 if result.missing or result.duplicates or result.orphans else 0


if __name__ == '__main__':
    sys.exit(main())
