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


def verdict(answer: dict[str, Any]) -> tuple[int, list[str]]:
    lines: list[str] = []
    if answer.get("valid") is True:
        method = str(answer.get("method") or "")
        lines.append(f"ПРИНЯТ: {VERDICTS.get(method, method or 'метод не указан')}")
        lines.append("auth-service подтвердил пароль — вход по IMAP не должен ломаться.")
        return 0, lines
    lines.append("ОТКАЗ: auth-service вернул valid = false")
    lines.append("Проверьте по порядку:")
    lines.append("  1. учётка ACTIVE: select username, status, totp_enabled from users;")
    lines.append("  2. пароль приложения действительно тот: list-app-password показывается один раз;")
    lines.append("  3. в алфавите нет символов 0, O, 1, l, I — их нельзя «поправить» на глаз;")
    lines.append("  4. при включённой 2FA основной пароль не принимается, нужен пароль приложения.")
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
        raw = ask_password_with_project(args)
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
    code, lines = verdict(answer)
    for line in lines:
        print(line)
    return code


def ask_password_with_project(args: argparse.Namespace) -> str:
    """Как ask_password, но с именем проекта из аргументов."""
    password = os.environ.get("CORVEX_TEST_ALERT_PASSWORD") or getpass.getpass(
        f"Пароль для {args.username} (скрытый ввод): "
    )
    if not password:
        raise ValueError("Пустой пароль не проверяется")
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
    return result.stdout.strip()


if __name__ == "__main__":
    sys.exit(main())
