#!/usr/bin/env python3
"""Сквозной Corvex SMTP → Sent APPEND → доставка → Sent smoke."""

from __future__ import annotations

import argparse
import getpass
import hashlib
import imaplib
import os
import smtplib
import ssl
import sys
import time
import uuid
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timezone
from email import policy
from email.message import EmailMessage
from email.parser import BytesParser
from email.utils import format_datetime, parseaddr
from pathlib import Path
from typing import Any

from mail_smoke import expect_ok, imap_message_payload
from smoke_common import SmokeError, SmokeReport, make_ssl_context, tls_details

ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class PreparedMessage:
    raw: bytes
    attachment_name: str
    attachment_sha256: str


@dataclass(frozen=True)
class LocatedMessage:
    folder: str
    raw: bytes
    tls: dict[str, str]
    attempts: int


def validate_address(value: str, role: str) -> str:
    address = value.strip()
    if not address or "\r" in address or "\n" in address or len(address) > 320:
        raise SmokeError(f"Некорректный {role} address")
    parsed = parseaddr(address)[1]
    local, separator, domain = address.rpartition("@")
    if parsed != address or not separator or not local or not domain:
        raise SmokeError(f"{role} должен быть plain protocol email без display name")
    return address


def prepare_message(sender: str, recipient: str, run_id: str) -> PreparedMessage:
    if not run_id or "\r" in run_id or "\n" in run_id or " " in run_id:
        raise SmokeError("run_id должен быть непустой строкой без пробелов и CRLF")
    attachment_name = f"corvex-smoke-{run_id}.txt"
    attachment = f"Corvex automated smoke attachment\nrun_id={run_id}\n".encode()
    message = EmailMessage(policy=policy.SMTP)
    message["From"] = sender
    message["To"] = recipient
    message["Subject"] = f"Corvex automated smoke {run_id}"
    message["Message-ID"] = f"<corvex-smoke-{run_id}@{sender.rpartition('@')[2]}>"
    message["Date"] = format_datetime(datetime.now(timezone.utc))  # noqa: UP017 — Python 3.10.
    message["X-Corvex-Smoke-ID"] = run_id
    message.set_content(
        f"Corvex automated smoke\nrun_id={run_id}\n",
        subtype="plain",
        charset="utf-8",
    )
    message.add_alternative(
        f"<html><body><p>Corvex automated smoke</p><p>run_id={run_id}</p></body></html>",
        subtype="html",
        charset="utf-8",
    )
    message.add_attachment(
        attachment,
        maintype="application",
        subtype="octet-stream",
        filename=attachment_name,
    )
    return PreparedMessage(
        raw=message.as_bytes(policy=policy.SMTP),
        attachment_name=attachment_name,
        attachment_sha256=hashlib.sha256(attachment).hexdigest(),
    )


