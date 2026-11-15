#!/usr/bin/env python3
"""Read-only aggregate of external-mail import statuses; never retries or repairs."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SQL = """BEGIN READ ONLY;
SELECT json_build_object(
  'queued', count(*) FILTER (WHERE status = 'QUEUED'),
  'queued_older_than_hour', count(*) FILTER (WHERE status = 'QUEUED' AND updated_at < now() - interval '1 hour'),
  'queued_without_raw_ref', count(*) FILTER (WHERE status = 'QUEUED' AND (raw_ref IS NULL OR raw_ref = '')),
  'failed', count(*) FILTER (WHERE status = 'FAILED'),
  'other_status', count(*) FILTER (WHERE status IS NULL OR status NOT IN ('QUEUED','DELIVERED','FAILED','SKIPPED_INITIAL','SKIPPED_TOO_LARGE'))
)::text FROM external_mail_imported_messages;
ROLLBACK;
"""
KEYS = {'queued', 'queued_older_than_hour', 'queued_without_raw_ref', 'failed', 'other_status'}


class ReportError(Exception):
    pass


def parse_report(raw: bytes) -> dict[str, int]:
    try:
        result = json.loads(raw.decode('utf-8').strip())
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReportError('Невозможно разобрать ответ базы.') from exc
    if not isinstance(result, dict) or set(result) != KEYS:
        raise ReportError('Некорректная структура ответа базы.')
    if any(type(value) is not int or value < 0 for value in result.values()):
        raise ReportError('Некорректные счётчики базы.')
    if result['queued_older_than_hour'] > result['queued'] or result['queued_without_raw_ref'] > result['queued']:
        raise ReportError('Несогласованные счётчики базы.')
    return result


def execute(args: list[str], *, payload: bytes | None = None) -> bytes:
    try:
        result = subprocess.run(args, cwd=ROOT, input=payload, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ReportError('Docker недоступен или истёк таймаут.') from exc
    if result.returncode != 0:
        # No native stderr: it can contain connection details or SQL context.
        raise ReportError(f'Команда Docker завершилась с кодом {result.returncode}.')
    return result.stdout


def collect(project: str | None) -> dict[str, int]:
    compose = ['docker', 'compose'] + (['-p', project] if project else [])
    raw_user = execute(compose + ['exec', '-T', 'postgres', 'printenv', 'POSTGRES_USER'])
    try:
        user = raw_user.decode('utf-8').strip()
    except UnicodeDecodeError as exc:
        raise ReportError('Некорректное имя пользователя базы.') from exc
    if not user or '\n' in user or '\r' in user or '\0' in user or user.startswith('-'):
        raise ReportError('Некорректное имя пользователя базы.')
    output = execute(compose + ['exec', '-T', 'postgres', 'psql', '-X', '-q', '-U', user,
                                '-d', 'mail_db', '-A', '-t', '-v', 'ON_ERROR_STOP=1'],
                     payload=SQL.encode('utf-8'))
    return parse_report(output)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', help='Имя Compose-проекта при необходимости')
    args = parser.parse_args(argv)
    try:
        report = collect(args.project)
    except ReportError as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 2
    print('Снимок статусов внешнего импорта (только чтение):')
    for key in sorted(KEYS):
        print(f'{key}: {report[key]}')
    print('QUEUED не доказывает доставку или публикацию. Счётчики не проверяют файлы, брокер или провайдера.')
    print('Не повторяйте и не удаляйте данные автоматически; необходимо отдельное reconciliation.')
    # Even a zero count is not an acceptance or cutover approval.
    return 0


if __name__ == '__main__':
    sys.exit(main())
