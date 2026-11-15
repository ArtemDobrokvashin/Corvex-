#!/usr/bin/env python3
"""Read selected clamd configuration values; never scan mail or modify containers."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# Configured values only, not assumed defaults or a live daemon introspection API.
BOOL_KEYS = ('AlertExceedsMax', 'ScanMail')
NUMBER_KEYS = ('MaxScanSize', 'MaxFileSize', 'MaxRecursion', 'MaxFiles',
               'MaxScanTime', 'StreamMaxLength')
KEYS = BOOL_KEYS + NUMBER_KEYS


class ReportError(Exception):
    pass


def parse_config(raw: bytes) -> dict[str, str | None]:
    if len(raw) > 256 * 1024:
        raise ReportError('Конфигурация слишком велика.')
    try:
        text = raw.decode('utf-8')
    except UnicodeDecodeError as exc:
        raise ReportError('Не удалось прочитать конфигурацию как UTF-8.') from exc
    result: dict[str, str | None] = dict.fromkeys(KEYS)
    seen: set[str] = set()
    for line in text.splitlines():
        line = line.split('#', 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        key = parts[0]
        if key not in result:
            continue
        if key in seen or len(parts) != 2:
            raise ReportError(f'Повторная или некорректная настройка {key}.')
        seen.add(key)
        value = parts[1]
        pattern = r'(?i:yes|no|true|false)' if key in BOOL_KEYS else r'[0-9]+(?:[kKmMgG](?:[bB])?)?'
        if not re.fullmatch(pattern, value):
            raise ReportError(f'Неизвестное значение настройки {key}.')
        result[key] = value
    return result


def collect(compose_dir: Path, project: str | None) -> dict[str, str | None]:
    cmd = ['docker', 'compose'] + (['-p', project] if project else [])
    cmd += ['exec', '-T', 'clamav', 'cat', '/etc/clamav/clamd.conf']
    try:
        result = subprocess.run(cmd, cwd=compose_dir, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ReportError('Docker недоступен или истёк таймаут.') from exc
    if result.returncode != 0:
        # stderr is suppressed because it can include deployment-specific details.
        raise ReportError(f'Чтение конфигурации завершилось с кодом {result.returncode}.')
    return parse_config(result.stdout)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-dir', type=Path, default=Path.cwd())
    parser.add_argument('--project', help='Имя Compose-проекта, если необходимо')
    args = parser.parse_args(argv)
    try:
        values = collect(args.compose_dir, args.project)
    except ReportError as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 2
    print('Только явно заданные значения в /etc/clamav/clamd.conf:')
    for key, value in values.items():
        print(f'{key}: {value if value is not None else "не задано явно"}')
    print('Отсутствие строки НЕ означает отключение: применяются значения по умолчанию.')
    print('Файл не подтверждает фактические runtime-настройки, полное сканирование или готовность AV.')
    print('Ничего не изменено; почта и содержимое очередей не читались.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
