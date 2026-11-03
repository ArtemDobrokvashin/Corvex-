#!/usr/bin/env python3
"""Guarded Corvex IMAP MOVE/flags/restart/persistence/UID EXPUNGE smoke."""

from __future__ import annotations

import argparse
import getpass
import imaplib
import os
import re
import ssl
import sys
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mail_e2e import (
    LocatedMessage,
    open_imap,
    prepare_message,
    validate_address,
    verify_message,
)
from mail_smoke import expect_ok, imap_message_payload
from runtime_smoke import check_service, run_command
from smoke_common import SmokeError, SmokeReport, make_ssl_context

ROOT = Path(__file__).resolve().parent.parent
RESTARTABLE = {"mail-storage-service", "mail-imap-service"}


@dataclass(frozen=True)
class FoundMessage:
    folder: str
    uid: int
    raw: bytes
    flags: frozenset[str]
    exists: int


def validate_run_id(value: str) -> str:
    run_id = value.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{32}", run_id):
        raise SmokeError(
            "run_id должен содержать ровно 32 hex-символа из завершённого mail_e2e"
        )
    return run_id


def temp_folder(run_id: str) -> str:
    return f"Corvex-Smoke-{run_id}"


def select_folder(client: imaplib.IMAP4, folder: str, *, readonly: bool) -> int:
    response_type, data = client.select(folder, readonly=readonly)
    expect_ok(response_type, data, f"IMAP SELECT {folder}")
    try:
        return int((data or [b"0"])[0] or b"0")
    except (TypeError, ValueError) as exc:
        raise SmokeError(f"IMAP SELECT {folder} вернул некорректный EXISTS") from exc


def search_run_uid(
    client: imaplib.IMAP4, run_id: str, *, flagged: bool = False
) -> int | None:
    arguments: list[Any] = ["SEARCH", "HEADER", "X-Corvex-Smoke-ID", run_id]
    if flagged:
        arguments.append("FLAGGED")
    response_type, data = client.uid(*arguments)
    expect_ok(response_type, data, "IMAP UID SEARCH run_id")
    identifiers = (data or [b""])[0].split()
    if not identifiers:
        return None
    if len(identifiers) != 1:
        raise SmokeError(
            f"Найдено {len(identifiers)} письма с тем же run_id: обнаружены дубли"
        )
    return int(identifiers[0])


def fetch_raw(client: imaplib.IMAP4, uid: int) -> bytes:
    response_type, data = client.uid("FETCH", str(uid), "(BODY.PEEK[])")
    expect_ok(response_type, data, "IMAP UID FETCH BODY.PEEK[]")
    return imap_message_payload(data)


def fetch_flags(client: imaplib.IMAP4, uid: int) -> frozenset[str]:
    response_type, data = client.uid("FETCH", str(uid), "(FLAGS)")
    expect_ok(response_type, data, "IMAP UID FETCH FLAGS")
    chunks = []
    for item in data or []:
        if isinstance(item, bytes):
            chunks.append(item)
        elif isinstance(item, tuple) and item and isinstance(item[0], bytes):
            chunks.append(item[0])
    raw = b" ".join(chunks)
    return frozenset(flag.decode("ascii") for flag in re.findall(rb"\\[A-Za-z]+", raw))


def find_message(
    client: imaplib.IMAP4,
    folder: str,
    run_id: str,
    *,
    readonly: bool,
) -> FoundMessage | None:
    exists = select_folder(client, folder, readonly=readonly)
    uid = search_run_uid(client, run_id)
    if uid is None:
        return None
    return FoundMessage(
        folder=folder,
        uid=uid,
        raw=fetch_raw(client, uid),
        flags=fetch_flags(client, uid),
        exists=exists,
    )


def require_message(
    client: imaplib.IMAP4,
    folder: str,
    run_id: str,
    *,
    readonly: bool,
) -> FoundMessage:
    found = find_message(client, folder, run_id, readonly=readonly)
    if found is None:
        raise SmokeError(f"Тестовое письмо run_id не найдено в {folder}")
    return found


