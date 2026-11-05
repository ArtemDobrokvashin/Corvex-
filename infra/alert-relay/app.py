"""Small, dependency-free Alertmanager relay for channels outside Corvex.

External delivery deliberately does not use RabbitMQ, PostgreSQL, or Corvex SMTP.
The last validated admin configuration is cached locally, so an admin/Java outage
cannot disable external channels. Alertmanager remains responsible for grouping
and silences; consumers apply the dynamic category and repeat policy.
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import smtplib
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from email.message import EmailMessage
from http.client import HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from telegram_bot import TelegramBotClient, TelegramBotError

MAX_REQUEST_BYTES = 2 * 1024 * 1024
MESSENGER_TEXT_LIMIT = 3900  # MAX allows 4000; Telegram allows 4096.
EMAIL_TEXT_LIMIT = 60_000
TELEGRAM_API_BASE = "https://api.telegram.org"
MAX_API_BASE = "https://platform-api2.max.ru"
LOG = logging.getLogger("corvex-alert-relay")


class ConfigurationError(ValueError):
    """External alert settings are incomplete or unsafe."""


class DeliveryError(RuntimeError):
    """A channel failed; the message contains no credentials or response bodies."""


def env_bool(env: Mapping[str, str], name: str, default: bool = False) -> bool:
    value = env.get(name)
    if value is None or not value.strip():
        return default
    normalized = value.strip().lower()
    if normalized not in {"true", "false"}:
        raise ConfigurationError(f"{name} must be true or false")
    return normalized == "true"


def env_number(
    env: Mapping[str, str], name: str, default: int, minimum: int, maximum: int
) -> int:
    raw = env.get(name, str(default)).strip()
    try:
        value = int(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{name} must be an integer") from exc
    if not minimum <= value <= maximum:
        raise ConfigurationError(f"{name} must be between {minimum} and {maximum}")
    return value


def duration_seconds(raw: str, name: str) -> int:
    value = raw.strip()
    if not value or not value[:-1].isdigit() or value[-1] not in "smhd":
        raise ConfigurationError(f"{name} must be an integer followed by s, m, h, or d")
    number = int(value[:-1])
    multiplier = {"s": 1, "m": 60, "h": 3600, "d": 86400}[value[-1]]
    seconds = number * multiplier
    if not 1 <= seconds <= 2_592_000:
        raise ConfigurationError(f"{name} is outside the supported range")
    return seconds


def runtime_string(payload: Mapping[str, Any], name: str) -> str:
    value = payload.get(name, "")
    if not isinstance(value, str):
        raise ConfigurationError(f"{name} must be a string")
    return value


def runtime_bool(payload: Mapping[str, Any], name: str) -> bool:
    value = payload.get(name)
    if not isinstance(value, bool):
        raise ConfigurationError(f"{name} must be a boolean")
    return value


def runtime_int(payload: Mapping[str, Any], name: str) -> int:
    value = payload.get(name)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ConfigurationError(f"{name} must be an integer")
    return value


@dataclass(frozen=True)
class Settings:
    email_enabled: bool
    email_recipients: tuple[str, ...]
    smtp_host: str
    smtp_port: int
    smtp_tls_mode: str
    smtp_username: str
    smtp_password: str
    smtp_from: str
    telegram_enabled: bool
    telegram_token: str
    telegram_chat_id: str
    max_enabled: bool
    max_token: str
    max_chat_id: str
    max_user_id: str
    ca_file: str
    timeout_seconds: int
    repeat_normal_seconds: int
    repeat_critical_seconds: int
    disabled_categories: frozenset[str]

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> Settings:
        email_enabled = env_bool(env, "CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED")
        telegram_enabled = env_bool(env, "CORVEX_ALERT_TELEGRAM_ENABLED")
        max_enabled = env_bool(env, "CORVEX_ALERT_MAX_ENABLED")
        recipients = tuple(
            item.strip()
            for item in env.get("CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS", "").split(",")
            if item.strip()
        )
        smtp_host = env.get("CORVEX_ALERT_EXTERNAL_SMTP_HOST", "").strip()
        smtp_from = env.get("CORVEX_ALERT_EXTERNAL_SMTP_FROM", "").strip()
        smtp_username = env.get("CORVEX_ALERT_EXTERNAL_SMTP_USERNAME", "").strip()
        smtp_password = env.get("CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD", "")
        smtp_tls_mode = (
            env.get("CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE", "starttls").strip().lower()
        )
        telegram_token = env.get("CORVEX_ALERT_TELEGRAM_BOT_TOKEN", "").strip()
        telegram_chat_id = env.get("CORVEX_ALERT_TELEGRAM_CHAT_ID", "").strip()
        max_token = env.get("CORVEX_ALERT_MAX_BOT_TOKEN", "").strip()
        max_chat_id = env.get("CORVEX_ALERT_MAX_CHAT_ID", "").strip()
        max_user_id = env.get("CORVEX_ALERT_MAX_USER_ID", "").strip()

        if email_enabled:
            if not recipients:
                raise ConfigurationError(
                    "CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS is required when external email is enabled"
                )
            for name, value in (
                ("CORVEX_ALERT_EXTERNAL_SMTP_HOST", smtp_host),
                ("CORVEX_ALERT_EXTERNAL_SMTP_FROM", smtp_from),
            ):
                if not value:
                    raise ConfigurationError(
                        f"{name} is required when external email is enabled"
                    )
            if any(
                "\r" in item or "\n" in item
                for item in (*recipients, smtp_host, smtp_from)
            ):
                raise ConfigurationError(
                    "External email settings must be single-line values"
                )
            if smtp_tls_mode not in {"starttls", "ssl"}:
                raise ConfigurationError(
                    "CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE must be starttls or ssl"
                )
            if smtp_username and not smtp_password:
                raise ConfigurationError(
                    "CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD is required when SMTP username is set"
                )
        if telegram_enabled:
            if not telegram_token:
                raise ConfigurationError("CORVEX_ALERT_TELEGRAM_BOT_TOKEN is required")
            if not telegram_chat_id:
                raise ConfigurationError("CORVEX_ALERT_TELEGRAM_CHAT_ID is required")
        if max_enabled:
            if not max_token:
                raise ConfigurationError("CORVEX_ALERT_MAX_BOT_TOKEN is required")
            if bool(max_chat_id) == bool(max_user_id):
                raise ConfigurationError(
                    "Set exactly one of CORVEX_ALERT_MAX_CHAT_ID and CORVEX_ALERT_MAX_USER_ID"
                )
            target = max_chat_id or max_user_id
            if not target.lstrip("-").isdigit():
                raise ConfigurationError("MAX chat_id/user_id must be an integer")

        return cls(
            email_enabled=email_enabled,
            email_recipients=recipients,
            smtp_host=smtp_host,
            smtp_port=env_number(env, "CORVEX_ALERT_EXTERNAL_SMTP_PORT", 587, 1, 65535),
            smtp_tls_mode=smtp_tls_mode,
            smtp_username=smtp_username,
            smtp_password=smtp_password,
            smtp_from=smtp_from,
            telegram_enabled=telegram_enabled,
            telegram_token=telegram_token,
            telegram_chat_id=telegram_chat_id,
            max_enabled=max_enabled,
            max_token=max_token,
            max_chat_id=max_chat_id,
            max_user_id=max_user_id,
            ca_file=env.get("CORVEX_ALERT_EXTERNAL_CA_FILE", "").strip(),
            timeout_seconds=env_number(
                env, "CORVEX_ALERT_EXTERNAL_TIMEOUT_SECONDS", 10, 2, 60
            ),
            repeat_normal_seconds=duration_seconds(
                env.get("CORVEX_ALERT_REPEAT_INTERVAL", "4h"),
                "CORVEX_ALERT_REPEAT_INTERVAL",
            ),
            repeat_critical_seconds=duration_seconds(
                env.get("CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL", "30m"),
                "CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL",
            ),
            disabled_categories=frozenset(),
        )

    @classmethod
    def from_runtime(
        cls, payload: Mapping[str, Any], base_env: Mapping[str, str]
    ) -> Settings:
        """Validate an untrusted admin/cache document through the same channel rules as env."""
        categories = payload.get("categories")
        if not isinstance(categories, Mapping):
            raise ConfigurationError("categories must be an object")
        disabled: set[str] = set()
        for name, enabled in categories.items():
            if not isinstance(name, str) or not isinstance(enabled, bool):
                raise ConfigurationError("categories must map alert names to booleans")
            if not enabled:
                disabled.add(name)
        values: dict[str, str] = dict(base_env)
        values.update(
            {
                "CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED": str(runtime_bool(payload, "externalEmailEnabled")).lower(),
                "CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS": runtime_string(payload, "externalEmailRecipients"),
                "CORVEX_ALERT_EXTERNAL_SMTP_HOST": runtime_string(payload, "externalSmtpHost"),
                "CORVEX_ALERT_EXTERNAL_SMTP_PORT": str(runtime_int(payload, "externalSmtpPort")),
                "CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE": runtime_string(payload, "externalSmtpTlsMode"),
                "CORVEX_ALERT_EXTERNAL_SMTP_USERNAME": runtime_string(payload, "externalSmtpUsername"),
                "CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD": runtime_string(payload, "externalSmtpPassword"),
                "CORVEX_ALERT_EXTERNAL_SMTP_FROM": runtime_string(payload, "externalSmtpFrom"),
                "CORVEX_ALERT_TELEGRAM_ENABLED": str(runtime_bool(payload, "telegramEnabled")).lower(),
                "CORVEX_ALERT_TELEGRAM_CHAT_ID": runtime_string(payload, "telegramChatId"),
                "CORVEX_ALERT_TELEGRAM_BOT_TOKEN": runtime_string(payload, "telegramBotToken"),
                "CORVEX_ALERT_MAX_ENABLED": str(runtime_bool(payload, "maxEnabled")).lower(),
                "CORVEX_ALERT_MAX_CHAT_ID": runtime_string(payload, "maxChatId"),
                "CORVEX_ALERT_MAX_USER_ID": runtime_string(payload, "maxUserId"),
                "CORVEX_ALERT_MAX_BOT_TOKEN": runtime_string(payload, "maxBotToken"),
                "CORVEX_ALERT_REPEAT_INTERVAL": f"{runtime_int(payload, 'repeatNormalSeconds')}s",
                "CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL": f"{runtime_int(payload, 'repeatCriticalSeconds')}s",
            }
        )
        parsed = cls.from_env(values)
        return replace(parsed, disabled_categories=frozenset(disabled))

    def channels(self) -> tuple[str, ...]:
        return tuple(
            name
            for enabled, name in (
                (self.email_enabled, "external-email"),
                (self.telegram_enabled, "telegram"),
                (self.max_enabled, "max"),
            )
            if enabled
        )


@dataclass(frozen=True)
class Notification:
    subject: str
    text: str


@dataclass(frozen=True)
class DeliveryResult:
    delivered: tuple[str, ...]
    failed: tuple[str, ...]
    state: str = "delivery"


def delivery_response(result: DeliveryResult) -> tuple[int, dict[str, Any]]:
    body = {
        "delivered": result.delivered,
        "failed": result.failed,
    }
    if result.state != "delivery":
        return 200, {"status": result.state, **body}
    if result.failed and not result.delivered:
        return 502, {"status": "delivery-failed", **body}
    if result.failed:
        # A retry of the whole webhook would duplicate channels already delivered.
        # The failed channel is retried by Alertmanager's next scheduled notification.
        return 200, {"status": "partial-delivery", **body}
    return 200, {"status": "ok", **body}


def mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def text_value(values: Mapping[str, Any], key: str, fallback: str = "") -> str:
    value = values.get(key)
    return fallback if value is None else str(value)


def truncate(value: str, limit: int) -> str:
    if len(value) <= limit:
        return value
    marker = "\n… Message truncated by Corvex alert relay."
    return value[: max(0, limit - len(marker))] + marker


def build_notification(payload: Mapping[str, Any]) -> Notification | None:
    raw_alerts = payload.get("alerts")
    if not isinstance(raw_alerts, list):
        raise TypeError("Alertmanager payload must contain an alerts list")
    alerts = [item for item in raw_alerts if isinstance(item, Mapping)]
    if not alerts:
        return None

    first_labels = mapping(alerts[0].get("labels"))
    severity_values = {
        text_value(mapping(alert.get("labels")), "severity", "warning").lower()
        for alert in alerts
    }
    severity = (
        "critical" if "critical" in severity_values else next(iter(severity_values))
    )
    alert_name = text_value(first_labels, "alertname", "Corvex alert")
    status = text_value(
        payload, "status", text_value(alerts[0], "status", "firing")
    ).upper()
    subject = f"[Corvex {severity}] {alert_name}"
    if len(alerts) > 1:
        subject += f" (+{len(alerts) - 1})"
    subject += f" — {status}"

    lines = [
        f"Corvex monitoring: {status}",
        f"Severity: {severity}",
        f"Alerts: {len(alerts)}",
    ]
    for index, alert in enumerate(alerts, 1):
        labels = mapping(alert.get("labels"))
        annotations = mapping(alert.get("annotations"))
        service = text_value(
            labels, "application", text_value(labels, "instance", "corvex")
        )
        name = text_value(labels, "alertname", "Corvex alert")
        alert_status = text_value(alert, "status", status).upper()
        lines.extend(["", f"{index}. {name} [{alert_status}] — {service}"])
        summary = text_value(annotations, "summary")
        description = text_value(annotations, "description")
        if summary:
            lines.append(summary)
        if description and description != summary:
            lines.append(description)
    return Notification(
        subject=subject, text=truncate("\n".join(lines), EMAIL_TEXT_LIMIT)
    )


def tls_context(ca_file: str = "") -> ssl.SSLContext:
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    if ca_file:
        # Add a private/MinTsifry CA without discarding the public system roots.
        context.load_verify_locations(cafile=ca_file)
    return context


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Do not forward bot credentials or alert contents to an unexpected location."""

    def redirect_request(self, _request, _fp, _code, _message, _headers, _new_url):
        return None


