# ruff: noqa: I001
from __future__ import annotations

import io
import json
import ssl
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from typing import ClassVar
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import mail_smoke
import runtime_smoke
from smoke_common import SmokeError, SmokeReport, make_ssl_context


RAW_MESSAGE = (
    b"From: sender@example.test\r\n"
    b"To: recipient@example.test\r\n"
    b"Subject: private-test-subject\r\n"
    b"MIME-Version: 1.0\r\n"
    b"Content-Type: multipart/mixed; boundary=smoke\r\n\r\n"
    b"--smoke\r\nContent-Type: text/plain\r\n\r\nbody\r\n"
    b"--smoke\r\nContent-Type: application/octet-stream\r\n"
    b"Content-Disposition: attachment; filename=test.bin\r\n"
    b"Content-Transfer-Encoding: base64\r\n\r\nAA==\r\n--smoke--\r\n"
)


class FakeTlsSocket:
    def version(self):
        return "TLSv1.3"

    def cipher(self):
        return ("TLS_AES_256_GCM_SHA384", "TLSv1.3", 256)


class SmokeReportTest(unittest.TestCase):
    def test_skip_is_incomplete_and_json_is_machine_readable(self):
        report = SmokeReport("test", Path("."))
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "report.json"
            with (
                redirect_stdout(io.StringIO()),
                patch("smoke_common.git_sha", return_value="test-sha"),
            ):
                report.add("available", "PASS", details={"count": 1})
                report.add("empty mailbox", "SKIP", error="nothing to fetch")
                report.write(destination, {"readonly": True})
            payload = json.loads(destination.read_text(encoding="utf-8"))
            self.assertEqual(report.exit_code, 3)
            self.assertEqual(payload["git_sha"], "test-sha")
            self.assertEqual(payload["summary"], {"pass": 1, "fail": 0, "skip": 1})
            self.assertTrue(payload["metadata"]["readonly"])


class RuntimeSmokeTest(unittest.TestCase):
    def test_profiles_are_filtered(self):
        config = {
            "services": {
                "frontend": {},
                "grafana": {"profiles": ["monitoring"]},
                "other": {"profiles": ["debug"]},
            }
        }
        self.assertEqual(runtime_smoke.select_services(config, False), ["frontend"])
        self.assertEqual(
            runtime_smoke.select_services(config, True), ["frontend", "grafana"]
        )

    def test_healthcheck_is_mandatory_when_present(self):
        with self.assertRaises(SmokeError):
            runtime_smoke.evaluate_state(
                {"Running": True, "Health": {"Status": "unhealthy"}}
            )
        self.assertEqual(
            runtime_smoke.evaluate_state(
                {"Running": True, "Health": {"Status": "healthy"}}
            ),
            "healthy",
        )
        self.assertEqual(
            runtime_smoke.evaluate_state({"Running": True}), "running (без healthcheck)"
        )

    def test_stopped_container_fails(self):
        with self.assertRaises(SmokeError):
            runtime_smoke.evaluate_state({"Running": False, "Status": "exited"})

    def test_tls_verification_stays_enabled(self):
        context = make_ssl_context()
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.minimum_version, ssl.TLSVersion.TLSv1_2)

    def test_web_url_cannot_leak_query_into_report(self):
        with self.assertRaises(SmokeError):
            runtime_smoke.validate_web_url("https://mail.example.test/?token=secret")


class FakeSmtpWithoutStartTls:
    instance = None

    def __init__(self, *args, **kwargs):
        self.closed = False
        FakeSmtpWithoutStartTls.instance = self

    def ehlo(self):
        return 250, b"hello"

    def has_extn(self, name):
        return False

    def close(self):
        self.closed = True