def verified_details(
    found: FoundMessage,
    run_id: str,
    sender: str,
    recipient: str,
    prepared: Any,
    tls: dict[str, str],
) -> dict[str, Any]:
    located = LocatedMessage(folder=found.folder, raw=found.raw, tls=tls, attempts=1)
    details = verify_message(located, run_id, sender, recipient, prepared)
    return {
        **details,
        "uid": found.uid,
        "flags": sorted(found.flags),
        "folder_exists": found.exists,
    }


def check_existing(
    host: str,
    username: str,
    password: str,
    folder: str,
    run_id: str,
    sender: str,
    recipient: str,
    prepared: Any,
    context: ssl.SSLContext,
    timeout: int,
) -> dict[str, Any]:
    client: imaplib.IMAP4 | None = None
    try:
        client, tls = open_imap(host, username, password, context, timeout)
        found = require_message(client, folder, run_id, readonly=True)
        return verified_details(found, run_id, sender, recipient, prepared, tls)
    finally:
        if client is not None:
            with suppress(imaplib.IMAP4.error, OSError):
                client.logout()


def move_to_temp(
    host: str,
    username: str,
    password: str,
    folder: str,
    run_id: str,
    sender: str,
    recipient: str,
    prepared: Any,
    context: ssl.SSLContext,
    timeout: int,
) -> dict[str, Any]:
    client: imaplib.IMAP4 | None = None
    try:
        client, tls = open_imap(host, username, password, context, timeout)
        response_type, data = client.create(folder)
        expect_ok(response_type, data, "IMAP CREATE temp folder")
        source = require_message(client, "INBOX", run_id, readonly=False)
        response_type, data = client.uid("MOVE", str(source.uid), folder)
        expect_ok(response_type, data, "IMAP UID MOVE")
        moved = require_message(client, folder, run_id, readonly=True)
        details = verified_details(moved, run_id, sender, recipient, prepared, tls)
        if find_message(client, "INBOX", run_id, readonly=True) is not None:
            raise SmokeError("После UID MOVE исходная копия осталась в INBOX")
        return {**details, "source_uid": source.uid, "destination_uid": moved.uid}
    finally:
        if client is not None:
            with suppress(imaplib.IMAP4.error, OSError):
                client.logout()


def flag_search_reconnect(
    host: str,
    username: str,
    password: str,
    folder: str,
    run_id: str,
    sender: str,
    recipient: str,
    prepared: Any,
    context: ssl.SSLContext,
    timeout: int,
) -> dict[str, Any]:
    first: imaplib.IMAP4 | None = None
    second: imaplib.IMAP4 | None = None
    try:
        first, first_tls = open_imap(host, username, password, context, timeout)
        found = require_message(first, folder, run_id, readonly=False)
        response_type, data = first.uid(
            "STORE", str(found.uid), "+FLAGS", "(\\Flagged)"
        )
        expect_ok(response_type, data, "IMAP UID STORE Flagged")
        flagged_uid = search_run_uid(first, run_id, flagged=True)
        if flagged_uid != found.uid:
            raise SmokeError("FLAGGED SEARCH не вернул UID тестового письма")
        if "\\Flagged" not in fetch_flags(first, found.uid):
            raise SmokeError("Флаг \\Flagged не сохранился после UID STORE")
        first.logout()
        first = None

        second, second_tls = open_imap(host, username, password, context, timeout)
        reconnected = require_message(second, folder, run_id, readonly=True)
        if "\\Flagged" not in reconnected.flags:
            raise SmokeError("Флаг \\Flagged потерян после reconnect")
        if reconnected.uid != found.uid:
            raise SmokeError("UID тестового письма изменился после reconnect")
        details = verified_details(
            reconnected, run_id, sender, recipient, prepared, second_tls
        )
        second.logout()
        second = None
        return {**details, "first_tls": first_tls, "reconnect_verified": True}
    finally:
        for client in (first, second):
            if client is not None:
                with suppress(imaplib.IMAP4.error, OSError):
                    client.logout()