def open_no_redirect(request, *, timeout: int, context: ssl.SSLContext):
    opener = urllib.request.build_opener(
        NoRedirectHandler(), urllib.request.HTTPSHandler(context=context)
    )
    return opener.open(request, timeout=timeout)


class Relay:
    def __init__(
        self,
        settings: Settings,
        *,
        urlopen: Callable[..., Any] | None = None,
        smtp_factory: Callable[..., Any] = smtplib.SMTP,
        smtp_ssl_factory: Callable[..., Any] = smtplib.SMTP_SSL,
        clock: Callable[[], float] = time.time,
        telegram_state_path: str | Path | None = None,
    ) -> None:
        self.settings = settings
        self.urlopen = urlopen or open_no_redirect
        self.smtp_factory = smtp_factory
        self.smtp_ssl_factory = smtp_ssl_factory
        self.context = tls_context(settings.ca_file)
        self.clock = clock
        self.telegram = TelegramBotClient(
            urlopen=self.urlopen,
            context=lambda: self.context,
            timeout=lambda: self.settings.timeout_seconds,
            state_path=telegram_state_path,
        )
        self.max_lock = threading.Lock()
        self.last_max_sent = 0.0
        self.settings_lock = threading.Lock()
        self.last_sent: dict[tuple[str, str], float] = {}
        self.runtime_status: dict[str, Any] = {
            "revision": 0,
            "source": "ENVIRONMENT",
            "refresh": "NOT_ATTEMPTED",
            "updatedAt": None,
            "repeatNormalSeconds": settings.repeat_normal_seconds,
            "repeatCriticalSeconds": settings.repeat_critical_seconds,
            "disabledCategories": sorted(settings.disabled_categories),
        }

    def update_settings(
        self, settings: Settings, revision: int, source: str, updated_at: Any
    ) -> None:
        with self.settings_lock:
            configuration_changed = settings != self.settings
            self.settings = settings
            self.context = tls_context(settings.ca_file)
            if configuration_changed:
                self.last_sent.clear()  # только новая policy сбрасывает repeat-throttle
            self.runtime_status = {
                "revision": revision,
                "source": source,
                "refresh": "SUCCESS",
                "updatedAt": updated_at if isinstance(updated_at, str) else None,
                "repeatNormalSeconds": settings.repeat_normal_seconds,
                "repeatCriticalSeconds": settings.repeat_critical_seconds,
                "disabledCategories": sorted(settings.disabled_categories),
            }

    def mark_refresh_failed(self) -> None:
        with self.settings_lock:
            self.runtime_status = {**self.runtime_status, "refresh": "FAILED"}

    def discover_telegram(self, supplied_token: str) -> dict[str, Any]:
        if not isinstance(supplied_token, str):
            raise TelegramBotError("Telegram bot token must be a string")
        with self.settings_lock:
            effective_token = self.settings.telegram_token
        return self.telegram.discover(supplied_token, effective_token)

    def poll_telegram(self) -> int:
        with self.settings_lock:
            enabled = self.settings.telegram_enabled
            token = self.settings.telegram_token
        if not enabled or not token:
            return 0
        return self.telegram.poll_once(token)

    def deliver(self, payload: Mapping[str, Any]) -> DeliveryResult:
        # Держим один snapshot настроек и throttle state на всю fanout-операцию.
        with self.settings_lock:
            return self._deliver_locked(payload)

    def _deliver_locked(self, payload: Mapping[str, Any]) -> DeliveryResult:
        raw_alerts = payload.get("alerts")
        if isinstance(raw_alerts, list):
            enabled_alerts = [
                alert
                for alert in raw_alerts
                if not isinstance(alert, Mapping)
                or text_value(mapping(alert.get("labels")), "alertname", "Corvex alert")
                not in self.settings.disabled_categories
            ]
            if raw_alerts and not enabled_alerts:
                return DeliveryResult((), (), "filtered")
            payload = {**payload, "alerts": enabled_alerts}
        notification = build_notification(payload)
        if notification is None:
            return DeliveryResult((), ())
        alerts = [item for item in payload.get("alerts", []) if isinstance(item, Mapping)]
        status = text_value(
            payload,
            "status",
            text_value(alerts[0], "status", "firing") if alerts else "firing",
        ).lower()
        resolved = status == "resolved"
        severity_values = {
            text_value(mapping(alert.get("labels")), "severity", "warning").lower()
            for alert in alerts
        }
        repeat_seconds = (
            self.settings.repeat_critical_seconds
            if "critical" in severity_values
            else self.settings.repeat_normal_seconds
        )
        fingerprints = [text_value(alert, "fingerprint") for alert in alerts]
        group_key = text_value(payload, "groupKey")
        if not group_key:
            identities = [
                "|".join(
                    (
                        text_value(mapping(alert.get("labels")), "alertname", "Corvex alert"),
                        text_value(
                            mapping(alert.get("labels")),
                            "application",
                            text_value(mapping(alert.get("labels")), "instance", "corvex"),
                        ),
                    )
                )
                for alert in alerts
            ]
            group_key = "|".join(filter(None, fingerprints)) or "|".join(identities)

        senders = {
            "external-email": self.send_email,
            "telegram": self.send_telegram,
            "max": self.send_max,
        }
        delivered: list[str] = []
        failed: list[str] = []
        attempted = 0
        now = self.clock()
        for channel in self.settings.channels():
            previous = self.last_sent.get((group_key, channel))
            if not resolved and previous is not None and now - previous < repeat_seconds:
                continue
            attempted += 1
            try:
                senders[channel](notification)
                delivered.append(channel)
                if not resolved:
                    self.last_sent[(group_key, channel)] = now
                LOG.warning("External alert delivered: channel=%s", channel)
            except Exception as exc:  # noqa: BLE001 — isolate independent channels
                LOG.error(
                    "External alert delivery failed: channel=%s error=%s",
                    channel,
                    safe_error(exc),
                )
                failed.append(channel)
        if resolved:
            for key in [key for key in self.last_sent if key[0] == group_key]:
                self.last_sent.pop(key, None)
        if attempted == 0 and self.settings.channels():
            return DeliveryResult((), (), "suppressed")
        return DeliveryResult(tuple(delivered), tuple(failed))

    def send_email(self, notification: Notification) -> None:
        message = EmailMessage()
        message["From"] = self.settings.smtp_from
        message["To"] = ", ".join(self.settings.email_recipients)
        message["Subject"] = notification.subject
        message.set_content(notification.text)
        server = None
        try:
            if self.settings.smtp_tls_mode == "ssl":
                server = self.smtp_ssl_factory(
                    self.settings.smtp_host,
                    self.settings.smtp_port,
                    timeout=self.settings.timeout_seconds,
                    context=self.context,
                )
            else:
                server = self.smtp_factory(
                    self.settings.smtp_host,
                    self.settings.smtp_port,
                    timeout=self.settings.timeout_seconds,
                )
                server.ehlo()
                server.starttls(context=self.context)
                server.ehlo()
            if self.settings.smtp_username:
                server.login(self.settings.smtp_username, self.settings.smtp_password)
            server.send_message(message)
        except (smtplib.SMTPException, OSError, ValueError) as exc:
            raise DeliveryError(f"external-email {safe_error(exc)}") from None
        finally:
            if server is not None:
                try:
                    server.quit()
                except (smtplib.SMTPException, OSError) as exc:
                    LOG.debug("SMTP QUIT failed after delivery: %s", safe_error(exc))

    def post_json(
        self,
        channel: str,
        url: str,
        body: Mapping[str, Any],
        headers: Mapping[str, str],
    ) -> None:
        request = urllib.request.Request(
            url,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json", **headers},
            method="POST",
        )
        try:
            with self.urlopen(
                request, timeout=self.settings.timeout_seconds, context=self.context
            ) as response:
                status = getattr(response, "status", 200)
                response.read(4096)
                if not 200 <= status < 300:
                    raise DeliveryError(f"{channel} HTTP {status}")
        except urllib.error.HTTPError as exc:
            raise DeliveryError(f"{channel} HTTP {exc.code}") from None
        except DeliveryError:
            raise
        except (urllib.error.URLError, OSError, HTTPException, ValueError) as exc:
            # URLError/HTTP messages can contain a URL; never propagate them (Telegram token is in its URL).
            raise DeliveryError(f"{channel} {type(exc).__name__}") from None

    def send_telegram(self, notification: Notification) -> None:
        url = f"{TELEGRAM_API_BASE}/bot{self.settings.telegram_token}/sendMessage"
        self.post_json(
            "telegram",
            url,
            {
                "chat_id": self.settings.telegram_chat_id,
                "text": truncate(notification.text, MESSENGER_TEXT_LIMIT),
                "disable_web_page_preview": True,
            },
            {},
        )

    def send_max(self, notification: Notification) -> None:
        # Official MAX limit: no more than two messages/second to one chat.
        with self.max_lock:
            elapsed = time.monotonic() - self.last_max_sent
            if self.last_max_sent and elapsed < 0.55:
                time.sleep(0.55 - elapsed)
            target = (
                {"chat_id": self.settings.max_chat_id}
                if self.settings.max_chat_id
                else {"user_id": self.settings.max_user_id}
            )
            target["disable_link_preview"] = "true"
            url = f"{MAX_API_BASE}/messages?{urllib.parse.urlencode(target)}"
            self.post_json(
                "max",
                url,
                {"text": truncate(notification.text, MESSENGER_TEXT_LIMIT)},
                {"Authorization": self.settings.max_token},
            )
            self.last_max_sent = time.monotonic()


