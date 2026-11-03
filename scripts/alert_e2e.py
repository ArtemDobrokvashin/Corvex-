#!/usr/bin/env python3
"""Контролируемый Alertmanager → notification-service → email → IMAP smoke."""

from __future__ import annotations

import argparse
import getpass
import imaplib
import os
import ssl
import subprocess
import sys
import time
import uuid
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from email import policy
from email.parser import BytesParser
from pathlib import Path
from typing import Any

from mail_e2e import open_imap, text_content, validate_address
from mail_smoke import expect_ok, imap_message_payload
from smoke_common import SmokeError, SmokeReport, make_ssl_context

ROOT = Path(__file__).resolve().parent.parent
ALERT_NAME = "CorvexAlertSmoke"
UTC = timezone.utc  # noqa: UP017 — Python 3.10 compatibility.


@dataclass(frozen=True)
class LocatedAlert:
    raw: bytes
    tls: dict[str, str]
    attempts: int


def compose_exec(service: str, arguments: list[str], timeout: int) -> str:
    command = ["docker", "compose", "exec", "-T", service, *arguments]
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise SmokeError(f"docker compose exec {service}: {exc}") from exc
    if result.returncode != 0:
        detail = (result.stderr.strip() or result.stdout.strip())[:800]
        raise SmokeError(
            f"docker compose exec {service} завершился с {result.returncode}: {detail}"
        )
    return result.stdout.strip()


def check_configured_recipient(username: str, timeout: int) -> dict[str, Any]:
    value = compose_exec(
        "notification-service",
        ["printenv", "CORVEX_ALERT_RECIPIENTS"],
        timeout,
    )
    recipients = {item.strip().lower() for item in value.split(",") if item.strip()}
    if username.lower() not in recipients:
        raise SmokeError(
            f"{username} не входит в CORVEX_ALERT_RECIPIENTS; "
            "alert не создан. Укажите ящик из этой настройки или обновите её в .env"
        )
    return {
        "mailbox_in_alert_recipients": True,
        "configured_recipients": len(recipients),
    }


def rfc3339(value: datetime) -> str:
    return (
        value.astimezone(UTC)
        .isoformat(timespec="seconds")
        .replace("+00:00", "Z")
    )


def set_alert(run_id: str, ends_at: datetime, timeout: int) -> dict[str, Any]:
    if not run_id or any(character in run_id for character in "\r\n ="):
        raise SmokeError("run_id содержит недопустимые символы")
    expiry = rfc3339(ends_at)
    compose_exec(
        "alertmanager",
        [
            "amtool",
            "--alertmanager.url=http://127.0.0.1:9093",
            "alert",
            "add",
            f"alertname={ALERT_NAME}",
            "severity=warning",
            f"instance=alert-smoke-{run_id}",
            f"corvex_smoke_id={run_id}",
            f"--annotation=summary=Corvex alert smoke {run_id}",
            f"--annotation=description=Controlled monitoring alert {run_id}",
            f"--end={expiry}",
        ],
        timeout,
    )
    return {"alertname": ALERT_NAME, "run_id": run_id, "ends_at": expiry}


def submit_alert(run_id: str, wait_seconds: int, timeout: int) -> dict[str, Any]:
    # Даже при прерванном скрипте алерт не останется активным бессрочно.
    lifetime = max(900, wait_seconds + 120)
    return set_alert(
        run_id,
        datetime.now(UTC) + timedelta(seconds=lifetime),
        timeout,
    )


def resolve_alert(run_id: str, timeout: int) -> dict[str, Any]:
    return set_alert(run_id, datetime.now(UTC), timeout)


def find_alert_message(client: Any, run_id: str) -> bytes | None:
    response_type, data = client.select("INBOX", readonly=True)
    expect_ok(response_type, data, "IMAP EXAMINE INBOX")
    response_type, data = client.uid("SEARCH", "HEADER", "Subject", run_id)
    expect_ok(response_type, data, "IMAP UID SEARCH alert run_id")
    identifiers = (data or [b""])[0].split()
    if not identifiers:
        return None
    if len(identifiers) != 1:
        raise SmokeError(
            f"Найдено {len(identifiers)} писем с тем же alert run_id: обнаружены дубли"
        )
    response_type, data = client.uid("FETCH", identifiers[0], "(BODY.PEEK[])")
    expect_ok(response_type, data, "IMAP UID FETCH BODY.PEEK[]")
    raw = imap_message_payload(data)
    message = BytesParser(policy=policy.default).parsebytes(raw)
    expected_subject = f"[Corvex warning] {ALERT_NAME} (alert-smoke-{run_id})"
    if message.get("Subject", "") != expected_subject:
        raise SmokeError(
            "Subject alert-письма не совпадает: проверьте alertname, severity и instance"
        )
    if run_id not in text_content(message, "text/plain"):
        raise SmokeError("text/plain alert-письма не содержит run_id")
    return raw


