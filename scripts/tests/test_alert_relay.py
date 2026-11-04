from __future__ import annotations

import importlib.util
import json
import ssl
import stat
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "corvex_alert_relay", ROOT / "infra/alert-relay/app.py"
)
assert SPEC and SPEC.loader
relay_module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = relay_module
SPEC.loader.exec_module(relay_module)


PAYLOAD = {
    "status": "firing",
    "alerts": [
        {
            "status": "firing",
            "labels": {
                "alertname": "ServiceDown",
                "severity": "critical",
                "instance": "chat-service:8088",
            },
            "annotations": {"summary": "chat-service is down"},
        }
    ],
}


def settings(**overrides):
    values = {
        "CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED": "false",
        "CORVEX_ALERT_TELEGRAM_ENABLED": "false",
        "CORVEX_ALERT_MAX_ENABLED": "false",
    }
    values.update(overrides)
    return relay_module.Settings.from_env(values)


def runtime_payload(**overrides):
    payload = {
        "revision": 5,
        "updatedAt": "2026-11-04T10:00:00Z",
        "repeatNormalSeconds": 14_400,
        "repeatCriticalSeconds": 1_800,
        "categories": {name: True for name in (
            "ServiceDown", "HighHttp5xxRate", "MailQueueBacklog", "DiskSpaceLow", "JvmMemoryPressure"
        )},
        "externalEmailEnabled": False,
        "externalEmailRecipients": "",
        "externalSmtpHost": "",
        "externalSmtpPort": 587,
        "externalSmtpTlsMode": "starttls",
        "externalSmtpUsername": "",
        "externalSmtpPassword": "",
        "externalSmtpFrom": "",
        "telegramEnabled": True,
        "telegramChatId": "-100123",
        "telegramBotToken": "telegram-secret",
        "maxEnabled": False,
        "maxChatId": "",
        "maxUserId": "",
        "maxBotToken": "",
    }
    payload.update(overrides)
    return payload


class FakeResponse:
    status = 200

    def __init__(self):
        self.body_read = False

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self, _limit):
        self.body_read = True
        return b"{}"


class StaticResponse:
    status = 200

    def __init__(self, payload):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self, _limit):
        return json.dumps(self.payload).encode("utf-8")


class FakeSmtp:
    def __init__(self):
        self.events = []
        self.message = None

    def ehlo(self):
        self.events.append("ehlo")

    def starttls(self, *, context):
        self.events.append(("starttls", context))

    def login(self, username, password):
        self.events.append(("login", username, password))

    def send_message(self, message):
        self.message = message
        self.events.append("send")

    def quit(self):
        self.events.append("quit")


class AlertSettingsTest(unittest.TestCase):
    def test_external_channels_are_opt_in(self):
        configured = settings()
        self.assertEqual(configured.channels(), ())
        self.assertEqual(configured.smtp_tls_mode, "starttls")
        self.assertEqual(configured.timeout_seconds, 10)

    def test_telegram_requires_token_and_chat(self):
        with self.assertRaises(relay_module.ConfigurationError):
            settings(CORVEX_ALERT_TELEGRAM_ENABLED="true")
        with self.assertRaises(relay_module.ConfigurationError):
            settings(
                CORVEX_ALERT_TELEGRAM_ENABLED="true",
                CORVEX_ALERT_TELEGRAM_BOT_TOKEN="secret",
            )

    def test_max_requires_exactly_one_integer_target(self):
        base = {
            "CORVEX_ALERT_MAX_ENABLED": "true",
            "CORVEX_ALERT_MAX_BOT_TOKEN": "secret",
        }
        with self.assertRaises(relay_module.ConfigurationError):
            settings(**base)
        with self.assertRaises(relay_module.ConfigurationError):
            settings(
                **base,
                CORVEX_ALERT_MAX_CHAT_ID="100",
                CORVEX_ALERT_MAX_USER_ID="200",
            )
        with self.assertRaises(relay_module.ConfigurationError):
            settings(**base, CORVEX_ALERT_MAX_CHAT_ID="not-a-number")
        configured = settings(**base, CORVEX_ALERT_MAX_CHAT_ID="100")
        self.assertEqual(configured.channels(), ("max",))

    def test_external_email_requires_working_tls_configuration(self):
        with self.assertRaises(relay_module.ConfigurationError):
            settings(CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED="true")
        with self.assertRaises(relay_module.ConfigurationError):
            settings(
                CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED="true",
                CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS="admin@example.test",
                CORVEX_ALERT_EXTERNAL_SMTP_HOST="smtp.example.test",
                CORVEX_ALERT_EXTERNAL_SMTP_FROM="corvex@example.test",
                CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE="plain",
            )

    def test_boolean_and_timeout_validation_is_strict(self):
        with self.assertRaises(relay_module.ConfigurationError):
            settings(CORVEX_ALERT_MAX_ENABLED="yes")
        with self.assertRaises(relay_module.ConfigurationError):
            settings(CORVEX_ALERT_EXTERNAL_TIMEOUT_SECONDS="1")