def safe_error(exc: Exception) -> str:
    if isinstance(exc, DeliveryError):
        return str(exc)
    # Do not expose SMTP usernames, URLs with tokens, API response bodies, or credentials.
    return type(exc).__name__


def open_internal_no_redirect(request: urllib.request.Request, *, timeout: int):
    return urllib.request.build_opener(NoRedirectHandler()).open(request, timeout=timeout)


class RuntimeConfigProvider:
    """Poll admin-service, validate the complete document, and keep a 0600 last-good cache."""

    def __init__(
        self,
        relay: Relay,
        env: Mapping[str, str],
        *,
        opener: Callable[..., Any] = open_internal_no_redirect,
    ) -> None:
        self.relay = relay
        self.env = env
        self.opener = opener
        self.admin_url = env.get(
            "CORVEX_ADMIN_SERVICE_URL", "http://admin-service:8090"
        ).rstrip("/")
        self.internal_token = env.get("CORVEX_INTERNAL_API_TOKEN", "").strip()
        self.cache_path = Path(
            env.get(
                "CORVEX_ALERT_RUNTIME_CACHE",
                "/var/lib/corvex-alert-relay/runtime-config.json",
            )
        )

    def load_cache(self) -> None:
        if not self.cache_path.is_file():
            return
        try:
            raw = self.cache_path.read_bytes()
            if not raw or len(raw) > MAX_REQUEST_BYTES:
                raise ConfigurationError("Invalid runtime cache size")
            payload = json.loads(raw)
            if not isinstance(payload, Mapping):
                raise ConfigurationError("Runtime cache must be an object")
            self._apply(payload, "CACHE", write_cache=False)
            LOG.info("Loaded cached alert runtime revision %s", self.relay.runtime_status["revision"])
        except Exception as exc:  # noqa: BLE001 — keep env fallback on a broken cache
            LOG.error("Cannot load alert runtime cache: %s", type(exc).__name__)

    def refresh(self) -> None:
        try:
            if not self.internal_token:
                raise ConfigurationError("CORVEX_INTERNAL_API_TOKEN is required")
            request = urllib.request.Request(
                f"{self.admin_url}/internal/alerts/relay-config",
                headers={
                    "Accept": "application/json",
                    "X-Corvex-Internal-Token": self.internal_token,
                },
            )
            with self.opener(request, timeout=3) as response:
                status = getattr(response, "status", 200)
                raw = response.read(MAX_REQUEST_BYTES + 1)
                if not 200 <= status < 300:
                    raise ConfigurationError(f"Admin config HTTP {status}")
            if not raw or len(raw) > MAX_REQUEST_BYTES:
                raise ConfigurationError("Invalid admin config size")
            payload = json.loads(raw)
            if not isinstance(payload, Mapping):
                raise ConfigurationError("Admin config must be an object")
            self._apply(payload, "ADMIN", write_cache=True)
        except Exception as exc:  # noqa: BLE001 — sanitized polling failure boundary
            self.relay.mark_refresh_failed()
            LOG.warning("Alert runtime refresh failed: %s", type(exc).__name__)

    def _apply(self, payload: Mapping[str, Any], source: str, *, write_cache: bool) -> None:
        revision = runtime_int(payload, "revision")
        if revision < 0:
            raise ConfigurationError("revision must not be negative")
        settings = Settings.from_runtime(payload, self.env)
        if write_cache:
            if revision == 0:
                self.cache_path.unlink(missing_ok=True)
            else:
                self._write_cache(payload)
        updated_at = payload.get("updatedAt")
        self.relay.update_settings(settings, revision, source, updated_at)

    def _write_cache(self, payload: Mapping[str, Any]) -> None:
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if len(encoded) > MAX_REQUEST_BYTES:
            raise ConfigurationError("Runtime config is too large to cache")
        temporary = self.cache_path.with_suffix(".tmp")
        with open(temporary, "wb") as output:
            os.chmod(temporary, 0o600)
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self.cache_path)
        os.chmod(self.cache_path, 0o600)

    def start(self) -> None:
        self.load_cache()
        self.refresh()

        def poll() -> None:
            while True:
                time.sleep(10)
                self.refresh()

        threading.Thread(target=poll, name="alert-runtime-config", daemon=True).start()