def wait_for_alert(
    host: str,
    username: str,
    password: str,
    run_id: str,
    context: ssl.SSLContext,
    timeout: int,
    wait_seconds: int,
    poll_seconds: int,
) -> LocatedAlert:
    deadline = time.monotonic() + wait_seconds
    attempts = 0
    while True:
        attempts += 1
        client = None
        try:
            client, tls = open_imap(host, username, password, context, timeout)
            raw = find_alert_message(client, run_id)
        finally:
            if client is not None:
                with suppress(imaplib.IMAP4.error, OSError):
                    client.logout()
        if raw:
            return LocatedAlert(raw=raw, tls=tls, attempts=attempts)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(poll_seconds, remaining))
    raise SmokeError(
        f"Alert-письмо с run_id не найдено в INBOX за {wait_seconds} с; "
        f"попыток: {attempts}. Проверьте логи alertmanager и notification-service"
    )


def check_delivery(
    host: str,
    username: str,
    password: str,
    run_id: str,
    context: ssl.SSLContext,
    timeout: int,
    wait_seconds: int,
    poll_seconds: int,
) -> dict[str, Any]:
    located = wait_for_alert(
        host,
        username,
        password,
        run_id,
        context,
        timeout,
        wait_seconds,
        poll_seconds,
    )
    return {
        **located.tls,
        "folder": "INBOX",
        "attempts": located.attempts,
        "message_bytes": len(located.raw),
        "subject_and_body_verified": True,
        "duplicates": 0,
        "readonly": True,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Создаёт один synthetic alert через amtool, проверяет доставку email по IMAP "
            "и завершает alert. Сервисы не останавливаются; существующие письма не изменяются."
        )
    )
    parser.add_argument(
        "--host", default=os.environ.get("CORVEX_TEST_HOST", "mail.company.local")
    )
    parser.add_argument(
        "--username",
        required=True,
        help="Ящик из CORVEX_ALERT_RECIPIENTS; пароль будет запрошен скрыто",
    )
    parser.add_argument(
        "--ca-file", help="PEM тестового CA; системное доверие используется без флага"
    )
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--wait-seconds", type=int, default=180)
    parser.add_argument("--poll-seconds", type=int, default=5)
    parser.add_argument(
        "--report", type=Path, help="JSON-отчёт без пароля и содержимого письма"
    )
    args = parser.parse_args()
    if args.timeout <= 0 or args.wait_seconds <= 0 or args.poll_seconds <= 0:
        parser.error("--timeout, --wait-seconds и --poll-seconds должны быть > 0")
    try:
        args.username = validate_address(args.username, "username")
    except SmokeError as exc:
        parser.error(str(exc))
    return args


def main() -> int:
    args = parse_args()
    password = os.environ.get("CORVEX_TEST_ALERT_PASSWORD") or getpass.getpass(
        "Alert recipient protocol password (скрытый ввод): "
    )
    if not password:
        print("Пустой пароль не допускается", file=sys.stderr)
        return 2
    try:
        context = make_ssl_context(args.ca_file)
    except (OSError, ssl.SSLError) as exc:
        print(f"Не удалось настроить проверку TLS: {exc}", file=sys.stderr)
        return 2

    run_id = uuid.uuid4().hex
    report = SmokeReport("alert-e2e", ROOT)
    delivered = False
    print(f"Run ID: {run_id}; synthetic alert будет автоматически завершён", flush=True)
    configured = report.run(
        "alert:recipient-config",
        lambda: check_configured_recipient(args.username, args.timeout),
    )
    if not configured:
        for name in ("alertmanager:submit", "imap:alert-email", "alertmanager:resolve"):
            report.add(name, "SKIP", error="не выполнялось: получатель не настроен")
    else:
        submitted = report.run(
            "alertmanager:submit",
            lambda: submit_alert(run_id, args.wait_seconds, args.timeout),
        )
        if submitted:
            delivered = report.run(
                "imap:alert-email",
                lambda: check_delivery(
                    args.host,
                    args.username,
                    password,
                    run_id,
                    context,
                    args.timeout,
                    args.wait_seconds,
                    args.poll_seconds,
                ),
            )
            # Выполняется и после FAIL доставки, чтобы не оставить тестовый alert активным.
            report.run(
                "alertmanager:resolve",
                lambda: resolve_alert(run_id, args.timeout),
            )
        else:
            for name in ("imap:alert-email", "alertmanager:resolve"):
                report.add(name, "SKIP", error="не выполнялось: alert не создан")
    if args.report:
        report.write(
            args.report,
            {
                "host": args.host,
                "run_id": run_id,
                "imap_readonly": True,
                "test_email_retained": delivered,
            },
        )
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())
