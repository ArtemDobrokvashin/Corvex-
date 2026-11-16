#!/usr/bin/env python3
"""Приёмка серверного календаря и напоминаний по HTTP, без браузера.

Проверяет то, что нельзя увидеть в unit-тестах:
  1. вход настоящим пользователем и наличие прав CALENDAR_READ/WRITE;
  2. создание события и его появление в выборке за период;
  3. защиту от перезаписи: PUT с устаревшей revision даёт 409;
  4. изменение с актуальной revision увеличивает revision;
  5. напоминание реально приходит письмом в INBOX (значит, работает
     планировщик → notification-service → SMTP → доставка);
  6. удаление события и 404 после него.

Скрипт ничего не удаляет, кроме созданного им же события с уникальным run ID,
и не читает чужие письма: в INBOX ищется только тема с этим run ID.
Пароль берётся из CORVEX_TEST_PASSWORD или запрашивается скрыто и никогда не
попадает в отчёт.

Пример:
    py -3 scripts/calendar_smoke.py --username i.ivanov@company.local \
        --base-url https://mail.company.local --report artifacts/smoke/calendar.json
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from smoke_common import SkipCheck, SmokeError, SmokeReport, make_ssl_context

ROOT = Path(__file__).resolve().parent.parent
UTC = timezone.utc  # noqa: UP017 — Python 3.10 compatibility.
REMINDER_PREFIX = "[Corvex] Напоминание: "


class HttpError(SmokeError):
    """HTTP-ответ с кодом, отличным от ожидаемого."""

    def __init__(self, method: str, path: str, status: int, body: str) -> None:
        self.status = status
        super().__init__(f"{method} {path}: HTTP {status} — {body[:300]}")


class Api:
    """Минимальный клиент gateway с Bearer-токеном и явным разбором ошибок."""

    def __init__(self, base_url: str, ca_file: str | None, timeout: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.context: ssl.SSLContext | None = make_ssl_context(ca_file)
        self.timeout = timeout
        self.token = ""

    def request(
        self,
        method: str,
        path: str,
        payload: Any | None = None,
        *,
        expect: tuple[int, ...] = (200,),
    ) -> Any:
        url = self.base_url + path
        data = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(
                request, timeout=self.timeout, context=self.context
            ) as response:
                status = response.status
                body = response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            status = exc.code
            body = exc.read().decode("utf-8", errors="replace")
        except urllib.error.URLError as exc:
            raise SmokeError(
                f"{method} {path}: сеть недоступна ({exc.reason}). "
                "Проверьте --base-url, сертификат и что стек запущен."
            ) from exc
        if status not in expect:
            raise HttpError(method, path, status, body)
        if not body.strip():
            return None
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return body

    def expect_status(
        self, method: str, path: str, payload: Any | None, status: int
    ) -> None:
        """Проверка отрицательного сценария: код должен совпасть, тело не важно."""
        try:
            self.request(method, path, payload, expect=(status,))
        except HttpError as exc:
            raise SmokeError(
                f"{method} {path}: ожидался HTTP {status}, получен {exc.status}"
            ) from exc


def require_permissions(api: Api, needed: tuple[str, ...]) -> dict[str, Any]:
    me = api.request("GET", "/api/v1/auth/me")
    if not isinstance(me, dict):
        raise SmokeError("GET /api/v1/auth/me вернул не JSON-объект")
    granted = set(me.get("permissions") or [])
    missing = [name for name in needed if name not in granted]
    if missing:
        hint = ""
        if me.get("demoMode"):
            hint = " Это демо-сессия: календарь в ней локальный и серверных прав не имеет."
        raise SmokeError(
            "У пользователя нет прав " + ", ".join(missing)
            + ". Выдайте группе CALENDAR_READ/CALENDAR_WRITE в админке." + hint
        )
    return {
        "userId": me.get("id"),
        "email": me.get("email"),
        "groups": me.get("groups") or [],
    }


def login(api: Api, username: str, password: str) -> None:
    body = api.request(
        "POST",
        "/api/v1/auth/login",
        {"username": username, "password": password},
        expect=(200,),
    )
    if not isinstance(body, dict):
        raise SmokeError("Вход вернул не JSON-объект")
    if body.get("mfaRequired"):
        raise SmokeError(
            "У аккаунта включена 2FA: пароль не даёт access token. "
            "Для приёмки используйте отдельного пользователя без 2FA "
            "или аккаунт, где 2FA отключена на время проверки."
        )
    token = body.get("accessToken")
    if not token or not isinstance(token, str):
        raise SmokeError("В ответе на вход нет accessToken")
    api.token = token


def calendar_payload(
    run_id: str,
    starts_at: datetime,
    ends_at: datetime,
    time_zone: str,
    reminder_minutes: int | None,
) -> dict[str, Any]:
    return {
        "title": f"Corvex smoke {run_id}",
        "description": "Техническая проверка приёмки календаря. Можно удалить.",
        "location": "Автоматическая проверка",
        "startsAt": starts_at.astimezone(UTC).isoformat().replace("+00:00", "Z"),
        "endsAt": ends_at.astimezone(UTC).isoformat().replace("+00:00", "Z"),
        "allDay": False,
        "timeZone": time_zone,
        "priority": "MEDIUM",
        "busyStatus": "BUSY",
        "recurrence": "NONE",
        "reminderMinutes": reminder_minutes,
        "attendees": [],
        "source": {"kind": "NONE", "mailId": None},
    }


def parse_instant(value: str) -> datetime:
    """Разбор ISO-8601 из ответа API; поддерживает 'Z' и смещение."""
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed


def inbox_folder(api: Api) -> str:
    folders = api.request("GET", "/api/v1/mail/folders")
    if not isinstance(folders, list) or not folders:
        raise SmokeError("GET /api/v1/mail/folders вернул пустой список")
    for folder in folders:
        if str(folder.get("specialUse") or "").upper() == "INBOX":
            return str(folder["id"])
    for folder in folders:
        if str(folder.get("name") or "").upper() == "INBOX":
            return str(folder["id"])
    raise SmokeError(
        "В списке папок нет INBOX со specialUse=INBOX; "
        "напоминание некуда искать — приёмка остановлена, а не выдана за успех"
    )


def find_reminder(api: Api, folder_id: str, run_id: str) -> dict[str, Any] | None:
    """Ищет только письмо своего run ID; тело не читается."""
    path = f"/api/v1/mail/folders/{urllib.parse.quote(folder_id)}/messages?limit=50"
    messages = api.request("GET", path)
    if not isinstance(messages, list):
        return None
    for message in messages:
        subject = str(message.get("subject") or "")
        if run_id in subject and subject.startswith(REMINDER_PREFIX):
            return {
                "subject": subject,
                "sender": message.get("sender"),
                "date": message.get("date"),
            }
    return None


def wait_for_reminder(
    api: Api, folder_id: str, run_id: str, wait_seconds: int, poll_seconds: int
) -> dict[str, Any]:
    deadline = time.monotonic() + wait_seconds
    while time.monotonic() < deadline:
        found = find_reminder(api, folder_id, run_id)
        if found:
            return found
        time.sleep(poll_seconds)
    raise SmokeError(
        f"Напоминание не появилось в INBOX за {wait_seconds} с. Проверьте: "
        "CORVEX_INTERNAL_API_TOKEN одинаковый у mail-storage и notification, "
        "CORVEX_CALENDAR_FROM указывает на существующий домен, "
        "docker compose logs notification-service — ошибки SMTP, "
        "и что у напоминания осталось время до начала события."
    )


def delete_quietly(api: Api, event_id: str, revision: int | None) -> None:
    path = f"/api/v1/calendar/events/{urllib.parse.quote(event_id)}"
    if revision is not None:
        path += f"?revision={revision}"
    try:
        api.request("DELETE", path, expect=(204, 200, 404))
    except SmokeError as exc:  # noqa: BLE001 — уборка не должна скрывать результат проверки.
        print(f"[WARN] не удалось убрать тестовое событие {event_id}: {exc}", flush=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Приёмка календаря и напоминаний через gateway: событие, 409 на устаревшую "
            "revision, письмо-напоминание в INBOX, удаление. Создаёт одно событие "
            "с уникальным run ID и удаляет его в конце."
        )
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("CORVEX_BASE_URL", "https://mail.company.local"),
        help="Корень gateway, например https://mail.company.local",
    )
    parser.add_argument(
        "--username",
        required=True,
        help="Логин или email пользователя с правом CALENDAR_WRITE",
    )
    parser.add_argument(
        "--ca-file", help="PEM тестового CA; системное доверие используется без флага"
    )
    parser.add_argument(
        "--time-zone",
        default=os.environ.get("CORVEX_TEST_TIME_ZONE", "Europe/Amsterdam"),
        help="IANA-зона создаваемого события",
    )
    parser.add_argument(
        "--lead-minutes",
        type=int,
        default=4,
        help="Через сколько минут начать событие (>=3, чтобы напоминание успело)",
    )
    parser.add_argument(
        "--wait-seconds",
        type=int,
        default=600,
        help="Сколько ждать письмо-напоминание",
    )
    parser.add_argument("--poll-seconds", type=int, default=10)
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument(
        "--skip-reminder",
        action="store_true",
        help="Быстрая проверка API без ожидания письма (напоминание не подтверждается)",
    )
    parser.add_argument(
        "--keep",
        action="store_true",
        help="Оставить событие после проверки (по умолчанию оно удаляется)",
    )
    parser.add_argument(
        "--report", type=Path, help="Путь JSON-отчёта, например artifacts/smoke/calendar.json"
    )
    args = parser.parse_args()
    if args.lead_minutes < 3:
        parser.error("--lead-minutes должен быть >= 3")
    if args.timeout <= 0 or args.poll_seconds <= 0:
        parser.error("--timeout и --poll-seconds должны быть > 0")
    if not args.skip_reminder and args.wait_seconds <= 0:
        parser.error("--wait-seconds должен быть > 0 без --skip-reminder")
    try:
        from zoneinfo import ZoneInfo

        ZoneInfo(args.time_zone)
    except Exception:  # noqa: BLE001 — неверная зона должна дать понятный отказ.
        parser.error(f"Неизвестная IANA-зона: {args.time_zone!r}")
    if not args.base_url.startswith(("https://", "http://")):
        parser.error("--base-url должен начинаться с https:// или http://")
    return args


def main() -> int:
    args = parse_args()
    password = os.environ.get("CORVEX_TEST_PASSWORD")
    if not password:
        password = getpass.getpass("Пароль тестового пользователя (скрытый ввод): ")

    run_id = uuid.uuid4().hex[:12]
    report = SmokeReport("calendar", ROOT)
    created: dict[str, Any] | None = None

    api = Api(args.base_url, args.ca_file, args.timeout)

    state: dict[str, Any] = {}

    def check_login() -> dict[str, Any]:
        login(api, args.username, password)
        me = require_permissions(api, ("CALENDAR_READ", "CALENDAR_WRITE"))
        state["me"] = me
        state["folder"] = inbox_folder(api)
        return {"user": me.get("email"), "groups": me.get("groups")}

    def check_create() -> dict[str, Any]:
        starts = datetime.now(UTC) + timedelta(minutes=args.lead_minutes)
        ends = starts + timedelta(minutes=15)
        reminder = None if args.skip_reminder else 1
        payload = calendar_payload(run_id, starts, ends, args.time_zone, reminder)
        event = api.request("POST", "/api/v1/calendar/events", payload, expect=(201,))
        if not isinstance(event, dict) or not event.get("id"):
            raise SmokeError("Создание события не вернуло id")
        nonlocal created
        created = event
        state["window_from"] = starts - timedelta(minutes=5)
        state["window_to"] = ends + timedelta(minutes=5)
        return {
            "id": event["id"],
            "revision": event.get("revision"),
            "startsAt": event.get("startsAt"),
            "timeZone": event.get("timeZone"),
            "reminderMinutes": event.get("reminderMinutes"),
        }

    def check_listed() -> dict[str, Any]:
        if not created:
            raise SmokeError("Событие не создано на предыдущем шаге")
        query = urllib.parse.urlencode(
            {
                "from": state["window_from"].isoformat().replace("+00:00", "Z"),
                "to": state["window_to"].isoformat().replace("+00:00", "Z"),
            }
        )
        events = api.request("GET", f"/api/v1/calendar/events?{query}")
        if not isinstance(events, list):
            raise SmokeError("Список событий вернул не массив")
        match = [item for item in events if item.get("id") == created["id"]]
        if not match:
            raise SmokeError(
                f"Созданное событие {created['id']} не найдено в выборке за период"
            )
        if match[0].get("timeZone") != args.time_zone:
            raise SmokeError(
                f"Зона события сохранилась как {match[0].get('timeZone')!r}, "
                f"ожидалась {args.time_zone!r}"
            )
        return {"count": len(events), "matched": 1}

    def check_stale_revision() -> dict[str, Any]:
        if not created:
            raise SmokeError("Событие не создано")
        payload = calendar_payload(
            run_id, datetime.now(UTC), datetime.now(UTC) + timedelta(minutes=10),
            args.time_zone, None,
        )
        payload["revision"] = max(0, int(created.get("revision") or 1) - 1)
        api.expect_status(
            "PUT", f"/api/v1/calendar/events/{created['id']}", payload, 409
        )
        return {"expected": 409, "staleRevision": payload["revision"]}

    def check_update() -> dict[str, Any]:
        if not created:
            raise SmokeError("Событие не создано")
        payload = calendar_payload(
            run_id, datetime.now(UTC), datetime.now(UTC) + timedelta(minutes=10),
            args.time_zone, None,
        )
        payload["revision"] = int(created.get("revision") or 1)
        updated = api.request(
            "PUT", f"/api/v1/calendar/events/{created['id']}", payload, expect=(200,)
        )
        if not isinstance(updated, dict):
            raise SmokeError("PUT события вернул не JSON-объект")
        new_revision = int(updated.get("revision") or 0)
        old_revision = int(created.get("revision") or 0)
        if new_revision <= old_revision:
            raise SmokeError(
                f"revision не выросла после изменения: {old_revision} → {new_revision}"
            )
        created = updated
        return {"revision": new_revision}

    def check_reminder() -> dict[str, Any]:
        if args.skip_reminder:
            raise SkipCheck("пропущено флагом --skip-reminder: доставка письма не проверялась")
        found = wait_for_reminder(
            api, state["folder"], run_id, args.wait_seconds, args.poll_seconds
        )
        return {
            "subjectHasRunId": True,
            "sender": found.get("sender"),
            "date": found.get("date"),
        }

    def check_delete() -> dict[str, Any]:
        if not created:
            raise SmokeError("Событие не создано")
        revision = int(created.get("revision") or 1)
        api.request(
            "DELETE",
            f"/api/v1/calendar/events/{created['id']}?revision={revision}",
            expect=(204, 200),
        )
        api.expect_status("GET", f"/api/v1/calendar/events/{created['id']}", None, 404)
        return {"deleted": True, "followUp": 404}

    report.run("auth.login_and_calendar_permissions", check_login)
    try:
        report.run("calendar.create", check_create)
        report.run("calendar.listed_in_range", check_listed)
        report.run("calendar.stale_revision_409", check_stale_revision)
        report.run("calendar.update_bumps_revision", check_update)
        report.run("reminder.delivered_to_inbox", check_reminder)
        report.run("calendar.delete_and_404", check_delete)
    finally:
        if created and not args.keep:
            delete_quietly(api, created["id"], created.get("revision"))
    if args.keep and created:
        print(f"[INFO] событие оставлено: {created['id']}", flush=True)

    metadata = {
        "base_url": args.base_url,
        "run_id": run_id,
        "time_zone": args.time_zone,
        "reminder_checked": not args.skip_reminder,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        report.write(args.report, metadata)
        print(f"[INFO] отчёт: {args.report}", flush=True)
    return report.exit_code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SmokeError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        sys.exit(1)