class TelegramCommandPoller:
    """Independent outbound command worker; failure never restarts or blocks alert delivery."""

    def __init__(self, relay: Relay, interval_seconds: float = 5.0) -> None:
        self.relay = relay
        self.interval_seconds = interval_seconds

    def start(self) -> None:
        def poll() -> None:
            while True:
                try:
                    self.relay.poll_telegram()
                except Exception as exc:  # noqa: BLE001 — sanitized isolated worker boundary
                    LOG.warning("Telegram command polling failed: %s", safe_error(exc))
                time.sleep(self.interval_seconds)

        threading.Thread(target=poll, name="telegram-commands", daemon=True).start()


class RequestBodyTooLarge(ValueError):
    """The declared or decoded request body exceeds its endpoint-specific bound."""


def read_request_body(handler: BaseHTTPRequestHandler, maximum: int) -> bytes:
    """Read a bounded JSON body, including Spring's chunked requests, without TE/CL ambiguity."""
    transfer_encoding = handler.headers.get("Transfer-Encoding")
    content_length = handler.headers.get("Content-Length")
    if transfer_encoding:
        if content_length is not None or transfer_encoding.strip().lower() != "chunked":
            raise ValueError("Unsupported transfer encoding")
        body = bytearray()
        trailer_bytes = 0
        while True:
            line = handler.rfile.readline(1026)
            if len(line) > 1024 or not line.endswith(b"\r\n"):
                raise ValueError("Invalid chunk header")
            raw_size = line[:-2].split(b";", 1)[0].strip()
            if not raw_size or len(raw_size) > 8 or any(
                byte not in b"0123456789abcdefABCDEF" for byte in raw_size
            ):
                raise ValueError("Invalid chunk size")
            size = int(raw_size, 16)
            if size == 0:
                for _ in range(100):
                    trailer = handler.rfile.readline(1026)
                    trailer_bytes += len(trailer)
                    if len(trailer) > 1024 or trailer_bytes > 16_384 or not trailer.endswith(b"\r\n"):
                        raise ValueError("Invalid chunk trailer")
                    if trailer == b"\r\n":
                        if not body:
                            raise ValueError("Empty request body")
                        return bytes(body)
                raise ValueError("Too many chunk trailers")
            if len(body) + size > maximum:
                raise RequestBodyTooLarge("Chunked request body is too large")
            chunk = handler.rfile.read(size)
            if len(chunk) != size or handler.rfile.read(2) != b"\r\n":
                raise ValueError("Truncated chunk")
            body.extend(chunk)
    if content_length is None:
        raise ValueError("Content-Length is required")
    length = int(content_length)
    if length > maximum:
        raise RequestBodyTooLarge("Request body is too large")
    if length <= 0:
        raise ValueError("Empty request body")
    body = handler.rfile.read(length)
    if len(body) != length:
        raise ValueError("Truncated request body")
    return body


class RelayHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], relay: Relay, internal_token: str = "") -> None:
        self.relay = relay
        self.internal_token = internal_token
        super().__init__(address, AlertRequestHandler)


class AlertRequestHandler(BaseHTTPRequestHandler):
    server: RelayHttpServer
    server_version = "CorvexAlertRelay/1"

    def json_response(self, status: int, body: Mapping[str, Any]) -> None:
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:
        if self.path == "/healthz":
            self.json_response(
                200,
                {
                    "status": "UP",
                    "channels": self.server.relay.settings.channels(),
                    "runtime": self.server.relay.runtime_status,
                },
            )
        else:
            self.json_response(404, {"status": "not-found"})

    def do_POST(self) -> None:
        if self.path == "/internal/telegram/discover":
            expected = self.server.internal_token
            supplied = self.headers.get("X-Corvex-Internal-Token", "")
            if not expected or not supplied or not hmac.compare_digest(
                expected.encode("utf-8"), supplied.encode("utf-8")
            ):
                self.json_response(403, {"status": "forbidden"})
                return
            try:
                payload = json.loads(read_request_body(self, 16_384))
                if not isinstance(payload, Mapping):
                    raise TypeError("JSON object required")
                token = payload.get("botToken", "")
                result = self.server.relay.discover_telegram(token)
                self.json_response(200, result)
            except RequestBodyTooLarge:
                self.json_response(413, {"status": "request-body-too-large"})
            except TelegramBotError as exc:
                invalid = str(exc) in {
                    "Invalid Telegram bot token format",
                    "Telegram bot token must be a string",
                    "Telegram HTTP 401",
                }
                self.json_response(
                    422 if invalid else 502,
                    {"status": "invalid-telegram-token" if invalid else "telegram-discovery-failed"},
                )
            except (TypeError, ValueError, UnicodeDecodeError):
                self.json_response(400, {"status": "invalid-discovery-payload"})
            except Exception as exc:  # noqa: BLE001 — sanitized HTTP failure boundary
                LOG.error("Telegram discovery failed: %s", safe_error(exc))
                self.json_response(502, {"status": "telegram-discovery-failed"})
            return
        if self.path != "/alerts/webhook":
            self.json_response(404, {"status": "not-found"})
            return
        try:
            payload = json.loads(read_request_body(self, MAX_REQUEST_BYTES))
            if not isinstance(payload, Mapping):
                raise TypeError("JSON object required")
            result = self.server.relay.deliver(payload)
            status, body = delivery_response(result)
            self.json_response(status, body)
        except RequestBodyTooLarge:
            self.json_response(413, {"status": "request-body-too-large"})
        except (TypeError, ValueError, UnicodeDecodeError):
            self.json_response(400, {"status": "invalid-alertmanager-payload"})
        except Exception as exc:  # noqa: BLE001 — sanitized HTTP failure boundary
            LOG.error("Webhook failed: %s", safe_error(exc))
            self.json_response(502, {"status": "delivery-failed"})

    def log_message(self, message: str, *args: Any) -> None:
        LOG.info("HTTP %s", message % args)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    try:
        settings = Settings.from_env(os.environ)
        relay = Relay(
            settings,
            telegram_state_path=os.environ.get(
                "CORVEX_ALERT_TELEGRAM_STATE",
                "/var/lib/corvex-alert-relay/telegram-state.json",
            ),
        )
        RuntimeConfigProvider(relay, os.environ).start()
        TelegramCommandPoller(relay).start()
    except Exception as exc:  # noqa: BLE001 — sanitized startup failure boundary
        # ConfigurationError names the invalid key; CA/TLS errors do not contain a secret value.
        LOG.error(
            "Cannot start alert relay: %s",
            safe_error(exc) if not isinstance(exc, ConfigurationError) else str(exc),
        )
        raise SystemExit(2) from None
    LOG.info(
        "Alert relay listening on 0.0.0.0:8092; external channels: %s",
        ",".join(settings.channels()) or "none",
    )
    try:
        RelayHttpServer(
            ("0.0.0.0", 8092), relay, os.environ.get("CORVEX_INTERNAL_API_TOKEN", "")
        ).serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