class FakeImap:
    instance = None

    def __init__(self, *args, **kwargs):
        self.sock = FakeTlsSocket()
        self.readonly = None
        self.logged_in = False
        self.logged_out = False
        self.starttls_called = False
        self.events = []
        FakeImap.instance = self

    def capability(self):
        return "OK", [b"CAPABILITY IMAP4rev1 STARTTLS"]

    def starttls(self, ssl_context=None):
        self.starttls_called = True
        self.events.append("starttls")
        return "OK", [b"Begin TLS"]

    def login(self, username, password):
        self.logged_in = True
        self.events.append("login")
        return "OK", [b"authenticated"]

    def list(self):
        return "OK", [b'(\\HasNoChildren) "/" "INBOX"']

    def select(self, mailbox, readonly=False):
        self.readonly = readonly
        return "OK", [b"1"]

    def search(self, charset, criterion):
        return "OK", [b"1"]

    def fetch(self, message_id, query):
        if "RFC822.SIZE" in query:
            return "OK", [b"1 (RFC822.SIZE %d)" % len(RAW_MESSAGE)]
        return "OK", [(b"1 (BODY[] {%d}" % len(RAW_MESSAGE), RAW_MESSAGE), b")"]

    def logout(self):
        self.logged_out = True
        return "BYE", [b"logout"]


class FakePop3:
    instances: ClassVar[list[FakePop3]] = []

    def __init__(self, *args, **kwargs):
        self.sock = FakeTlsSocket()
        self.quit_called = False
        FakePop3.instances.append(self)

    def user(self, username):
        return b"+OK"

    def pass_(self, password):
        return b"+OK"

    def stat(self):
        return 1, len(RAW_MESSAGE)

    def list(self, which=None):
        return b"+OK", [b"1 %d" % len(RAW_MESSAGE)], len(RAW_MESSAGE)

    def uidl(self, which=None):
        return b"+OK", [b"1 stable-test-uid"], 0

    def retr(self, which):
        return b"+OK", RAW_MESSAGE.splitlines(), len(RAW_MESSAGE)

    def quit(self):
        self.quit_called = True
        return b"+OK"

    def close(self):
        pass


class MailSmokeTest(unittest.TestCase):
    def test_smtp_refuses_auth_without_starttls(self):
        with (
            patch.object(mail_smoke.smtplib, "SMTP", FakeSmtpWithoutStartTls),
            self.assertRaisesRegex(SmokeError, "STARTTLS"),
        ):
            mail_smoke.check_smtp(
                "mail.example.test", "user", "password", make_ssl_context(), 5
            )
        self.assertTrue(FakeSmtpWithoutStartTls.instance.closed)

    def test_imap_is_readonly_and_fetches_mime(self):
        with patch.object(mail_smoke.imaplib, "IMAP4_SSL", FakeImap):
            details = mail_smoke.check_imap(
                "mail.example.test",
                "user",
                "password",
                make_ssl_context(),
                5,
                10000,
                implicit_tls=True,
            )
        self.assertTrue(FakeImap.instance.logged_in)
        self.assertTrue(FakeImap.instance.readonly)
        self.assertTrue(FakeImap.instance.logged_out)
        self.assertEqual(details["attachments"], 1)
        self.assertTrue(details["readonly"])

    def test_imap_starttls_precedes_login(self):
        with patch.object(mail_smoke.imaplib, "IMAP4", FakeImap):
            mail_smoke.check_imap(
                "mail.example.test",
                "user",
                "password",
                make_ssl_context(),
                5,
                10000,
                implicit_tls=False,
            )
        self.assertTrue(FakeImap.instance.starttls_called)
        self.assertLess(
            FakeImap.instance.events.index("starttls"),
            FakeImap.instance.events.index("login"),
        )

    def test_pop3_reconnect_has_stable_uidl_without_dele(self):
        FakePop3.instances = []
        with patch.object(mail_smoke.poplib, "POP3_SSL", FakePop3):
            details = mail_smoke.check_pop3(
                "mail.example.test", "user", "password", make_ssl_context(), 5, 10000
            )
        self.assertEqual(len(FakePop3.instances), 2)
        self.assertTrue(all(instance.quit_called for instance in FakePop3.instances))
        self.assertTrue(details["uidl_stable_after_reconnect"])
        self.assertFalse(details["dele_sent"])
        self.assertEqual(details["attachments"], 1)


if __name__ == "__main__":
    unittest.main()
