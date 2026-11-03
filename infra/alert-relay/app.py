"""Small, dependency-free Alertmanager relay for channels outside Corvex.

It deliberately does not use RabbitMQ, PostgreSQL, Corvex SMTP, or any Java service.
Alertmanager remains responsible for grouping, repeat intervals, and silences.
"""

from __future__ import annotations

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
from dataclasses import dataclass
from email.message import EmailMessage
from http.client import HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

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
        )

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


def delivery_response(result: DeliveryResult) -> tuple[int, dict[str, Any]]:
    body = {
        "delivered": result.delivered,
        "failed": result.failed,
    }
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
    ) -> None:
        self.settings = settings
        self.urlopen = urlopen or open_no_redirect
        self.smtp_factory = smtp_factory
        self.smtp_ssl_factory = smtp_ssl_factory
        self.context = tls_context(settings.ca_file)
        self.max_lock = threading.Lock()
        self.last_max_sent = 0.0

    def deliver(self, payload: Mapping[str, Any]) -> DeliveryResult:
        notification = build_notification(payload)
        if notification is None:
            return DeliveryResult((), ())
        senders = {
            "external-email": self.send_email,
            "telegram": self.send_telegram,
            "max": self.send_max,
        }
        delivered: list[str] = []
        failed: list[str] = []
        for channel in self.settings.channels():
            try:
                senders[channel](notification)
                delivered.append(channel)
                LOG.warning("External alert delivered: channel=%s", channel)
            except Exception as exc:  # noqa: BLE001 — isolate independent channels
                LOG.error(
                    "External alert delivery failed: channel=%s error=%s",
                    channel,
                    safe_error(exc),
                )
                failed.append(channel)
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


class RelayHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], relay: Relay) -> None:
        self.relay = relay
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
                200, {"status": "UP", "channels": self.server.relay.settings.channels()}
            )
        else:
            self.json_response(404, {"status": "not-found"})

    def do_POST(self) -> None:
        if self.path != "/alerts/webhook":
            self.json_response(404, {"status": "not-found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_REQUEST_BYTES:
                self.json_response(413, {"status": "invalid-content-length"})
                return
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, Mapping):
                raise TypeError("JSON object required")
            result = self.server.relay.deliver(payload)
            status, body = delivery_response(result)
            self.json_response(status, body)
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
        relay = Relay(settings)
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
        RelayHttpServer(("0.0.0.0", 8092), relay).serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