def send_message(
    host: str,
    sender: str,
    password: str,
    recipient: str,
    prepared: PreparedMessage,
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
        client.login(sender, password)
        refused = client.sendmail(sender, [recipient], prepared.raw)
        if refused:
            raise SmokeError("SMTP отклонил локального получателя")
        code, _ = client.quit()
        if code != 221:
            raise SmokeError(f"SMTP QUIT вернул {code}")
        return {
            **tls,
            "accepted_recipients": 1,
            "message_bytes": len(prepared.raw),
            "attachment_sha256": prepared.attachment_sha256,
        }
    finally:
        client.close()


def open_imap(
    host: str,
    username: str,
    password: str,
    context: ssl.SSLContext,
    timeout: int,
) -> tuple[imaplib.IMAP4_SSL, dict[str, str]]:
    client = imaplib.IMAP4_SSL(host, 993, ssl_context=context, timeout=timeout)
    try:
        tls = tls_details(client.sock)
        client.login(username, password)
        return client, tls
    except Exception:
        # Сохраняем исходную ошибку TLS/AUTH, а не возможную ошибку CLOSE в неподходящем состоянии.
        with suppress(imaplib.IMAP4.error, OSError):
            client.logout()
        raise


def append_sent(
    host: str,
    sender: str,
    password: str,
    prepared: PreparedMessage,
    context: ssl.SSLContext,
    timeout: int,
    sent_folder: str,
) -> dict[str, Any]:
    client: imaplib.IMAP4 | None = None
    try:
        client, tls = open_imap(host, sender, password, context, timeout)
        response_type, data = client.append(sent_folder, "(\\Seen)", None, prepared.raw)
        expect_ok(response_type, data, "IMAP APPEND Sent")
        client.logout()
        client = None
        return {**tls, "folder": sent_folder, "message_bytes": len(prepared.raw)}
    finally:
        if client is not None:
            with suppress(imaplib.IMAP4.error, OSError):
                client.logout()


def find_run_message(
    client: imaplib.IMAP4, folders: list[str], run_id: str
) -> tuple[str, bytes] | None:
    for folder in folders:
        response_type, data = client.select(folder, readonly=True)
        if response_type == "NO":
            continue
        expect_ok(response_type, data, f"IMAP EXAMINE {folder}")
        response_type, data = client.uid(
            "SEARCH", "HEADER", "X-Corvex-Smoke-ID", run_id
        )
        expect_ok(response_type, data, "IMAP UID SEARCH run_id")
        identifiers = (data or [b""])[0].split()
        if not identifiers:
            continue
        if len(identifiers) != 1:
            raise SmokeError(
                f"Найдено {len(identifiers)} письма с тем же run_id: обнаружены дубли"
            )
        response_type, data = client.uid("FETCH", identifiers[0], "(BODY.PEEK[])")
        expect_ok(response_type, data, "IMAP UID FETCH BODY.PEEK[]")
        return folder, imap_message_payload(data)
    return None


def wait_for_message(
    host: str,
    username: str,
    password: str,
    folders: list[str],
    run_id: str,
    context: ssl.SSLContext,
    timeout: int,
    wait_seconds: int,
    poll_seconds: int,
) -> LocatedMessage:
    deadline = time.monotonic() + wait_seconds
    attempts = 0
    while True:
        attempts += 1
        client: imaplib.IMAP4 | None = None
        try:
            client, tls = open_imap(host, username, password, context, timeout)
            found = find_run_message(client, folders, run_id)
        finally:
            if client is not None:
                with suppress(imaplib.IMAP4.error, OSError):
                    client.logout()
        if found:
            folder, raw = found
            return LocatedMessage(folder=folder, raw=raw, tls=tls, attempts=attempts)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(poll_seconds, remaining))
    raise SmokeError(
        f"Письмо с заданным run_id не найдено в {', '.join(folders)} "
        f"за {wait_seconds} с; попыток: {attempts}"
    )


def text_content(message: Any, content_type: str) -> str:
    chunks = []
    for part in message.walk():
        if (
            part.get_content_type() != content_type
            or part.get_content_disposition() == "attachment"
        ):
            continue
        payload = part.get_payload(decode=True) or b""
        charset = part.get_content_charset() or "utf-8"
        chunks.append(payload.decode(charset, errors="replace"))
    return "\n".join(chunks)


def verify_message(
    located: LocatedMessage,
    run_id: str,
    sender: str,
    recipient: str,
    prepared: PreparedMessage,
) -> dict[str, Any]:
    message = BytesParser(policy=policy.default).parsebytes(located.raw)
    if message.get("X-Corvex-Smoke-ID") != run_id:
        raise SmokeError("X-Corvex-Smoke-ID не совпадает")
    if parseaddr(message.get("From", ""))[1].lower() != sender.lower():
        raise SmokeError("From не совпадает с отправителем")
    if parseaddr(message.get("To", ""))[1].lower() != recipient.lower():
        raise SmokeError("To не совпадает с получателем")
    if run_id not in message.get("Subject", ""):
        raise SmokeError("Subject не содержит run_id")
    if run_id not in text_content(message, "text/plain"):
        raise SmokeError("text/plain не содержит run_id")
    if run_id not in text_content(message, "text/html"):
        raise SmokeError("text/html не содержит run_id")

    attachments = [
        part
        for part in message.walk()
        if part.get_content_disposition() == "attachment"
    ]
    if len(attachments) != 1:
        raise SmokeError(f"Ожидалось одно вложение, получено: {len(attachments)}")
    attachment = attachments[0]
    if attachment.get_filename() != prepared.attachment_name:
        raise SmokeError("Имя вложения не совпадает")
    payload = attachment.get_payload(decode=True) or b""
    actual_sha256 = hashlib.sha256(payload).hexdigest()
    if actual_sha256 != prepared.attachment_sha256:
        raise SmokeError("SHA-256 вложения не совпадает")
    return {
        **located.tls,
        "folder": located.folder,
        "attempts": located.attempts,
        "message_bytes": len(located.raw),
        "attachment_bytes": len(payload),
        "attachment_sha256": actual_sha256,
        "text_html_verified": True,
        "duplicates": 0,
    }


