#!/usr/bin/env python3
"""Read-only aggregate queue snapshot for the mail AV pipeline; never reads messages."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

QUEUES = (
    'mail.av.spam-checked', 'mail.av.spam-checked.dlq',
    'mail.storage.av-checked', 'mail.storage.av-checked.dlq',
)
FIELDS = ('messages_ready', 'messages_unacknowledged', 'consumers')


class ReportError(Exception):
    pass


def parse_report(raw: bytes) -> dict[str, dict[str, int | str] | None]:
    try:
        rows = json.loads(raw.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReportError('Некорректный ответ RabbitMQ.') from exc
    if not isinstance(rows, list):
        raise ReportError('Некорректная структура ответа RabbitMQ.')
    found: dict[str, dict[str, int | str] | None] = dict.fromkeys(QUEUES)
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get('name'), str):
            raise ReportError('Некорректная запись очереди.')
        name = row['name']
        if name not in found:
            continue
        if found[name] is not None:
            raise ReportError('Повторяющаяся очередь в ответе RabbitMQ.')
        if any(type(row.get(key)) is not int or row[key] < 0 for key in FIELDS):
            raise ReportError('Некорректные счётчики очереди.')
        queue_type = row.get('type')
        if queue_type not in ('classic', 'quorum', 'stream'):
            raise ReportError('Неизвестный тип или недоступная очередь.')
        found[name] = {key: row[key] for key in FIELDS} | {'type': queue_type}
    return found


def collect(compose_dir: Path, project: str | None) -> dict[str, dict[str, int | str] | None]:
    cmd = ['docker', 'compose'] + (['-p', project] if project else [])
    cmd += ['exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues',
            'name', 'messages_ready', 'messages_unacknowledged', 'consumers', 'type', '--formatter', 'json']
    try:
        result = subprocess.run(cmd, cwd=compose_dir, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=75, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ReportError('Docker недоступен или истёк таймаут.') from exc
    if result.returncode != 0:
        raise ReportError(f'Команда Docker завершилась с кодом {result.returncode}.')
    return parse_report(result.stdout)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-dir', type=Path, default=Path.cwd(), help='Папка действующего docker-compose.yml')
    parser.add_argument('--project', help='Имя Compose-проекта при необходимости')
    args = parser.parse_args(argv)
    try:
        result = collect(args.compose_dir, args.project)
    except ReportError as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 2
    print('Снимок очередей AV (только счётчики; содержимое сообщений не читается):')
    for name, values in result.items():
        if values is None:
            print(f'{name}: отсутствует или не видна')
        else:
            print(f"{name}: ready={values['messages_ready']} unacked={values['messages_unacknowledged']} "
                  f"consumers={values['consumers']} type={values['type']}")
    print('Это не согласованный снимок и не подтверждение сохранности spool или безопасного восстановления.')
    print('Не очищайте и не повторяйте DLQ автоматически; старые ERROR требуют повторного сканирования.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