def restart_service(service: str, wait_seconds: int, timeout: int) -> dict[str, Any]:
    if service not in RESTARTABLE:
        raise SmokeError(f"Запрещённый restart target: {service}")
    run_command(["docker", "compose", "restart", "--timeout", "30", service], 180)
    state = check_service(service, wait_seconds, timeout)
    return {"service": service, **state}


def check_persistence(
    host: str,
    username: str,
    password: str,
    folder: str,
    run_id: str,
    sender: str,
    recipient: str,
    prepared: Any,
    context: ssl.SSLContext,
    timeout: int,
    expected_uid: int,
    required_flag: str | None = None,
) -> dict[str, Any]:
    details = check_existing(
        host,
        username,
        password,
        folder,
        run_id,
        sender,
        recipient,
        prepared,
        context,
        timeout,
    )
    if details["uid"] != expected_uid:
        raise SmokeError(
            f"UID после restart изменился: было {expected_uid}, стало {details['uid']}"
        )
    if required_flag and required_flag not in details["flags"]:
        raise SmokeError(f"Флаг {required_flag} потерян после restart")
    return {**details, "restart_persistence_verified": True}


def cleanup_temp_folder(
    host: str,
    username: str,
    password: str,
    folder: str,
    run_id: str,
    context: ssl.SSLContext,
    timeout: int,
    expected_uid: int,
) -> dict[str, Any]:
    client: imaplib.IMAP4 | None = None
    try:
        client, _ = open_imap(host, username, password, context, timeout)
        exists = select_folder(client, folder, readonly=False)
        if exists != 1:
            raise SmokeError(
                f"Отказ UID EXPUNGE: в temp folder {exists} сообщений вместо одного"
            )
        found = require_message(client, folder, run_id, readonly=False)
        if found.uid != expected_uid or found.exists != 1:
            raise SmokeError("Отказ UID EXPUNGE: тестовый UID/EXISTS не совпадает")
        response_type, data = client.uid(
            "STORE", str(found.uid), "+FLAGS", "(\\Deleted)"
        )
        expect_ok(response_type, data, "IMAP UID STORE Deleted")
        # Только UID EXPUNGE конкретного тестового UID; общий EXPUNGE не используется.
        response_type, data = client.uid("EXPUNGE", str(found.uid))
        expect_ok(response_type, data, "IMAP UID EXPUNGE")
        remaining = select_folder(client, folder, readonly=False)
        if remaining != 0:
            raise SmokeError(
                f"После UID EXPUNGE в temp folder осталось {remaining} сообщений"
            )
        select_folder(client, "INBOX", readonly=True)
        response_type, data = client.delete(folder)
        expect_ok(response_type, data, "IMAP DELETE empty temp folder")
        return {
            "expunged_uid": found.uid,
            "temp_folder_deleted": True,
            "preexisting_mail_touched": False,
        }
    finally:
        if client is not None:
            with suppress(imaplib.IMAP4.error, OSError):
                client.logout()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Без флагов: только precheck сохранённых e2e-артефактов. Для полного MOVE/flags/"
            "restart/UID EXPUNGE нужны одновременно --allow-mutating и --allow-restart."
        )
    )
    parser.add_argument(
        "--host", default=os.environ.get("CORVEX_TEST_HOST", "mail.company.local")
    )
    parser.add_argument("--sender", required=True)
    parser.add_argument("--recipient", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--ca-file", help="PEM тестового CA")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--wait-seconds", type=int, default=120)
    parser.add_argument("--allow-mutating", action="store_true")
    parser.add_argument("--allow-restart", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.timeout <= 0 or args.wait_seconds <= 0:
        parser.error("--timeout и --wait-seconds должны быть > 0")
    if args.allow_mutating != args.allow_restart:
        parser.error(
            "Полный режим требует одновременно --allow-mutating и --allow-restart; без флагов только precheck"
        )
    try:
        args.sender = validate_address(args.sender, "sender")
        args.recipient = validate_address(args.recipient, "recipient")
        args.run_id = validate_run_id(args.run_id)
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

    run_id = args.run_id
    folder = temp_folder(run_id)
    prepared = prepare_message(args.sender, args.recipient, run_id)
    full_mode = args.allow_mutating and args.allow_restart
    report = SmokeReport(
        "mail-persistence" if full_mode else "mail-persistence-precheck", ROOT
    )
    state: dict[str, Any] = {}
    print(
        f"Run ID: {run_id}; mode={'full' if full_mode else 'precheck'}; temp folder={folder}",
        flush=True,
    )

    def recipient_precheck() -> dict[str, Any]:
        state["recipient"] = check_existing(
            args.host,
            args.recipient,
            recipient_password,
            "INBOX",
            run_id,
            args.sender,
            args.recipient,
            prepared,
            context,
            args.timeout,
        )
        return state["recipient"]

    def sender_precheck() -> dict[str, Any]:
        state["sender"] = check_existing(
            args.host,
            args.sender,
            sender_password,
            "Sent",
            run_id,
            args.sender,
            args.recipient,
            prepared,
            context,
            args.timeout,
        )
        return state["sender"]

    recipient_ok = report.run("precheck:recipient-inbox", recipient_precheck)
    sender_ok = report.run("precheck:sender-sent", sender_precheck)
    if full_mode and recipient_ok and sender_ok:
        moved = report.run(
            "imap:move-to-temp",
            lambda: move_to_temp(
                args.host,
                args.recipient,
                recipient_password,
                folder,
                run_id,
                args.sender,
                args.recipient,
                prepared,
                context,
                args.timeout,
            ),
        )
        if moved:

            def flag_step() -> dict[str, Any]:
                details = flag_search_reconnect(
                    args.host,
                    args.recipient,
                    recipient_password,
                    folder,
                    run_id,
                    args.sender,
                    args.recipient,
                    prepared,
                    context,
                    args.timeout,
                )
                state["recipient_persistence_uid"] = details["uid"]
                return details

            flagged = report.run("imap:flag-search-reconnect", flag_step)
        else:
            flagged = False
        if flagged:
            storage_restarted = report.run(
                "restart:mail-storage-service",
                lambda: restart_service(
                    "mail-storage-service", args.wait_seconds, args.timeout
                ),
            )
        else:
            storage_restarted = False
        if storage_restarted:
            imap_restarted = report.run(
                "restart:mail-imap-service",
                lambda: restart_service(
                    "mail-imap-service", args.wait_seconds, args.timeout
                ),
            )
        else:
            imap_restarted = False
        if imap_restarted:
            recipient_uid = state["recipient_persistence_uid"]
            recipient_persistent = report.run(
                "persistence:recipient-moved",
                lambda: check_persistence(
                    args.host,
                    args.recipient,
                    recipient_password,
                    folder,
                    run_id,
                    args.sender,
                    args.recipient,
                    prepared,
                    context,
                    args.timeout,
                    recipient_uid,
                    "\\Flagged",
                ),
            )
            sender_persistent = report.run(
                "persistence:sender-sent",
                lambda: check_persistence(
                    args.host,
                    args.sender,
                    sender_password,
                    "Sent",
                    run_id,
                    args.sender,
                    args.recipient,
                    prepared,
                    context,
                    args.timeout,
                    state["sender"]["uid"],
                ),
            )
            if recipient_persistent and sender_persistent:
                report.run(
                    "cleanup:recipient-temp-uid-expunge",
                    lambda: cleanup_temp_folder(
                        args.host,
                        args.recipient,
                        recipient_password,
                        folder,
                        run_id,
                        context,
                        args.timeout,
                        recipient_uid,
                    ),
                )
    if args.report:
        report.write(
            args.report,
            {
                "host": args.host,
                "run_id": run_id,
                "mode": "full" if full_mode else "precheck",
                "sender_sent_retained": sender_ok,
            },
        )
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())