def check_delivery(
    host: str,
    username: str,
    password: str,
    folders: list[str],
    run_id: str,
    sender: str,
    recipient: str,
    prepared: PreparedMessage,
    context: ssl.SSLContext,
    timeout: int,
    wait_seconds: int,
    poll_seconds: int,
) -> dict[str, Any]:
    located = wait_for_message(
        host,
        username,
        password,
        folders,
        run_id,
        context,
        timeout,
        wait_seconds,
        poll_seconds,
    )
    return verify_message(located, run_id, sender, recipient, prepared)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Отправляет одно тестовое письмо с вложением, делает IMAP APPEND в Sent и проверяет "
            "INBOX получателя + Sent отправителя. Существующие письма не изменяются и не удаляются."
        )
    )
    parser.add_argument(
        "--host", default=os.environ.get("CORVEX_TEST_HOST", "mail.company.local")
    )
    parser.add_argument("--sender", required=True, help="Protocol email отправителя")
    parser.add_argument("--recipient", required=True, help="Protocol email получателя")
    parser.add_argument("--sent-folder", default="Sent")
    parser.add_argument(
        "--ca-file", help="PEM тестового CA; системное доверие используется без флага"
    )
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--wait-seconds", type=int, default=120)
    parser.add_argument("--poll-seconds", type=int, default=5)
    parser.add_argument(
        "--report", type=Path, help="JSON-отчёт без паролей и содержимого письма"
    )
    args = parser.parse_args()
    if args.timeout <= 0 or args.wait_seconds <= 0 or args.poll_seconds <= 0:
        parser.error("--timeout, --wait-seconds и --poll-seconds должны быть > 0")
    if not args.sent_folder or "\r" in args.sent_folder or "\n" in args.sent_folder:
        parser.error("Некорректный --sent-folder")
    try:
        args.sender = validate_address(args.sender, "sender")
        args.recipient = validate_address(args.recipient, "recipient")
    except SmokeError as exc:
        parser.error(str(exc))
    return args


def main() -> int:
    args = parse_args()
    sender_password = os.environ.get("CORVEX_TEST_SENDER_PASSWORD") or getpass.getpass(
        "Sender protocol password (скрытый ввод): "
    )
    recipient_password = os.environ.get(
        "CORVEX_TEST_RECIPIENT_PASSWORD"
    ) or getpass.getpass("Recipient protocol password (скрытый ввод): ")
    if not sender_password or not recipient_password:
        print("Пустые пароли не допускаются", file=sys.stderr)
        return 2
    try:
        context = make_ssl_context(args.ca_file)
    except (OSError, ssl.SSLError) as exc:
        print(f"Не удалось настроить проверку TLS: {exc}", file=sys.stderr)
        return 2

    run_id = uuid.uuid4().hex
    prepared = prepare_message(args.sender, args.recipient, run_id)
    report = SmokeReport("mail-e2e", ROOT)
    print(
        f"Run ID: {run_id}; тестовые копии сохраняются для последующего persistence-smoke",
        flush=True,
    )
    sent = report.run(
        "smtp:send",
        lambda: send_message(
            args.host,
            args.sender,
            sender_password,
            args.recipient,
            prepared,
            context,
            args.timeout,
        ),
    )
    if not sent:
        for name in ("imap:sent-append", "imap:recipient-inbox", "imap:sender-sent"):
            report.add(name, "SKIP", error="не выполнялось: SMTP send не принят")
    else:
        appended = report.run(
            "imap:sent-append",
            lambda: append_sent(
                args.host,
                args.sender,
                sender_password,
                prepared,
                context,
                args.timeout,
                args.sent_folder,
            ),
        )
        report.run(
            "imap:recipient-inbox",
            lambda: check_delivery(
                args.host,
                args.recipient,
                recipient_password,
                ["INBOX"],
                run_id,
                args.sender,
                args.recipient,
                prepared,
                context,
                args.timeout,
                args.wait_seconds,
                args.poll_seconds,
            ),
        )
        if appended:
            report.run(
                "imap:sender-sent",
                lambda: check_delivery(
                    args.host,
                    args.sender,
                    sender_password,
                    [args.sent_folder],
                    run_id,
                    args.sender,
                    args.recipient,
                    prepared,
                    context,
                    args.timeout,
                    args.wait_seconds,
                    args.poll_seconds,
                ),
            )
        else:
            report.add(
                "imap:sender-sent",
                "SKIP",
                error="не выполнялось: IMAP APPEND не подтверждён",
            )
    if args.report:
        report.write(
            args.report,
            {
                "host": args.host,
                "run_id": run_id,
                "attachment_sha256": prepared.attachment_sha256,
                "test_artifacts_retained": sent,
            },
        )
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())
