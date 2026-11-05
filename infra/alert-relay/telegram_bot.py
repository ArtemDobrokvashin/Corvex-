"""Telegram setup discovery and command handling without exposing bot credentials.

The client uses outbound getUpdates polling: a self-hosted Corvex installation does
not need a public webhook. Discovery never acknowledges updates. The command worker
acknowledges them with an offset persisted separately from the runtime config.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import ssl
import threading
import urllib.error
import urllib.request
from collections.abc import Callable, Mapping
from http.client import HTTPException
from pathlib import Path
from typing import Any

MAX_RESPONSE_BYTES = 2 * 1024 * 1024
TELEGRAM_API_BASE = "https://api.telegram.org"
TOKEN_PATTERN = re.compile(r"^[0-9]{1,20}:[A-Za-z0-9_-]{10,200}$")
LOG = logging.getLogger("corvex-alert-relay.telegram")


class TelegramBotError(RuntimeError):
    """Sanitized Telegram setup/command failure; never contains an API response."""


def mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def message_from_update(update: Mapping[str, Any]) -> Mapping[str, Any]:
    message = update.get("message")
    if isinstance(message, Mapping):
        return message
    channel_post = update.get("channel_post")
    return channel_post if isinstance(channel_post, Mapping) else {}


def command_name(message: Mapping[str, Any]) -> str:
    text = message.get("text")
    if not isinstance(text, str) or not text.startswith("/"):
        return ""
    return text.split(maxsplit=1)[0].split("@", 1)[0].lower()


def clean_title(value: Any, fallback: str) -> str:
    if not isinstance(value, str):
        return fallback
    cleaned = " ".join(value.split()).strip()
    return cleaned[:200] or fallback


def chat_from_update(update: Mapping[str, Any]) -> tuple[dict[str, str], Mapping[str, Any]] | None:
    message = message_from_update(update)
    if not message or not command_name(message):
        return None
    raw_chat = mapping(message.get("chat"))
    chat_id = raw_chat.get("id")
    if isinstance(chat_id, bool) or not isinstance(chat_id, int):
        return None
    encoded_id = str(chat_id)
    if not re.fullmatch(r"-?[0-9]{1,32}", encoded_id):
        return None
    chat_type = clean_title(raw_chat.get("type"), "unknown")
    if chat_type == "private":
        full_name = " ".join(
            part for part in (
                clean_title(raw_chat.get("first_name"), ""),
                clean_title(raw_chat.get("last_name"), ""),
            ) if part
        )
        title = full_name or clean_title(raw_chat.get("username"), "Telegram user")
    else:
        title = clean_title(raw_chat.get("title"), "Telegram chat")
    return {"id": encoded_id, "type": chat_type, "title": title}, message


def command_for_bot(message: Mapping[str, Any], bot_username: str) -> str:
    text = message.get("text")
    if not isinstance(text, str) or not text.startswith("/"):
        return ""
    target = text.split(maxsplit=1)[0]
    parts = target.split("@", 1)
    if len(parts) == 2 and parts[1].lower() != bot_username.lower():
        return ""
    command = parts[0].lower()
    return command if command in {"/start", "/id", "/help"} else ""


def command_response(command: str, chat_id: str, message: Mapping[str, Any]) -> str:
    language = mapping(message.get("from")).get("language_code")
    russian = isinstance(language, str) and language.lower().startswith("ru")
    if command == "/id":
        return f"Chat ID: {chat_id}"
    if command == "/help":
        if russian:
            return (
                "Corvex — уведомления о сбоях почтового сервиса.\n"
                "/start — обнаружить этот чат и показать его ID.\n"
                "/id — показать числовой ID чата.\n"
                "Выбор получателя выполняется суперадминистратором в разделе "
                "«Алерты и доставка»."
            )
        return (
            "Corvex sends mail service failure alerts.\n"
            "/start — detect this chat and show its ID.\n"
            "/id — show the numeric chat ID.\n"
            "A Corvex super-admin selects the recipient under Alerts and delivery."
        )
    if russian:
        return (
            f"Corvex: чат обнаружен.\nChat ID: {chat_id}\n"
            "Вернитесь в раздел «Алерты и доставка» и нажмите «Найти чаты». "
            "Получатель выбирается только суперадминистратором."
        )
    return (
        f"Corvex: chat detected.\nChat ID: {chat_id}\n"
        "Return to Alerts and delivery and select Find chats. "
        "Only a super-admin can select the alert recipient."
    )


class TelegramBotClient:
    def __init__(
        self,
        *,
        urlopen: Callable[..., Any],
        context: Callable[[], ssl.SSLContext],
        timeout: Callable[[], int],
        state_path: str | Path | None = None,
    ) -> None:
        self.urlopen = urlopen
        self.context = context
        self.timeout = timeout
        self.state_path = Path(state_path) if state_path else None
        self.updates_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.chats_lock = threading.Lock()
        self.fingerprint = ""
        self.offset = 0
        self.username = ""
        self.recent_fingerprint = ""
        self.recent_chats: dict[str, dict[str, str]] = {}

    @staticmethod
    def validate_token(token: str) -> str:
        if not isinstance(token, str) or not TOKEN_PATTERN.fullmatch(token):
            raise TelegramBotError("Invalid Telegram bot token format")
        return token

    @staticmethod
    def token_fingerprint(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    def api(self, token: str, method: str, body: Mapping[str, Any]) -> Any:
        self.validate_token(token)
        if method not in {"getMe", "getUpdates", "sendMessage"}:
            raise TelegramBotError("Unsupported Telegram API method")
        request = urllib.request.Request(
            f"{TELEGRAM_API_BASE}/bot{token}/{method}",
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with self.urlopen(request, timeout=self.timeout(), context=self.context()) as response:
                status = getattr(response, "status", 200)
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                if not 200 <= status < 300:
                    raise TelegramBotError(f"Telegram HTTP {status}")
            if not raw or len(raw) > MAX_RESPONSE_BYTES:
                raise TelegramBotError("Invalid Telegram response size")
            payload = json.loads(raw)
            if not isinstance(payload, Mapping) or payload.get("ok") is not True:
                raise TelegramBotError("Telegram API rejected request")
            return payload.get("result")
        except urllib.error.HTTPError as exc:
            raise TelegramBotError(f"Telegram HTTP {exc.code}") from None
        except TelegramBotError:
            raise
        except (urllib.error.URLError, OSError, HTTPException, ValueError) as exc:
            raise TelegramBotError(f"Telegram {type(exc).__name__}") from None

    def get_username(self, token: str) -> str:
        result = self.api(token, "getMe", {})
        if not isinstance(result, Mapping):
            raise TelegramBotError("Invalid Telegram bot identity")
        username = result.get("username")
        if not isinstance(username, str) or not re.fullmatch(r"[A-Za-z0-9_]{3,64}", username):
            raise TelegramBotError("Invalid Telegram bot username")
        return username

    @staticmethod
    def updates(result: Any) -> list[Mapping[str, Any]]:
        if not isinstance(result, list) or len(result) > 100:
            raise TelegramBotError("Invalid Telegram updates")
        updates: list[Mapping[str, Any]] = []
        for update in result:
            if not isinstance(update, Mapping):
                raise TelegramBotError("Invalid Telegram update")
            update_id = update.get("update_id")
            if isinstance(update_id, bool) or not isinstance(update_id, int) or update_id < 0:
                raise TelegramBotError("Invalid Telegram update ID")
            updates.append(update)
        return updates

    @staticmethod
    def unique_chats(updates: list[Mapping[str, Any]]) -> dict[str, dict[str, str]]:
        chats: dict[str, dict[str, str]] = {}
        for update in updates:
            parsed = chat_from_update(update)
            if parsed:
                chat, _message = parsed
                chats[chat["id"]] = chat
        return chats

    def remember(self, fingerprint: str, chats: Mapping[str, dict[str, str]]) -> None:
        if not chats:
            return
        with self.chats_lock:
            if self.recent_fingerprint != fingerprint:
                self.recent_fingerprint = fingerprint
                self.recent_chats = {}
            self.recent_chats.update(chats)
            if len(self.recent_chats) > 50:
                self.recent_chats = dict(list(self.recent_chats.items())[-50:])

    def discover(self, supplied_token: str, effective_token: str) -> dict[str, Any]:
        token = supplied_token.strip() if supplied_token.strip() else effective_token.strip()
        self.validate_token(token)
        username = self.get_username(token)
        with self.updates_lock:
            result = self.api(token, "getUpdates", {
                "limit": 100,
                "timeout": 0,
                "allowed_updates": ["message", "channel_post"],
            })
        updates = self.updates(result)
        chats = self.unique_chats(updates)
        fingerprint = self.token_fingerprint(token)
        with self.chats_lock:
            if self.recent_fingerprint == fingerprint:
                chats = {**self.recent_chats, **chats}
        return {"botUsername": username, "chats": list(chats.values())}

    def load_offset(self, fingerprint: str) -> int:
        if not self.state_path or not self.state_path.is_file():
            return 0
        try:
            raw = self.state_path.read_bytes()
            if not raw or len(raw) > 4096:
                raise ValueError("state size")
            payload = json.loads(raw)
            if not isinstance(payload, Mapping):
                raise ValueError("state object")
            saved_fingerprint = payload.get("fingerprint")
            offset = payload.get("offset")
            if not isinstance(saved_fingerprint, str) or saved_fingerprint != fingerprint:
                return 0
            if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
                raise ValueError("state offset")
            return offset
        except (OSError, ValueError) as exc:
            LOG.warning("Cannot load Telegram command state: %s", type(exc).__name__)
            return 0

    def save_offset(self) -> None:
        if not self.state_path:
            return
        temporary = self.state_path.with_suffix(".tmp")
        encoded = json.dumps({
            "fingerprint": self.fingerprint,
            "offset": self.offset,
        }).encode("utf-8")
        with open(temporary, "wb") as output:
            os.chmod(temporary, 0o600)
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self.state_path)
        os.chmod(self.state_path, 0o600)

    def select_token(self, token: str) -> None:
        fingerprint = self.token_fingerprint(token)
        if self.fingerprint == fingerprint:
            return
        self.fingerprint = fingerprint
        self.offset = self.load_offset(fingerprint)
        self.username = ""

    def poll_once(self, token: str) -> int:
        """Process one bounded update batch; command failures never affect alert delivery."""
        self.validate_token(token)
        with self.state_lock:
            self.select_token(token)
            if not self.username:
                self.username = self.get_username(token)
            with self.updates_lock:
                result = self.api(token, "getUpdates", {
                    "offset": self.offset,
                    "limit": 100,
                    "timeout": 0,
                    "allowed_updates": ["message", "channel_post"],
                })
            updates = self.updates(result)
            fingerprint = self.fingerprint
            self.remember(fingerprint, self.unique_chats(updates))
            for update in updates:
                update_id = update["update_id"]
                self.offset = max(self.offset, update_id + 1)
                parsed = chat_from_update(update)
                if not parsed:
                    continue
                chat, message = parsed
                command = command_for_bot(message, self.username)
                if not command:
                    continue
                try:
                    self.api(token, "sendMessage", {
                        "chat_id": chat["id"],
                        "text": command_response(command, chat["id"], message),
                        "disable_web_page_preview": True,
                    })
                except TelegramBotError as exc:
                    LOG.warning("Telegram command response failed: %s", exc)
            if updates:
                try:
                    self.save_offset()
                except OSError as exc:
                    LOG.warning("Cannot save Telegram command state: %s", type(exc).__name__)
            return len(updates)
