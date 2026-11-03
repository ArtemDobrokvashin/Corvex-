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
        with patch.object(
            alert_e2e,
            "compose_exec",
            return_value="other@company.local, admin@company.local",
        ):
            details = alert_e2e.check_configured_recipient("admin@company.local", 30)
            self.assertEqual(2, details["configured_recipients"])
            with self.assertRaises(SmokeError):
                alert_e2e.check_configured_recipient("missing@company.local", 30)

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

    def test_rfc3339_is_utc(self):
        value = datetime(2026, 11, 3, 12, 30, tzinfo=timezone.utc)  # noqa: UP017
        self.assertEqual("2026-11-03T12:30:00Z", alert_e2e.rfc3339(value))


if __name__ == "__main__":
    unittest.main()
