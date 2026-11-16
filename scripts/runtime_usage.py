#!/usr/bin/env python3
"""Снимок фактического потребления контейнеров: RAM, CPU, диск томов.

Нужен, чтобы рекомендации по размеру сервера опирались на измерения, а не на
«минимум 16 ГБ». Сделайте снимок на рабочем стенде под обычной нагрузкой
(после входа в webmail, открытия почты и чата), затем сравните с расчётом.

Что делает:
  * `docker stats --no-stream` по контейнерам проекта -> RAM/CPU в JSON и таблицей;
  * `docker system df` -> размер образов, томов и build cache;
  * ничего не останавливает, не удаляет и не перезапускает.

Примеры:
    py -3 scripts/runtime_usage.py
    py -3 scripts/runtime_usage.py --report artifacts/smoke/usage.json
    py -3 scripts/runtime_usage.py --project corvex --json
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent

# docker stats --format "{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}" -> "1.5GiB / 2GiB"
MEMORY = re.compile(r"([\d.]+)\s*([KMGT]?i?B)\s*/\s*([\d.]+)\s*([KMGT]?i?B)")
UNITS = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3,
         "kB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}


def to_bytes(value: str, unit: str) -> float:
    return float(value) * UNITS.get(unit, 1)


def run(arguments: list[str], timeout: int = 60) -> str:
    try:
        result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout, check=False)
    except FileNotFoundError as exc:
        raise RuntimeError(f"команда не найдена: {arguments[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"таймаут: {' '.join(arguments[:3])}") from exc
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip()[:500]
                           or f"exit {result.returncode}")
    return result.stdout


def collect_stats(project: str | None) -> list[dict[str, Any]]:
    arguments = ["docker", "stats", "--no-stream", "--format",
                 "{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}"]
    if project:
        names = run(["docker", "ps", "--filter", f"label=com.docker.compose.project={project}",
                     "--format", "{{.Names}}"]).split()
        if not names:
            raise RuntimeError(f"нет запущенных контейнеров проекта {project!r}")
        arguments.extend(names)
    rows: list[dict[str, Any]] = []
    for line in run(arguments, timeout=120).splitlines():
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        name, memory, memory_percent, cpu = parts[0], parts[1], parts[2], parts[3]
        match = MEMORY.search(memory)
        used = to_bytes(match.group(1), match.group(2)) if match else None
        limit = to_bytes(match.group(3), match.group(4)) if match else None
        rows.append({
            "name": name,
            "memory_bytes": int(used) if used is not None else None,
            "memory_limit_bytes": int(limit) if limit is not None else None,
            "memory_percent": memory_percent.rstrip("%") or None,
            "cpu_percent": cpu.rstrip("%") or None,
        })
    rows.sort(key=lambda item: item["memory_bytes"] or 0, reverse=True)
    return rows


def collect_disk() -> list[dict[str, str]]:
    """`docker system df` даёт образы, контейнеры, тома и build cache."""
    output = run(["docker", "system", "df", "--format",
                  "{{.Type}}\t{{.Size}}\t{{.Reclaimable}}"])
    result = []
    for line in output.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            result.append({"type": parts[0], "size": parts[1],
                           "reclaimable": parts[2] if len(parts) > 2 else ""})
    return result


def format_mib(value: int | None) -> str:
    if value is None:
        return "-"
    return f"{value / 1024 ** 2:,.0f}".replace(",", " ")


def print_human(rows: list[dict[str, Any]], disk: list[dict[str, str]]) -> None:
    print(f"{'контейнер':44} {'RAM, МиБ':>10} {'от лимита':>10} {'CPU, %':>8}")
    total = 0
    for row in rows:
        total += row["memory_bytes"] or 0
        print(f"{row['name'][:44]:44} {format_mib(row['memory_bytes']):>10} "
              f"{str(row['memory_percent'] or '-'):>10} {str(row['cpu_percent'] or '-'):>8}")
    print("-" * 76)
    print(f"{'всего по запущенным контейнерам':44} {format_mib(total):>10} МиБ")
    print()
    print("Диск (docker system df):")
    for item in disk:
        print(f"  {item['type']:<14} {item['size']:>10}  можно освободить: {item['reclaimable'] or '-'}")
    print()
    print("Как читать: сумма RAM — это то, что стек занимает сейчас, а не пик под нагрузкой.")
    print("Пик проверяйте серией снимков во время рассылки и входа нескольких пользователей;")
    print("к расчётной сумме добавляйте 20–30% запаса и ещё запас на страницу/файловый кэш ОС.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Снимок потребления RAM/CPU/диска запущенного Corvex (ничего не меняет)."
    )
    parser.add_argument("--project", help="имя Compose-проекта; по умолчанию все контейнеры")
    parser.add_argument("--report", type=Path, help="JSON-отчёт, например artifacts/smoke/usage.json")
    parser.add_argument("--json", action="store_true", help="вывести JSON в stdout")
    parser.add_argument("--skip-disk", action="store_true", help="не вызывать docker system df")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        rows = collect_stats(args.project)
        disk = [] if args.skip_disk else collect_disk()
    except RuntimeError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1

    payload = {
        "created_at": datetime.now(timezone.utc).isoformat(),  # noqa: UP017
        "project": args.project,
        "containers": rows,
        "total_memory_bytes": sum(row["memory_bytes"] or 0 for row in rows),
        "disk": disk,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print_human(rows, disk)
        if args.report:
            print(f"\nJSON: {args.report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
