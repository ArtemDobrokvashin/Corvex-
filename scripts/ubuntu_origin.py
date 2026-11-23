#!/usr/bin/env python3
"""Вписать адрес сервера в `.env`, чтобы вход работал без домена.

nginx слушает 443 на любом имени, поэтому по `https://<IP>` открывается интерфейс.
Но web-gateway проверяет `Origin` по списку `CORVEX_WEB_ORIGINS`, и пока там стоит
`https://mail.company.local`, браузер по IP получает отказ на каждом запросе —
без единой строки в логах сервисов. Этот скрипт дописывает адрес, по которому
сервер реально откроют, **до** первого `docker compose up`.

Правила:
  * ручной список из нескольких origin не затирается, а дополняется;
  * значение, выведенное из старого домена по умолчанию, заменяется;
  * файл переписывается в UTF-8 без BOM и с LF — иначе `docker compose` падает
    на первой же переменной (это уже случалось, см. docs/INSTALL.md §8);
  * повторный запуск идемпотентен: одно и то же значение не дублируется.

Примеры:

    python3 scripts/ubuntu_origin.py --env .env --ip 203.0.113.10
    python3 scripts/ubuntu_origin.py --env .env --domain mail.company.ru
    python3 scripts/ubuntu_origin.py --env .env --domain mail.company.ru --ip 203.0.113.10
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Значения по умолчанию из .env.example: они означают «адрес ещё не выбран».
PLACEHOLDER_ORIGINS = {
    "",
    "https://mail.company.local",
    "https://${CORVEX_DOMAIN:-mail.company.local}",
    "https://${CORVEX_DOMAIN}",
}

DEFAULT_DOMAIN = "mail.company.local"


def desired_origins(domain: str, ip: str) -> list[str]:
    """Адреса в порядке предпочтения: имя с сертификатом, затем IP."""
    origins: list[str] = []
    if domain:
        origins.append(f"https://{domain}")
    if ip:
        origins.append(f"https://{ip}")
    return origins


def merge_origins(current: str, domain: str, ip: str) -> str:
    """Дополнить список origin адресами сервера, не теряя ручных значений."""
    existing = [item.strip() for item in current.split(",") if item.strip()]
    if current.strip() in PLACEHOLDER_ORIGINS or all(
        item == f"https://{DEFAULT_DOMAIN}" for item in existing
    ):
        existing = []
    for origin in desired_origins(domain, ip):
        if origin not in existing:
            existing.append(origin)
    return ",".join(existing)


def set_value(text: str, name: str, value: str) -> str:
    """Заменить или добавить переменную, не трогая комментарии и порядок строк."""
    pattern = re.compile(rf"^{re.escape(name)}=.*$", re.M)
    replacement = f"{name}={value}"
    if pattern.search(text):
        return pattern.sub(lambda _match: replacement, text, count=1)
    return text.rstrip("\n") + "\n" + replacement + "\n"


def rewrite(text: str, domain: str, ip: str) -> str:
    match = re.search(r"^CORVEX_WEB_ORIGINS=(.*)$", text, re.M)
    current = match.group(1) if match else ""
    merged = merge_origins(current, domain, ip)
    if domain:
        text = set_value(text, "CORVEX_DOMAIN", domain)
    text = set_value(text, "CORVEX_WEB_ORIGINS", merged)
    return text


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--env", default=".env", help="путь к .env (по умолчанию .env)")
    parser.add_argument("--domain", default="", help="внешнее имя, например mail.company.ru")
    parser.add_argument("--ip", default="", help="публичный IP, если домена ещё нет")
    args = parser.parse_args(argv)
    if not args.domain and not args.ip:
        parser.error("нужен хотя бы один из --domain и --ip")
    if args.domain and not re.fullmatch(r"[A-Za-z0-9.-]+", args.domain):
        parser.error("--domain: допустимы буквы, цифры, точки и дефис")
    if args.ip and not re.fullmatch(r"[0-9A-Fa-f.:]+", args.ip):
        parser.error("--ip: ожидается IPv4 или IPv6")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    path = Path(args.env)
    if not path.exists():
        print(f"ОШИБКА: нет файла {path}. Сначала ./scripts/first-run.sh", file=sys.stderr)
        return 1

    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        # BOM ломает docker compose: "unexpected character \\ufeff in variable name".
        raw = raw[3:]
    text = raw.decode("utf-8").replace("\r\n", "\n")

    updated = rewrite(text, args.domain, args.ip)
    path.write_bytes(updated.encode("utf-8"))

    origins = re.search(r"^CORVEX_WEB_ORIGINS=(.*)$", updated, re.M)
    print(f"CORVEX_WEB_ORIGINS={origins.group(1) if origins else ''}")
    if args.domain:
        print(f"CORVEX_DOMAIN={args.domain}")
    else:
        print(
            "Домена нет: интерфейс откроется по IP, но сертификат выдан на "
            f"{DEFAULT_DOMAIN} — браузер покажет предупреждение. Это ожидаемо."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
