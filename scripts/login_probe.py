#!/usr/bin/env python3
"""Диагностика входа: auth-service и, по флагу, настоящий IMAP LOGIN.

IMAP не отдаёт причину отказа: James возвращает одно и то же
``LOGIN failed. Invalid login/password.`` и на неверный пароль, и на
несуществующего пользователя, и на неактивную учётку, и на сбой auth-service.
Этот скрипт проверяет те же данные в двух местах:

1. ``POST /internal/verify-password`` — ровно тот запрос, что делает
   mail-imap-service. Отвечает, подтвердил ли auth-service пароль и каким методом.
2. ``--imap`` — настоящий ``LOGIN`` на 993 тем же значением.

Если (1) даёт SUCCESS, а (2) FAIL — проблема между mail-imap-service и
auth-service, а не в пароле. Это и было главной ловушкой приёмки: обе ошибки
выглядели одинаково.

Пароль вводится скрыто, не печатается, не попадает в аргументы командной
строки и в историю PowerShell.
"""

from __future__ import annotations

import argparse
import getpass
import imaplib
import json
import os
import ssl
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
VERDICTS = {"PASSWORD": "основной пароль", "APP_PASSWORD": "пароль приложения"}
# Длина пароля приложения без дефисов; в интерфейсе он показан группами по четыре.
APP_PASSWORD_LENGTH = 20
FORBIDDEN_IN_APP_PASSWORD = "0O1lI"

# Запрос выполняется внутри контейнера alert-relay: он в одной сети с
# auth-service и содержит python с доступом к внутреннему токену.
PROBE = """
import json, os, urllib.request
body = json.dumps({
    "username": os.environ["CORVEX_PROBE_USERNAME"],
    "password": os.environ["CORVEX_PROBE_PASSWORD"],
}).encode("utf-8")
request = urllib.request.Request(
    "http://auth-service:8081/internal/verify-password",
    data=body,
    headers={
        "Content-Type": "application/json",
        "X-Corvex-Internal-Token": os.environ.get("CORVEX_INTERNAL_API_TOKEN", ""),
    },
)
with urllib.request.urlopen(request, timeout=5) as response:
    print(response.read().decode("utf-8"))
"""


def compose_command(project: str | None, tail: list[str]) -> list[str]:
    prefix = ["-p", project] if project else []
    return ["docker", "compose", *prefix, *tail]


def shape_notes_for(password: str) -> list[str]:
    """Что можно сказать о введённом значении, не раскрывая его.

    Пароль приложения — 20 символов из алфавита без 0/O/1/l/I, в интерфейсе
    показывается группами по четыре через дефис. Основной пароль по длине и
    составу обычно не совпадает, и это сразу видно по форме, а не по отказу.
    """
    stripped = password.replace("-", "").replace(" ", "")
    notes: list[str] = []
    if len(stripped) == APP_PASSWORD_LENGTH and stripped.isalnum():
        notes.append("форма: похоже на пароль приложения (20 символов)")
    else:
        notes.append(
            f"форма: {len(stripped)} символов без дефисов — пароль приложения "
            f"состоит из {APP_PASSWORD_LENGTH}"
        )
    if any(character in FORBIDDEN_IN_APP_PASSWORD for character in stripped):
        notes.append("в значении есть 0, O, 1, l или I — в пароле приложения их не бывает")
    return notes


def verdict(answer: dict[str, Any], shape_notes: list[str] | None = None) -> tuple[int, list[str]]:
    notes = list(shape_notes or [])
    if answer.get("valid") is True:
        method = str(answer.get("method") or "")
        return 0, [
            f"SUCCESS: пароль подошёл — {VERDICTS.get(method, method or 'метод не указан')}",
            f"  пользователь: {answer.get('userId') or 'не указан'}",
            "  auth-service подтвердил пароль: IMAP-вход тем же значением должен пройти.",
        ]
    return 1, [
        "FAIL: пароль не подошёл (auth-service вернул valid = false)",
        *[f"  {note}" for note in notes],
        "Проверьте по порядку:",
        "  1. учётка ACTIVE: select username, status, totp_enabled from users;",
        "  2. пароль приложения показывается один раз — если плашку закрыли, нужен новый;",
        "  3. вводите ровно 20 символов; в алфавите нет 0, O, 1, l, I;",
        "  4. при включённой 2FA основной пароль не принимается, нужен пароль приложения.",
    ]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Проверяет, что auth-service подтверждает пароль, и по флагу --imap — "
            "что настоящий IMAP LOGIN проходит. Пароль вводится скрыто и не печатается."
        )
    )
    parser.add_argument("--username", required=True, help="Логин или e-mail")
    parser.add_argument(
        "--project",
        default=os.environ.get("COMPOSE_PROJECT_NAME") or None,
        help="Имя compose-проекта, если стенд поднят с -p",
    )
    parser.add_argument("--imap", action="store_true", help="Дополнительно выполнить настоящий IMAP LOGIN")
    parser.add_argument("--host", default=os.environ.get("CORVEX_TEST_HOST", "mail.company.local"))
    parser.add_argument("--port", type=int, default=993)
    parser.add_argument("--ca-file", help="PEM тестового CA")
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args(argv)
    if args.timeout <= 0:
        parser.error("--timeout должен быть > 0")
    if args.project is not None:
        project = str(args.project).strip()
        if not project or not all(c.isalnum() or c in "-_" for c in project):
            parser.error("--project: допустимы буквы, цифры, дефис и подчёркивание")
        args.project = project
    return args


