from __future__ import annotations

import io
import os
import sys
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timezone
from email.message import EmailMessage
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import alert_e2e
from smoke_common import SmokeError

RUN_ID = "a" * 32


class FakeImap:
    def __init__(self, raw: bytes, identifiers: bytes = b"7") -> None:
        self.raw = raw
        self.identifiers = identifiers
        self.readonly = False

    def select(self, folder: str, readonly: bool = False):
        assert folder == "INBOX"
        self.readonly = readonly
        return "OK", [b"1"]

    def uid(self, command: str, *arguments):
        if command == "SEARCH":
            assert arguments == ("HEADER", "Subject", RUN_ID)
            return "OK", [self.identifiers]
        assert command == "FETCH"
        assert arguments == (b"7", "(BODY.PEEK[])")
        return "OK", [(b"7 (BODY[] {123}", self.raw), b")"]


def alert_raw(run_id: str, subject: str | None = None) -> bytes:
    message = EmailMessage()
    message["From"] = "corvex-alert@company.local"
    message["To"] = "admin@company.local"
    message["Subject"] = subject or (
        f"[Corvex warning] CorvexAlertSmoke (alert-smoke-{run_id})"
    )
    message.set_content(f"Corvex alert smoke {run_id}\n")
    return message.as_bytes()