class AlertFormattingTest(unittest.TestCase):
    def test_multiple_alerts_are_grouped_into_one_notification(self):
        payload = {
            "status": "firing",
            "alerts": [PAYLOAD["alerts"][0], PAYLOAD["alerts"][0]],
        }
        notification = relay_module.build_notification(payload)
        self.assertIn("(+1)", notification.subject)
        self.assertIn("Alerts: 2", notification.text)
        self.assertEqual(notification.text.count("ServiceDown"), 2)

    def test_empty_alerts_do_not_create_empty_messages(self):
        self.assertIsNone(relay_module.build_notification({"alerts": []}))
        with self.assertRaises(TypeError):
            relay_module.build_notification({})

    def test_messenger_limit_covers_long_descriptions(self):
        payload = {
            "status": "firing",
            "alerts": [
                {
                    "labels": {"alertname": "Long", "severity": "warning"},
                    "annotations": {"description": "x" * 5000},
                }
            ],
        }
        notification = relay_module.build_notification(payload)
        shortened = relay_module.truncate(
            notification.text, relay_module.MESSENGER_TEXT_LIMIT
        )
        self.assertLessEqual(len(shortened), 3900)
        self.assertIn("truncated", shortened)

    def test_tls_never_disables_certificate_or_hostname_checks(self):
        context = relay_module.tls_context()
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.minimum_version, ssl.TLSVersion.TLSv1_2)

    def test_outgoing_api_redirects_are_not_followed(self):
        handler = relay_module.NoRedirectHandler()
        self.assertIsNone(
            handler.redirect_request(
                None, None, 302, "Found", {}, "https://example.test"
            )
        )