def prompt_password(username: str) -> str:
    password = os.environ.get("CORVEX_TEST_ALERT_PASSWORD") or getpass.getpass(
        f"Пароль для {username} (скрытый ввод): "
    )
    if not password:
        raise ValueError("Пустой пароль не проверяется")
    return password


def probe_auth_service(args: argparse.Namespace, password: str) -> str:
    """Ответ auth-service на тот же запрос, что делает mail-imap-service."""
    command = compose_command(
        args.project,
        [
            "exec", "-T",
            # Без "=значение": compose возьмёт переменную из окружения процесса.
            # С "=значение" пароль попал бы в список процессов хоста.
            "-e", "CORVEX_PROBE_USERNAME",
            "-e", "CORVEX_PROBE_PASSWORD",
            "alert-relay", "python", "-c", PROBE,
        ],
    )
    environment = dict(os.environ)
    environment["CORVEX_PROBE_USERNAME"] = args.username
    environment["CORVEX_PROBE_PASSWORD"] = password
    try:
        result = subprocess.run(
            command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=args.timeout, check=False, env=environment,
        )
    finally:
        environment.clear()
    if result.returncode != 0:
        detail = (result.stderr.strip() or result.stdout.strip())[:400]
        raise RuntimeError(detail.replace(password, "***"))
    return result.stdout.strip()


def imap_login_check(args: argparse.Namespace, password: str) -> tuple[bool, str]:
    """Настоящий LOGIN тем же значением — то, что делает почтовый клиент."""
    try:
        context = ssl.create_default_context(cafile=args.ca_file)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.check_hostname = True
    except (OSError, ssl.SSLError) as failure:
        return False, f"TLS не настроен: {failure}"
    client = None
    try:
        client = imaplib.IMAP4_SSL(args.host, args.port, ssl_context=context, timeout=10)
        client.login(args.username, password)
        return True, f"{args.host}:{args.port}, TLS {client.sock.version() if client.sock else 'ok'}"
    except imaplib.IMAP4.error as failure:
        text = failure.args[0] if failure.args else failure
        if isinstance(text, bytes):
            text = text.decode("utf-8", "replace")
        return False, str(text)
    except (OSError, ssl.SSLError) as failure:
        return False, f"{failure.__class__.__name__}: {failure}"
    finally:
        if client is not None:
            try:
                client.logout()
            except (imaplib.IMAP4.error, OSError):
                pass


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        password = prompt_password(args.username)
    except ValueError as failure:
        print(failure, file=sys.stderr)
        return 2

    shape_notes = shape_notes_for(password)
    try:
        answer = json.loads(probe_auth_service(args, password))
    except (RuntimeError, json.JSONDecodeError) as failure:
        print(f"ОШИБКА: {failure}", file=sys.stderr)
        print(
            "Проверьте, что стек поднят и имя проекта верное (--project или "
            "COMPOSE_PROJECT_NAME).",
            file=sys.stderr,
        )
        return 2

    code, lines = verdict(answer, shape_notes)
    for line in lines:
        print(line)

    if not args.imap:
        return code

    ok, detail = imap_login_check(args, password)
    if ok:
        print(f"IMAP LOGIN: SUCCESS ({detail})")
        # Оба звена подтвердили одно и то же значение — больше искать нечего.
        return 0 if code == 0 else 1
    print(f"IMAP LOGIN: FAIL — {detail}")
    if code == 0:
        print(
            "  auth-service пароль подтвердил, а IMAP — нет: проблема не в пароле. "
            "Смотрите docker compose logs mail-imap-service: клиент пишет в лог "
            "причину (401/400/недоступность auth-service), а не только «Invalid login»."
        )
    return 1


if __name__ == "__main__":
    sys.exit(main())