class AlertE2eTest(unittest.TestCase):
    def test_submit_uses_standard_alertname_and_bounded_expiry(self):
        with patch.object(alert_e2e, "compose_exec", return_value="") as execute:
            details = alert_e2e.submit_alert(RUN_ID, 180, 30)

        service, arguments, timeout = execute.call_args.args
        self.assertEqual("alertmanager", service)
        self.assertEqual(30, timeout)
        self.assertIn("alertname=CorvexAlertSmoke", arguments)
        self.assertIn(f"instance=alert-smoke-{RUN_ID}", arguments)
        self.assertIn(f"corvex_smoke_id={RUN_ID}", arguments)
        self.assertFalse(any("alertName=" in item for item in arguments))
        self.assertTrue(any(item.startswith("--end=") for item in arguments))
        self.assertEqual("CorvexAlertSmoke", details["alertname"])

    def test_recipient_must_be_present_in_effective_configuration(self):
        runtime = (
            '{"revision": 4, "internalEmailEnabled": true, '
            '"internalRecipients": "other@company.local, admin@company.local"}'
        )
        with patch.object(alert_e2e, "compose_exec", return_value=runtime) as execute:
            details = alert_e2e.check_configured_recipient("admin@company.local", 30)
            self.assertEqual(2, details["configured_recipients"])
            self.assertEqual(4, details["runtime_revision"])
            with self.assertRaises(SmokeError):
                alert_e2e.check_configured_recipient("missing@company.local", 30)
        service, arguments, timeout = execute.call_args.args
        self.assertEqual("alert-relay", service)
        self.assertEqual(30, timeout)
        self.assertIn("/internal/alerts/runtime-status", arguments[2])
        self.assertNotIn("CORVEX_ALERT_RECIPIENTS", arguments[2])

    def test_disabled_internal_channel_fails_before_alert_submission(self):
        runtime = (
            '{"revision": 4, "internalEmailEnabled": false, '
            '"internalRecipients": "admin@company.local"}'
        )
        with patch.object(alert_e2e, "compose_exec", return_value=runtime):
            with self.assertRaisesRegex(SmokeError, "канал выключен"):
                alert_e2e.check_configured_recipient("admin@company.local", 30)

    def test_alert_message_is_readonly_and_subject_and_body_are_verified(self):
        raw = alert_raw(RUN_ID)
        client = FakeImap(raw)

        located = alert_e2e.find_alert_message(client, RUN_ID)

        self.assertEqual(raw, located)
        self.assertTrue(client.readonly)

    def test_wrong_alertname_in_subject_is_rejected(self):
        client = FakeImap(
            alert_raw(
                RUN_ID,
                subject=f"[Corvex warning] alert (alert-smoke-{RUN_ID})",
            )
        )

        with self.assertRaises(SmokeError):
            alert_e2e.find_alert_message(client, RUN_ID)

    def test_duplicate_alert_messages_are_rejected(self):
        client = FakeImap(alert_raw(RUN_ID), identifiers=b"1 2")

        with self.assertRaises(SmokeError):
            alert_e2e.find_alert_message(client, RUN_ID)

    def test_resolve_uses_same_alert_identity(self):
        with patch.object(alert_e2e, "compose_exec", return_value="") as execute:
            alert_e2e.resolve_alert(RUN_ID, 30)

        arguments = execute.call_args.args[1]
        self.assertIn("alertname=CorvexAlertSmoke", arguments)
        self.assertIn(f"instance=alert-smoke-{RUN_ID}", arguments)
        self.assertIn(f"corvex_smoke_id={RUN_ID}", arguments)

    def test_delivery_failure_still_resolves_submitted_alert(self):
        args = SimpleNamespace(
            host="mail.company.local",
            username="admin@company.local",
            ca_file=None,
            timeout=30,
            wait_seconds=180,
            poll_seconds=5,
            report=None,
            project=None,
        )
        with (
            patch.object(alert_e2e, "parse_args", return_value=args),
            patch.dict(os.environ, {"CORVEX_TEST_ALERT_PASSWORD": "secret"}),
            patch.object(alert_e2e, "make_ssl_context", return_value=object()),
            patch.object(alert_e2e, "check_configured_recipient", return_value={}),
            patch.object(alert_e2e, "submit_alert", return_value={}),
            patch.object(
                alert_e2e, "check_delivery", side_effect=SmokeError("not delivered")
            ),
            patch.object(alert_e2e, "resolve_alert", return_value={}) as resolve,
            redirect_stdout(io.StringIO()),
        ):
            exit_code = alert_e2e.main()

        self.assertEqual(1, exit_code)
        resolve.assert_called_once()
        self.assertRegex(resolve.call_args.args[0], r"^[0-9a-f]{32}$")
        self.assertEqual(30, resolve.call_args.args[1])

    def test_compose_exec_passes_project_so_a_renamed_stand_is_visible(self):
        # Стенд приёмки поднимается с -p corvex-fresh. Без проекта compose ищет
        # контейнеры проекта corvex-clean и отвечает «service is not running».
        with patch.object(alert_e2e.subprocess, "run") as run:
            run.return_value = SimpleNamespace(returncode=0, stdout="ok", stderr="")
            with patch.object(alert_e2e, "COMPOSE_PROJECT", "corvex-fresh"):
                alert_e2e.compose_exec("alert-relay", ["python", "-c", "pass"], 30)

        command = run.call_args.args[0]
        self.assertEqual(
            ["docker", "compose", "-p", "corvex-fresh", "exec", "-T", "alert-relay",
             "python", "-c", "pass"],
            command,
        )

    def test_compose_exec_without_project_keeps_the_default(self):
        with patch.object(alert_e2e.subprocess, "run") as run:
            run.return_value = SimpleNamespace(returncode=0, stdout="ok", stderr="")
            with patch.object(alert_e2e, "COMPOSE_PROJECT", None):
                alert_e2e.compose_exec("alert-relay", ["true"], 30)

        self.assertEqual(
            ["docker", "compose", "exec", "-T", "alert-relay", "true"],
            run.call_args.args[0],
        )

    def test_missing_service_hint_mentions_the_project(self):
        with patch.object(alert_e2e.subprocess, "run") as run:
            run.return_value = SimpleNamespace(
                returncode=1,
                stdout="",
                stderr='service "alert-relay" is not running',
            )
            with patch.object(alert_e2e, "COMPOSE_PROJECT", None):
                with self.assertRaises(SmokeError) as caught:
                    alert_e2e.compose_exec("alert-relay", ["true"], 30)

        self.assertIn("--project", str(caught.exception))

    def test_project_is_validated_before_reaching_docker(self):
        with patch.object(alert_e2e.sys, "argv", ["alert_e2e.py", "--username",
                                                  "admin@company.local",
                                                  "--project", "corvex; rm -rf /"]):
            with self.assertRaises(SystemExit):
                alert_e2e.parse_args()

    def test_transient_connection_abort_is_retried_until_the_message_appears(self):
        # WinError 10053 на Windows: сервер оборвал уже открытую сессию.
        # Одна такая ошибка не должна хоронить ожидание письма целиком.
        attempts = {"open": 0}

        def flaky_open(*_args, **_kwargs):
            attempts["open"] += 1
            if attempts["open"] < 3:
                raise ConnectionResetError(10053, "An established connection was aborted")
            return SimpleNamespace(logout=lambda: None), {
                "tls": "TLSv1.3", "cipher": "TLS_AES_256_GCM_SHA384",
            }

        with (
            patch.object(alert_e2e, "open_imap", side_effect=flaky_open),
            patch.object(alert_e2e, "find_alert_message", return_value=b"raw"),
            patch.object(alert_e2e.time, "sleep"),
        ):
            located = alert_e2e.wait_for_alert(
                "mail.company.local", "admin@company.local", "secret", RUN_ID,
                object(), 30, 180, 5,
            )

        self.assertEqual(3, located.attempts)
        self.assertEqual(b"raw", located.raw)

    def test_repeated_connection_failure_reports_the_last_error(self):
        with (
            patch.object(
                alert_e2e, "open_imap",
                side_effect=ConnectionResetError(10053, "aborted by host"),
            ),
            patch.object(alert_e2e.time, "sleep"),
        ):
            with self.assertRaises(SmokeError) as caught:
                alert_e2e.wait_for_alert(
                    "mail.company.local", "admin@company.local", "secret", RUN_ID,
                    object(), 30, 0, 5,
                )

        self.assertIn("ConnectionResetError", str(caught.exception))

    def test_login_processing_failure_points_at_the_server_side_cause(self):
        # «processing failed» означает: пароль принят, упал следующий шаг.
        # Раньше скрипт советовал смотреть alertmanager, хотя письма никто не искал.
        log = (
            "2026-11-20T10:41:00 ERROR o.a.j.i.p.AbstractAuthProcessor - "
            "Error encountered while login\n"
            "org.apache.james.mailbox.exception.MailboxException: "
            "mail-storage-service недоступен или вернул ошибку: HTTP 503\n"
            "2026-11-20T10:41:01 INFO — не относится к делу\n"
        )
        finished = SimpleNamespace(returncode=0, stdout=log, stderr="")
        with patch.object(alert_e2e.subprocess, "run", return_value=finished):
            hint = alert_e2e.login_failure_hint(
                "error: b'LOGIN processing failed.'"
            )

        self.assertIn("сервер принял пароль", hint.lower())
        self.assertIn("mail-storage-service", hint)
        self.assertIn("HTTP 503", hint)
        self.assertNotIn("alertmanager", hint)

    def test_invalid_login_hint_sends_you_to_login_probe(self):
        hint = alert_e2e.login_failure_hint(
            "error: b'LOGIN failed. Invalid login/password.'"
        )
        self.assertIn("пароль не подошёл", hint.lower())
        self.assertIn("login_probe.py --imap", hint)

    def test_no_error_means_no_guess(self):
        self.assertEqual("", alert_e2e.login_failure_hint(None))

    def test_login_diagnostics_survive_missing_docker(self):
        with patch.object(alert_e2e.subprocess, "run", side_effect=OSError("no docker")):
            self.assertEqual([], alert_e2e.imap_login_diagnostics())

    def test_rfc3339_is_utc(self):
        value = datetime(2026, 11, 3, 12, 30, tzinfo=timezone.utc)  # noqa: UP017
        self.assertEqual("2026-11-03T12:30:00Z", alert_e2e.rfc3339(value))


if __name__ == "__main__":
    unittest.main()