class AlertDeliveryTest(unittest.TestCase):
    def test_max_uses_current_api_and_authorization_header(self):
        requests = []

        def fake_urlopen(request, **_kwargs):
            requests.append(request)
            return FakeResponse()

        configured = settings(
            CORVEX_ALERT_MAX_ENABLED="true",
            CORVEX_ALERT_MAX_BOT_TOKEN="max-secret",
            CORVEX_ALERT_MAX_CHAT_ID="123456",
        )
        relay = relay_module.Relay(configured, urlopen=fake_urlopen)

        result = relay.deliver(PAYLOAD)
        self.assertEqual(result.delivered, ("max",))
        self.assertEqual(result.failed, ())
        request = requests[0]
        self.assertTrue(
            request.full_url.startswith(
                "https://platform-api2.max.ru/messages?chat_id=123456"
            )
        )
        self.assertNotIn("max-secret", request.full_url)
        self.assertNotIn("access_token", request.full_url)
        self.assertIn("disable_link_preview=true", request.full_url)
        self.assertEqual(request.get_header("Authorization"), "max-secret")
        body = json.loads(request.data)
        self.assertIn("ServiceDown", body["text"])
        self.assertNotIn("disable_link_preview", body)

    def test_telegram_uses_json_and_does_not_leak_token_in_errors(self):
        configured = settings(
            CORVEX_ALERT_TELEGRAM_ENABLED="true",
            CORVEX_ALERT_TELEGRAM_BOT_TOKEN="telegram-secret",
            CORVEX_ALERT_TELEGRAM_CHAT_ID="-100123",
        )

        def failing_urlopen(_request, **_kwargs):
            raise urllib.error.URLError(
                "https://api.telegram.org/bottelegram-secret/sendMessage"
            )

        relay = relay_module.Relay(configured, urlopen=failing_urlopen)
        notification = relay_module.build_notification(PAYLOAD)
        with self.assertRaises(relay_module.DeliveryError) as raised:
            relay.send_telegram(notification)
        self.assertNotIn("telegram-secret", str(raised.exception))

    def test_external_smtp_uses_starttls_auth_and_message(self):
        fake = FakeSmtp()
        configured = settings(
            CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED="true",
            CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS="admin@example.test,backup@example.test",
            CORVEX_ALERT_EXTERNAL_SMTP_HOST="smtp.example.test",
            CORVEX_ALERT_EXTERNAL_SMTP_FROM="corvex@example.test",
            CORVEX_ALERT_EXTERNAL_SMTP_USERNAME="corvex@example.test",
            CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD="smtp-secret",
        )
        relay = relay_module.Relay(
            configured, smtp_factory=lambda *_args, **_kwargs: fake
        )

        result = relay.deliver(PAYLOAD)
        self.assertEqual(result.delivered, ("external-email",))
        self.assertEqual(result.failed, ())
        self.assertEqual(fake.events[0], "ehlo")
        self.assertEqual(fake.events[1][0], "starttls")
        self.assertEqual(fake.events[2], "ehlo")
        self.assertEqual(
            fake.events[3], ("login", "corvex@example.test", "smtp-secret")
        )
        self.assertEqual(fake.message["To"], "admin@example.test, backup@example.test")
        self.assertIn("ServiceDown", fake.message["Subject"])
        self.assertEqual(fake.events[-1], "quit")

    def test_dynamic_repeat_resolved_and_disabled_categories_are_applied(self):
        requests = []
        now = [1_000.0]

        def fake_urlopen(request, **_kwargs):
            requests.append(request)
            return FakeResponse()

        configured = relay_module.Settings.from_runtime(runtime_payload(), {})
        relay = relay_module.Relay(configured, urlopen=fake_urlopen, clock=lambda: now[0])
        self.assertEqual(relay.deliver(PAYLOAD).delivered, ("telegram",))
        self.assertEqual(relay.deliver(PAYLOAD).state, "suppressed")
        self.assertEqual(len(requests), 1)

        now[0] += 1_800
        self.assertEqual(relay.deliver(PAYLOAD).delivered, ("telegram",))
        resolved = {**PAYLOAD, "status": "resolved", "alerts": [{**PAYLOAD["alerts"][0], "status": "resolved"}]}
        self.assertEqual(relay.deliver(resolved).delivered, ("telegram",))
        self.assertEqual(relay.deliver(PAYLOAD).delivered, ("telegram",))
        self.assertEqual(len(requests), 4)

        disabled = runtime_payload(categories={**runtime_payload()["categories"], "ServiceDown": False})
        filtered_relay = relay_module.Relay(
            relay_module.Settings.from_runtime(disabled, {}), urlopen=fake_urlopen
        )
        self.assertEqual(filtered_relay.deliver(PAYLOAD).state, "filtered")
        self.assertEqual(len(requests), 4)

    def test_partial_failure_retries_only_the_failed_channel(self):
        requests = []
        now = [1_000.0]

        def fake_urlopen(request, **_kwargs):
            requests.append(request)
            if "telegram" in request.full_url and len(requests) == 1:
                raise urllib.error.URLError("offline")
            return FakeResponse()

        payload = runtime_payload(maxEnabled=True, maxChatId="200", maxBotToken="max-secret")
        relay = relay_module.Relay(
            relay_module.Settings.from_runtime(payload, {}),
            urlopen=fake_urlopen,
            clock=lambda: now[0],
        )
        with patch.object(relay_module, "LOG"):
            first = relay.deliver(PAYLOAD)
            second = relay.deliver(PAYLOAD)
        self.assertEqual(first.delivered, ("max",))
        self.assertEqual(first.failed, ("telegram",))
        self.assertEqual(second.delivered, ("telegram",))
        self.assertEqual(second.failed, ())
        self.assertEqual(len(requests), 3)

    def test_no_enabled_channel_performs_no_network_request(self):
        def unexpected(*_args, **_kwargs):
            raise AssertionError("network request must not happen")

        relay = relay_module.Relay(settings(), urlopen=unexpected)
        result = relay.deliver(PAYLOAD)
        self.assertEqual(result, relay_module.DeliveryResult((), ()))
        status, body = relay_module.delivery_response(result)
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "ok")

    def test_one_failed_channel_does_not_block_another(self):
        requests = []

        def fake_urlopen(request, **_kwargs):
            requests.append(request)
            if "telegram" in request.full_url:
                raise urllib.error.URLError("offline")
            return FakeResponse()

        configured = settings(
            CORVEX_ALERT_TELEGRAM_ENABLED="true",
            CORVEX_ALERT_TELEGRAM_BOT_TOKEN="telegram-secret",
            CORVEX_ALERT_TELEGRAM_CHAT_ID="100",
            CORVEX_ALERT_MAX_ENABLED="true",
            CORVEX_ALERT_MAX_BOT_TOKEN="max-secret",
            CORVEX_ALERT_MAX_CHAT_ID="200",
        )
        relay = relay_module.Relay(configured, urlopen=fake_urlopen)
        with patch.object(relay_module, "LOG"):
            result = relay.deliver(PAYLOAD)
        self.assertEqual(len(requests), 2)
        self.assertEqual(result.delivered, ("max",))
        self.assertEqual(result.failed, ("telegram",))
        status, body = relay_module.delivery_response(result)
        self.assertEqual(status, 200, "partial success must not duplicate MAX on retry")
        self.assertEqual(body["status"], "partial-delivery")
        self.assertNotIn("max-secret", str(body))

    def test_total_delivery_failure_is_retryable(self):
        configured = settings(
            CORVEX_ALERT_TELEGRAM_ENABLED="true",
            CORVEX_ALERT_TELEGRAM_BOT_TOKEN="telegram-secret",
            CORVEX_ALERT_TELEGRAM_CHAT_ID="100",
        )

        def failing_urlopen(_request, **_kwargs):
            raise urllib.error.URLError("offline")

        relay = relay_module.Relay(configured, urlopen=failing_urlopen)
        with patch.object(relay_module, "LOG"):
            result = relay.deliver(PAYLOAD)
        status, body = relay_module.delivery_response(result)
        self.assertEqual(status, 502)
        self.assertEqual(body["status"], "delivery-failed")
        self.assertEqual(result.delivered, ())


