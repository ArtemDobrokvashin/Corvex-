#!/usr/bin/env python3
"""Read-only Corvex SMTP/IMAP/POP3 smoke на стандартных клиентах Python."""

from __future__ import annotations

import argparse
import getpass
import imaplib
import os
import poplib
import re
import smtplib
import ssl
import sys
from contextlib import suppress
from email import policy
from email.parser import BytesParser
from pathlib import Path
from typing import Any

from smoke_common import (
    SkipCheck,
    SmokeError,
    SmokeReport,
    make_ssl_context,
    tls_details,
)

ROOT = Path(__file__).resolve().parent.parent
PROTOCOLS = ("smtp", "imap-starttls", "imap-ssl", "pop3")


def expect_ok(response_type: str, data: Any, operation: str) -> None:
    if response_type != "OK":
        raise SmokeError(f"{operation}: сервер вернул {response_type}")


def message_metrics(raw: bytes) -> dict[str, Any]:
    if not raw:
        raise SmokeError("Сервер вернул пустое тело письма")
    message = BytesParser(policy=policy.default).parsebytes(raw)
    attachments = sum(
        part.get_content_disposition() == "attachment" for part in message.walk()
    )
    return {
        "message_bytes": len(raw),
        "multipart": message.is_multipart(),
        "attachments": attachments,
    }


def imap_message_payload(data: Any) -> bytes:
    chunks = []
    for item in data or []:
        if isinstance(item, tuple) and len(item) >= 2 and isinstance(item[1], bytes):
            chunks.append(item[1])
    payload = b"".join(chunks)
    if not payload:
        raise SmokeError("IMAP FETCH не вернул тело письма")
    return payload


def imap_message_size(data: Any) -> int:
    raw = b" ".join(
        item if isinstance(item, bytes) else item[0]
        for item in (data or [])
        if isinstance(item, bytes) or (isinstance(item, tuple) and item)
    )
    match = re.search(rb"RFC822\.SIZE\s+(\d+)", raw)
    if not match:
        raise SmokeError("IMAP не вернул RFC822.SIZE")
    return int(match.group(1))


def pop_listing(lines: list[bytes]) -> dict[int, int]:
    result = {}
    for line in lines:
        parts = line.split()
        if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
            result[int(parts[0])] = int(parts[1])
    if not result:
        raise SmokeError("POP3 LIST не содержит ни одного сообщения")
    return result


def pop_uidl(lines: list[bytes]) -> dict[int, str]:
    result = {}
    for line in lines:
        parts = line.split(maxsplit=2)
        if len(parts) >= 2 and parts[0].isdigit():
            result[int(parts[0])] = parts[1].decode("ascii", errors="replace")
    if not result:
        raise SmokeError("POP3 UIDL не содержит ни одного сообщения")
    return result


def check_smtp(
    host: str,
    username: str,
    password: str,
    context: ssl.SSLContext,
    timeout: int,
) -> dict[str, Any]:
    client = smtplib.SMTP(host, 587, timeout=timeout)
    try:
        client.ehlo()
        if not client.has_extn("starttls"):
            raise SmokeError("SMTP 587 не анонсирует STARTTLS")
        client.starttls(context=context)
        tls = tls_details(client.sock)
        client.ehlo()
        if not client.has_extn("auth"):
            raise SmokeError("SMTP 587 после STARTTLS не анонсирует AUTH")
        client.login(username, password)
        client.noop()
        code, _ = client.quit()
        if code != 221:
            raise SmokeError(f"SMTP QUIT вернул {code}")
        return {
            **tls,
            "scope": "STARTTLS + AUTH + NOOP + clean QUIT; письмо не отправлялось",
        }
    finally:
        client.close()


def exercise_imap(client: imaplib.IMAP4, max_message_bytes: int) -> dict[str, Any]:
    response_type, data = client.capability()
    expect_ok(response_type, data, "IMAP CAPABILITY")
    response_type, data = client.list()
    expect_ok(response_type, data, "IMAP LIST")
    folders = len([item for item in (data or []) if item])
    if not folders:
        raise SmokeError("IMAP LIST вернул пустой список папок")

    response_type, data = client.select("INBOX", readonly=True)
    expect_ok(response_type, data, "IMAP EXAMINE INBOX")
    response_type, data = client.search(None, "ALL")
    expect_ok(response_type, data, "IMAP SEARCH ALL")
    identifiers = (data or [b""])[0].split()
    if not identifiers:
        raise SkipCheck(
            "INBOX пуст: LOGIN/LIST/EXAMINE/SEARCH проверены, FETCH не выполнялся"
        )
    message_id = identifiers[-1]

    response_type, data = client.fetch(message_id, "(RFC822.SIZE)")
    expect_ok(response_type, data, "IMAP FETCH RFC822.SIZE")
    size = imap_message_size(data)
    if size > max_message_bytes:
        raise SkipCheck(
            f"Последнее письмо занимает {size} байт, больше лимита {max_message_bytes}; FETCH тела пропущен"
        )
    response_type, data = client.fetch(message_id, "(BODY.PEEK[])")
    expect_ok(response_type, data, "IMAP FETCH BODY.PEEK[]")
    metrics = message_metrics(imap_message_payload(data))
    return {"folders": folders, "inbox_messages": len(identifiers), **metrics}


