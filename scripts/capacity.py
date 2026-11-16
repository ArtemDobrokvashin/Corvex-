#!/usr/bin/env python3
"""Оценка диска и памяти для Corvex под заданное число ящиков.

Зачем нужен расчёт, а не «500 ГБ на всякий случай»: размер диска зависит от
квоты на ящик, реального заполнения, срока хранения, индекса поиска и того,
лежат ли резервные копии на том же диске. Для 50 ящиков и для 1000 ящиков
получаются разные числа, и завышенное требование мешает согласовать сервер.

Скрипт считает только арифметику по явным допущениям и печатает их рядом с
результатом. Он не измеряет ваш стенд: фактические размеры снимаются
`scripts/runtime_usage.py` (память контейнеров) и `docker system df` /
`du` по томам (диск).

Примеры:
    py -3 scripts/capacity.py --mailboxes 50
    py -3 scripts/capacity.py --mailboxes 50 --quota-gib 10 --years 2 --json
    py -3 scripts/capacity.py --mailboxes 1000 --backup-on-same-disk
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import asdict, dataclass

GIB = 1024 ** 3

# Допущения по умолчанию. Каждое видно в отчёте, чтобы число можно было оспорить.
DEFAULT_QUOTA_GIB = 5.0          # mail.defaultQuotaBytes = 5368709120
DEFAULT_FILL_PERCENT = 20.0      # реальное заполнение за первый год при квоте 5 ГиБ
DEFAULT_DEDUPE = 0.25            # дедупликация вложений по SHA-256, типично 20–30%
DEFAULT_WORK_DAYS = 250
DEFAULT_MESSAGES_PER_USER_DAY = 40
DEFAULT_PG_BYTES_PER_MESSAGE = 2000     # строка метаданных + индексы
DEFAULT_ES_BYTES_PER_MESSAGE = 3000     # текст и поля в индексе поиска
DEFAULT_ES_REPLICAS = 1                 # single-node кластер: 1 реплика на шард
DEFAULT_CHAT_AND_FILES_GIB = 5.0
DEFAULT_OS_GIB = 60.0
DEFAULT_IMAGES_GIB = 25.0               # 16 локальных образов + 4 upstream + build cache
DEFAULT_BACKUP_COPIES = 2.0             # кратность полных копий (не дедуплицируются)
DEFAULT_MONITORING_GIB = 15.0           # Prometheus/Loki при 90 днях retention


@dataclass
class Estimate:
    mailboxes: int
    quota_gib: float
    years: float
    logical_quota_gib: float
    mailbox_data_gib: float
    metadata_gib: float
    search_gib: float
    chat_and_files_gib: float
    monitoring_gib: float
    system_gib: float
    data_disk_gib: float
    backup_disk_gib: float
    total_gib: float
    recommended_system_disk_gib: int
    recommended_data_disk_gib: int
    recommended_backup_disk_gib: int
    messages_per_year: int
    notes: list[str]


def round_up(value: float, step: int) -> int:
    """Округление вверх до шага: диски продаются по 100/200/500 ГБ, а не по 37."""
    if value <= 0:
        return 0
    return int(math.ceil(value / step) * step)


def estimate(
    mailboxes: int,
    quota_gib: float = DEFAULT_QUOTA_GIB,
    fill_percent: float = DEFAULT_FILL_PERCENT,
    dedupe: float = DEFAULT_DEDUPE,
    years: float = 1.0,
    messages_per_user_day: int = DEFAULT_MESSAGES_PER_USER_DAY,
    work_days: int = DEFAULT_WORK_DAYS,
    pg_bytes_per_message: int = DEFAULT_PG_BYTES_PER_MESSAGE,
    es_bytes_per_message: int = DEFAULT_ES_BYTES_PER_MESSAGE,
    es_replicas: int = DEFAULT_ES_REPLICAS,
    chat_and_files_gib: float = DEFAULT_CHAT_AND_FILES_GIB,
    monitoring_gib: float = DEFAULT_MONITORING_GIB,
    backup_copies: float = DEFAULT_BACKUP_COPIES,
    backup_on_same_disk: bool = False,
) -> Estimate:
    """Считает объём данных по годам и рекомендует размеры дисков."""
    if mailboxes <= 0:
        raise ValueError("--mailboxes должен быть > 0")
    if quota_gib <= 0:
        raise ValueError("--quota-gib должен быть > 0")
    if not 0 <= fill_percent <= 100:
        raise ValueError("--fill-percent должен быть в диапазоне 0…100")
    if not 0 <= dedupe < 1:
        raise ValueError("--dedupe должен быть в диапазоне 0…0.99")
    if years <= 0:
        raise ValueError("--years должен быть > 0")

    notes: list[str] = []

    logical = mailboxes * quota_gib
    filled = logical * (fill_percent / 100.0) * years
    mailbox_data = filled * (1.0 - dedupe)

    messages = int(mailboxes * messages_per_user_day * work_days * years)
    metadata = messages * pg_bytes_per_message / GIB
    # Один шард + реплики: индекс поиска занимает bytes * (1 + replicas).
    search = messages * es_bytes_per_message * (1 + es_replicas) / GIB

    data = mailbox_data + metadata + search + chat_and_files_gib + monitoring_gib
    system = DEFAULT_OS_GIB + DEFAULT_IMAGES_GIB

    # Резервные копии — полные снимки; retention управляется вручную, поэтому
    # считаем кратность, а не «столько же, сколько данных».
    backup = (data - monitoring_gib) * backup_copies

    if backup_on_same_disk:
        total = system + data + backup
        notes.append(
            "Резервные копии посчитаны на том же диске: потеря диска уничтожит и данные, "
            "и копии. Для пилота допустимо только при отдельной внешней копии."
        )
        data_disk = total
        backup_disk = 0
    else:
        total = system + data
        data_disk = data
        backup_disk = backup
        notes.append(
            "Резервные копии вынесены на отдельный диск или NAS: это требование "
            "восстановления, а не удобство."
        )

    if logical > mailbox_data / max(fill_percent / 100.0 * (1 - dedupe), 1e-9) * 1.0:
        notes.append(
            f"Логический предел квот {logical:.0f} ГиБ выше расчётного заполнения "
            f"{mailbox_data:.0f} ГиБ: квоты — это потолок, а не занятое место. "
            "Следите за 90% тома, а не за суммой квот."
        )
    if mailboxes <= 100:
        notes.append(
            "До ~100 ящиков Elasticsearch и PostgreSQL на одном узле не требуют "
            "шардирования; важнее SSD и свободное место под рост индекса."
        )

    return Estimate(
        mailboxes=mailboxes,
        quota_gib=quota_gib,
        years=years,
        logical_quota_gib=round(logical, 1),
        mailbox_data_gib=round(mailbox_data, 1),
        metadata_gib=round(metadata, 1),
        search_gib=round(search, 1),
        chat_and_files_gib=round(chat_and_files_gib, 1),
        monitoring_gib=round(monitoring_gib, 1),
        system_gib=round(system, 1),
        data_disk_gib=round(data_disk, 1),
        backup_disk_gib=round(backup_disk, 1),
        total_gib=round(total, 1),
        recommended_system_disk_gib=round_up(system, 50),
        recommended_data_disk_gib=round_up(data_disk * 1.5, 50) if data_disk else 0,
        recommended_backup_disk_gib=round_up(backup_disk, 100),
        messages_per_year=messages,
        notes=notes,
    )


def format_gib(value: float) -> str:
    return f"{value:,.1f} ГиБ".replace(",", " ")


def print_human(result: Estimate, args: argparse.Namespace) -> None:
    print(f"Расчёт для {result.mailboxes} ящиков, квота {result.quota_gib:g} ГиБ, "
          f"горизонт {result.years:g} год(а)")
    print()
    print("Данные:")
    print(f"  почта (после дедупликации)   {format_gib(result.mailbox_data_gib)}")
    print(f"  метаданные PostgreSQL        {format_gib(result.metadata_gib)}")
    print(f"  индекс поиска Elasticsearch  {format_gib(result.search_gib)}")
    print(f"  чат и файлы                  {format_gib(result.chat_and_files_gib)}")
    print(f"  мониторинг                   {format_gib(result.monitoring_gib)}")
    print(f"  ОС, образы и build cache     {format_gib(result.system_gib)}")
    print()
    print("Итого:")
    if result.backup_disk_gib:
        print(f"  системный диск               {format_gib(result.system_gib)} "
              f"→ рекомендуется {result.recommended_system_disk_gib} ГБ "
              f"(Windows, Docker-образы, build cache)")
        print(f"  диск данных                  {format_gib(result.data_disk_gib)} "
              f"→ рекомендуется {result.recommended_data_disk_gib} ГБ "
              f"(запас на рост и загрузки)")
        print(f"  диск резервных копий         {format_gib(result.backup_disk_gib)} "
              f"→ рекомендуется {result.recommended_backup_disk_gib} ГБ")
        local = result.recommended_system_disk_gib + result.recommended_data_disk_gib
        print(f"  на самом сервере             {local} ГБ "
              f"(система + данные), бэкап — отдельный носитель ≥ "
              f"{result.recommended_backup_disk_gib} ГБ")
        print(f"  всё вместе                   "
              f"{local + result.recommended_backup_disk_gib} ГБ")
    else:
        print(f"  один диск                    {format_gib(result.total_gib)} "
              f"→ рекомендуется {result.recommended_data_disk_gib} ГБ")
    print(f"  писем в год                  {result.messages_per_year:,}".replace(",", " "))
    print(f"  логический предел квот       {format_gib(result.logical_quota_gib)} (не занятое место)")
    print()
    print("Допущения: заполнение "
          f"{args.fill_percent:g}% квоты, дедупликация {args.dedupe * 100:g}%, "
          f"{args.messages_per_user_day} писем на ящик в рабочий день, "
          f"{args.work_days} рабочих дней.")
    for note in result.notes:
        print(f"! {note}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Оценка диска для Corvex по числу ящиков и квоте.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--mailboxes", type=int, default=50, help="число ящиков")
    parser.add_argument("--quota-gib", type=float, default=DEFAULT_QUOTA_GIB,
                        help="квота одного ящика, ГиБ (по умолчанию как mail.defaultQuotaBytes)")
    parser.add_argument("--fill-percent", type=float, default=DEFAULT_FILL_PERCENT,
                        help="реальное заполнение квоты за год, %%")
    parser.add_argument("--dedupe", type=float, default=DEFAULT_DEDUPE,
                        help="доля дедупликации вложений, 0…0.99")
    parser.add_argument("--years", type=float, default=1.0, help="горизонт, лет")
    parser.add_argument("--messages-per-user-day", type=int,
                        default=DEFAULT_MESSAGES_PER_USER_DAY)
    parser.add_argument("--work-days", type=int, default=DEFAULT_WORK_DAYS)
    parser.add_argument("--backup-copies", type=float, default=DEFAULT_BACKUP_COPIES,
                        help="кратность полных резервных копий: 2 — текущая + одна внешняя, 3 — более длинное хранение")
    parser.add_argument("--backup-on-same-disk", action="store_true",
                        help="копии на том же диске (пилот без NAS)")
    parser.add_argument("--json", action="store_true", help="машинный вывод")
    args = parser.parse_args()
    if args.mailboxes <= 0:
        parser.error("--mailboxes должен быть > 0")
    if args.quota_gib <= 0:
        parser.error("--quota-gib должен быть > 0")
    if not 0 <= args.fill_percent <= 100:
        parser.error("--fill-percent должен быть в диапазоне 0…100")
    if not 0 <= args.dedupe < 1:
        parser.error("--dedupe должен быть в диапазоне 0…0.99")
    if args.years <= 0:
        parser.error("--years должен быть > 0")
    if args.work_days <= 0 or args.messages_per_user_day <= 0:
        parser.error("--work-days и --messages-per-user-day должны быть > 0")
    if args.backup_copies <= 0:
        parser.error("--backup-copies должен быть > 0")
    return args


def main() -> int:
    args = parse_args()
    try:
        result = estimate(
            mailboxes=args.mailboxes,
            quota_gib=args.quota_gib,
            fill_percent=args.fill_percent,
            dedupe=args.dedupe,
            years=args.years,
            messages_per_user_day=args.messages_per_user_day,
            work_days=args.work_days,
            backup_copies=args.backup_copies,
            backup_on_same_disk=args.backup_on_same_disk,
        )
    except ValueError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    else:
        print_human(result, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
