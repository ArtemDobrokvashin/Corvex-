#!/usr/bin/env python3
"""Диагностика входа: что auth-service на самом деле отвечает на пароль.

IMAP не отвечает причиной отказа: James возвращает одно и то же
``LOGIN failed. Invalid login/password.`` и на неверный пароль, и на
несуществующего пользователя, и на неактивную учётку. Этот скрипт делает тот же
запрос, что делает mail-imap-service (``POST /internal/verify-password``), но
показывает результат явно — включая метод, которым пароль был принят.

Скрипт только читает: пользователи и сессии не меняются, кроме отметки
``last_used_at`` у пароля приложения, которую ставит сам auth-service при
успешной проверке.

Пароль вводится скрыто и не печатается, не попадает в аргументы командной
строки и в историю PowerShell (он передаётся контейнеру через переменную
окружения только на время запроса).
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
VERDICTS = {"PASSWORD": "основной пароль", "APP_PASSWORD": "пароль приложения"}
# Длина пароля приложения без дефисов; в интерфейсе он показан группами по четыре.
APP_PASSWORD_LENGTH = 20

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
    if any(character in "0O1lI" for character in stripped):
        notes.append("в значении есть 0, O, 1, l или I — в пароле приложения их не бывает")
    return notes


def verdict(answer: dict[str, Any], shape_notes: list[str] | None = None) -> tuple[int, list[str]]:
    notes = list(shape_notes or [])
    if answer.get("valid") is True:
        method = str(answer.get("method") or "")
        lines = [
            f"SUCCESS: пароль подошёл — {VERDICTS.get(method, method or 'метод не указан')}",
            f"  пользователь: {answer.get('userId') or 'не указан'}",
            "  auth-service подтвердил пароль: IMAP-вход тем же значением пройдёт.",
        ]
        return 0, lines
    lines = [
        "FAIL: пароль не подошёл (auth-service вернул valid = false)",
        *[f"  {note}" for note in notes],
        "Проверьте по порядку:",
        "  1. учётка ACTIVE: select username, status, totp_enabled from users;",
        "  2. пароль приложения показывается один раз — если плашку закрыли, нужен новый;",
        "  3. вводите ровно 20 символов; в алфавите нет 0, O, 1, l, I;",
        "  4. при включённой 2FA основной пароль не принимается, нужен пароль приложения.",
    ]
    return 1, lines


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Проверяет, что auth-service отвечает на пароль так же, как увидел бы IMAP. "
            "Только чтение, пароль вводится скрыто и не печатается."
        )
    )
    parser.add_argument("--username", required=True, help="Логин или e-mail")
    parser.add_argument(
        "--project",
        default=os.environ.get("COMPOSE_PROJECT_NAME") or None,
        help="Имя compose-проекта, если стенд поднят с -p",
    )
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


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        raw, shape_notes = ask_password_with_project(args)
        answer = json.loads(raw)
    except ValueError as failure:
        print(f"{failure}", file=sys.stderr)
        return 2
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
    return code


def ask_password_with_project(args: argparse.Namespace) -> tuple[str, list[str]]:
    """Запрашивает пароль и возвращает ответ auth-service и заметки о его форме."""
    password = os.environ.get("CORVEX_TEST_ALERT_PASSWORD") or getpass.getpass(
        f"Пароль для {args.username} (скрытый ввод): "
    )
    if not password:
        raise ValueError("Пустой пароль не проверяется")
    shape_notes = shape_notes_for(password)
    command = compose_command(
        args.project,
        [
            "exec", "-T",
            "-e", f"CORVEX_PROBE_USERNAME={args.username}",
            "-e", f"CORVEX_PROBE_PASSWORD={password}",
            "alert-relay", "python", "-c", PROBE,
        ],
    )
    result = subprocess.run(
        command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace", timeout=args.timeout, check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr.strip() or result.stdout.strip())[:400]
        detail = detail.replace(password, "***")
        raise RuntimeError(detail)
    return result.stdout.strip(), shape_notes


if __name__ == "__main__":
    sys.exit(main())