def check_imap(
    host: str,
    username: str,
    password: str,
    context: ssl.SSLContext,
    timeout: int,
    max_message_bytes: int,
    *,
    implicit_tls: bool,
) -> dict[str, Any]:
    client: imaplib.IMAP4 | None = None
    try:
        if implicit_tls:
            client = imaplib.IMAP4_SSL(host, 993, ssl_context=context, timeout=timeout)
            tls = tls_details(client.sock)
        else:
            client = imaplib.IMAP4(host, 143, timeout=timeout)
            response_type, data = client.capability()
            expect_ok(response_type, data, "IMAP CAPABILITY до STARTTLS")
            capabilities = b" ".join(item for item in (data or []) if item).upper()
            if b"STARTTLS" not in capabilities:
                raise SmokeError("IMAP 143 не анонсирует STARTTLS")
            client.starttls(ssl_context=context)
            tls = tls_details(client.sock)
        client.login(username, password)
        details = exercise_imap(client, max_message_bytes)
        client.logout()
        client = None
        return {**tls, **details, "readonly": True}
    finally:
        if client is not None:
            # Best-effort LOGOUT не должен маскировать исходную ошибку проверки.
            with suppress(imaplib.IMAP4.error, OSError):
                client.logout()


def open_pop3(
    host: str,
    username: str,
    password: str,
    context: ssl.SSLContext,
    timeout: int,
) -> tuple[poplib.POP3_SSL, dict[str, str]]:
    client = poplib.POP3_SSL(host, 995, context=context, timeout=timeout)
    try:
        tls = tls_details(client.sock)
        client.user(username)
        client.pass_(password)
        return client, tls
    except Exception:
        client.close()
        raise


def check_pop3(
    host: str,
    username: str,
    password: str,
    context: ssl.SSLContext,
    timeout: int,
    max_message_bytes: int,
) -> dict[str, Any]:
    client, tls = open_pop3(host, username, password, context, timeout)
    second: poplib.POP3_SSL | None = None
    try:
        count, _ = client.stat()
        if not count:
            client.quit()
            raise SkipCheck(
                "INBOX пуст: POP3 AUTH/STAT проверены, LIST/UIDL/RETR не выполнялись"
            )
        listing = pop_listing(client.list()[1])
        identifiers = pop_uidl(client.uidl()[1])
        message_id = max(listing)
        if message_id not in identifiers:
            raise SmokeError("POP3 LIST и UIDL не содержат общего message id")
        if listing[message_id] > max_message_bytes:
            client.quit()
            raise SkipCheck(
                f"Последнее письмо занимает {listing[message_id]} байт, больше лимита {max_message_bytes}; RETR пропущен"
            )
        raw = b"\r\n".join(client.retr(message_id)[1]) + b"\r\n"
        metrics = message_metrics(raw)
        original_uidl = identifiers[message_id]
        client.quit()

        # Вторая сессия подтверждает отсутствие DELE и стабильность UIDL без повторного RETR.
        second, _ = open_pop3(host, username, password, context, timeout)
        second_count, _ = second.stat()
        second_uidl = pop_uidl(second.uidl()[1])
        if second_count != count or second_uidl.get(message_id) != original_uidl:
            raise SmokeError("POP3 count/UIDL изменились между read-only сессиями")
        second.quit()
        second = None
        return {
            **tls,
            **metrics,
            "inbox_messages": count,
            "uidl_stable_after_reconnect": True,
            "dele_sent": False,
        }
    finally:
        client.close()
        if second is not None:
            second.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Read-only SMTP/IMAP/POP3 smoke. Пароль не принимается аргументом; "
            "письма не отправляются, MOVE/DELE/EXPUNGE не выполняются."
        )
    )
    parser.add_argument(
        "--host", default=os.environ.get("CORVEX_TEST_HOST", "mail.company.local")
    )
    parser.add_argument(
        "--username",
        default=os.environ.get("CORVEX_TEST_USERNAME"),
        help="Protocol username; можно задать через CORVEX_TEST_USERNAME",
    )
    parser.add_argument(
        "--ca-file", help="PEM тестового CA; системное доверие используется без флага"
    )
    parser.add_argument(
        "--protocol",
        action="append",
        choices=PROTOCOLS,
        help="Повторяемый фильтр; по умолчанию все",
    )
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--max-message-bytes", type=int, default=10 * 1024 * 1024)
    parser.add_argument(
        "--report", type=Path, help="Путь JSON-отчёта без паролей и содержимого писем"
    )
    args = parser.parse_args()
    if args.timeout <= 0 or args.max_message_bytes <= 0:
        parser.error("--timeout и --max-message-bytes должны быть > 0")
    if not args.username:
        args.username = input("Protocol username: ").strip()
    if not args.username:
        parser.error("Пустой protocol username")
    return args


def main() -> int:
    args = parse_args()
    password = os.environ.get("CORVEX_TEST_PASSWORD") or getpass.getpass(
        "Protocol password (скрытый ввод): "
    )
    if not password:
        print("Пустой пароль не допускается", file=sys.stderr)
        return 2
    try:
        context = make_ssl_context(args.ca_file)
    except (OSError, ssl.SSLError) as exc:
        print(f"Не удалось настроить проверку TLS: {exc}", file=sys.stderr)
        return 2
    selected = args.protocol or list(PROTOCOLS)
    report = SmokeReport("mail-readonly", ROOT)
    for protocol in selected:
        if protocol == "smtp":
            check = lambda: check_smtp(
                args.host, args.username, password, context, args.timeout
            )
        elif protocol == "imap-starttls":
            check = lambda: check_imap(
                args.host,
                args.username,
                password,
                context,
                args.timeout,
                args.max_message_bytes,
                implicit_tls=False,
            )
        elif protocol == "imap-ssl":
            check = lambda: check_imap(
                args.host,
                args.username,
                password,
                context,
                args.timeout,
                args.max_message_bytes,
                implicit_tls=True,
            )
        else:
            check = lambda: check_pop3(
                args.host,
                args.username,
                password,
                context,
                args.timeout,
                args.max_message_bytes,
            )
        report.run(protocol, check)
    if args.report:
        report.write(
            args.report, {"host": args.host, "protocols": selected, "readonly": True}
        )
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())