class RuntimeConfigProviderTest(unittest.TestCase):
    def test_validated_revision_is_cached_0600_and_survives_admin_outage(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "runtime.json"
            env = {
                "CORVEX_INTERNAL_API_TOKEN": "internal-secret",
                "CORVEX_ALERT_RUNTIME_CACHE": str(cache),
            }
            first_relay = relay_module.Relay(settings())
            provider = relay_module.RuntimeConfigProvider(
                first_relay, env, opener=lambda *_args, **_kwargs: StaticResponse(runtime_payload())
            )
            provider.refresh()

            self.assertEqual(first_relay.settings.channels(), ("telegram",))
            self.assertEqual(first_relay.runtime_status["revision"], 5)
            self.assertEqual(first_relay.runtime_status["source"], "ADMIN")
            self.assertEqual(stat.S_IMODE(cache.stat().st_mode), 0o600)
            self.assertIn("telegram-secret", cache.read_text(encoding="utf-8"))
            self.assertNotIn("telegram-secret", str(first_relay.runtime_status))

            restored = relay_module.Relay(settings())
            restored_provider = relay_module.RuntimeConfigProvider(restored, env)
            restored_provider.load_cache()
            self.assertEqual(restored.settings.channels(), ("telegram",))
            self.assertEqual(restored.runtime_status["source"], "CACHE")

            def offline(*_args, **_kwargs):
                raise urllib.error.URLError("admin offline")

            restored_provider.opener = offline
            restored_provider.refresh()
            self.assertEqual(restored.settings.channels(), ("telegram",))
            self.assertEqual(restored.runtime_status["refresh"], "FAILED")
            self.assertEqual(restored.runtime_status["revision"], 5)

    def test_invalid_runtime_document_does_not_replace_working_settings(self):
        relay = relay_module.Relay(settings())
        payload = runtime_payload(telegramBotToken={"not": "a string"})
        provider = relay_module.RuntimeConfigProvider(
            relay,
            {"CORVEX_INTERNAL_API_TOKEN": "secret"},
            opener=lambda *_args, **_kwargs: StaticResponse(payload),
        )
        with patch.object(relay_module, "LOG"):
            provider.refresh()
        self.assertEqual(relay.settings.channels(), ())
        self.assertEqual(relay.runtime_status["refresh"], "FAILED")


class AlertHttpTest(unittest.TestCase):
    def setUp(self):
        self.server = relay_module.RelayHttpServer(
            ("127.0.0.1", 0), relay_module.Relay(settings())
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_health_and_webhook_with_all_external_channels_disabled(self):
        with urllib.request.urlopen(f"{self.base_url}/healthz", timeout=2) as response:
            health = json.load(response)
        self.assertEqual(health["status"], "UP")
        self.assertEqual(health["channels"], [])
        self.assertEqual(health["runtime"]["revision"], 0)
        self.assertNotIn("token", str(health).lower())

        request = urllib.request.Request(
            f"{self.base_url}/alerts/webhook",
            data=json.dumps(PAYLOAD).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=2) as response:
            result = json.load(response)
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["delivered"], [])
        self.assertEqual(result["failed"], [])

    def test_malformed_json_is_a_client_error(self):
        request = urllib.request.Request(
            f"{self.base_url}/alerts/webhook",
            data=b"{not-json",
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(raised.exception.code, 400)
        self.assertEqual(
            json.load(raised.exception)["status"], "invalid-alertmanager-payload"
        )


if __name__ == "__main__":
    unittest.main()
