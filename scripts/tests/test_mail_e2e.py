from __future__ import annotations

import sys
import unittest
from pathlib import Path
from typing import ClassVar
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import mail_e2e
from smoke_common import SmokeError, make_ssl_context

SENDER = "petrova@company.local"
RECIPIENT = "admin@company.local"
RUN_ID = "0123456789abcdef0123456789abcdef"


class FakeTlsSocket:
    def version(self):
        return "TLSv1.3"

    def cipher(self):
        return ("TLS_AES_256_GCM_SHA384", "TLSv1.3", 256)


class FakeSmtp:
    instances: ClassVar[list[FakeSmtp]] = []
    refuse = False

    def __init__(self, *args, **kwargs):
        self.sock = FakeTlsSocket()
        self.events = []
        self.envelope = None
        FakeSmtp.instances.append(self)

    def ehlo(self):
        self.events.append("ehlo")
        return 250, b"hello"

    def has_extn(self, name):
        return name.lower() in {"starttls", "auth"}

    def starttls(self, context=None):
        self.events.append("starttls")
        return 220, b"ready"

    def login(self, username, password):
        self.events.append("login")

    def sendmail(self, sender, recipients, raw):
        self.events.append("sendmail")
        self.envelope = (sender, recipients, raw)
        return {RECIPIENT: (550, b"rejected")} if self.refuse else {}

    def quit(self):
        self.events.append("quit")
        return 221, b"bye"

    def close(self):
        pass


class FakeAppendImap:
    instances: ClassVar[list[FakeAppendImap]] = []

    def __init__(self, *args, **kwargs):
        self.sock = FakeTlsSocket()
        self.events = []
        self.append_args = None
        FakeAppendImap.instances.append(self)

    def login(self, username, password):
        self.events.append("login")
        return "OK", [b"authenticated"]

    def append(self, mailbox, flags, date_time, raw):
        self.events.append("append")
        self.append_args = (mailbox, flags, date_time, raw)
        return "OK", [b"[APPENDUID 1 42] done"]

    def logout(self):
        self.events.append("logout")
        return "BYE", [b"logout"]


class FakeSearchImap:
    def __init__(self, raw, identifiers=b"42"):
        self.raw = raw
        self.identifiers = identifiers
        self.readonly = None
        self.logout_calls = 0

    def select(self, folder, readonly=False):
        self.readonly = readonly
        return "OK", [b"1"]

    def uid(self, command, *args):
        if command == "SEARCH":
            return "OK", [self.identifiers]
        return "OK", [(b"42 (BODY[] {%d}" % len(self.raw), self.raw), b")"]

    def logout(self):
        self.logout_calls += 1
        return "BYE", [b"logout"]


class MailE2eTest(unittest.TestCase):
    def setUp(self):
        FakeSmtp.instances = []
        FakeSmtp.refuse = False
        FakeAppendImap.instances = []
        self.prepared = mail_e2e.prepare_message(SENDER, RECIPIENT, RUN_ID)
        self.located = mail_e2e.LocatedMessage(
            folder="INBOX", raw=self.prepared.raw, tls={"tls": "TLSv1.3"}, attempts=1
        )

    def test_mime_roundtrip_verifies_text_html_and_attachment(self):
        details = mail_e2e.verify_message(
            self.located, RUN_ID, SENDER, RECIPIENT, self.prepared
        )
        self.assertTrue(details["text_html_verified"])
        self.assertEqual(details["attachment_sha256"], self.prepared.attachment_sha256)
        self.assertEqual(details["duplicates"], 0)
        self.assertEqual(details["folder"], "INBOX")

    def test_run_id_mismatch_is_rejected(self):
        with self.assertRaisesRegex(SmokeError, "X-Corvex-Smoke-ID"):
            mail_e2e.verify_message(
                self.located, "different-run", SENDER, RECIPIENT, self.prepared
            )

    def test_attachment_hash_mismatch_is_rejected(self):
        tampered = mail_e2e.PreparedMessage(
            raw=self.prepared.raw,
            attachment_name=self.prepared.attachment_name,
            attachment_sha256="0" * 64,
        )
        with self.assertRaisesRegex(SmokeError, "SHA-256"):
            mail_e2e.verify_message(self.located, RUN_ID, SENDER, RECIPIENT, tampered)

    def test_smtp_tls_auth_and_envelope_order(self):
        with patch.object(mail_e2e.smtplib, "SMTP", FakeSmtp):
            details = mail_e2e.send_message(
                "mail.company.local",
                SENDER,
                "secret",
                RECIPIENT,
                self.prepared,
                make_ssl_context(),
                5,
            )
        client = FakeSmtp.instances[0]
        self.assertLess(client.events.index("starttls"), client.events.index("login"))
        self.assertLess(client.events.index("login"), client.events.index("sendmail"))
        self.assertEqual(client.envelope, (SENDER, [RECIPIENT], self.prepared.raw))
        self.assertEqual(details["accepted_recipients"], 1)

    def test_smtp_refused_recipient_is_failure(self):
        FakeSmtp.refuse = True
        with (
            patch.object(mail_e2e.smtplib, "SMTP", FakeSmtp),
            self.assertRaisesRegex(SmokeError, "отклонил"),
        ):
            mail_e2e.send_message(
                "mail.company.local",
                SENDER,
                "secret",
                RECIPIENT,
                self.prepared,
                make_ssl_context(),
                5,
            )

    def test_sent_append_uses_only_new_message(self):
        with patch.object(mail_e2e.imaplib, "IMAP4_SSL", FakeAppendImap):
            mail_e2e.append_sent(
                "mail.company.local",
                SENDER,
                "secret",
                self.prepared,
                make_ssl_context(),
                5,
                "Sent",
            )
        client = FakeAppendImap.instances[0]
        self.assertEqual(client.events, ["login", "append", "logout"])
        self.assertEqual(
            client.append_args, ("Sent", "(\\Seen)", None, self.prepared.raw)
        )

    def test_uid_search_fetches_one_message_readonly(self):
        client = FakeSearchImap(self.prepared.raw)
        found = mail_e2e.find_run_message(client, ["INBOX"], RUN_ID)
        self.assertEqual(found, ("INBOX", self.prepared.raw))
        self.assertTrue(client.readonly)

    def test_duplicate_run_id_is_failure(self):
        client = FakeSearchImap(self.prepared.raw, identifiers=b"42 43")
        with self.assertRaisesRegex(SmokeError, "дубли"):
            mail_e2e.find_run_message(client, ["INBOX"], RUN_ID)

    def test_wait_for_message_retries_and_logs_out(self):
        client = FakeSearchImap(self.prepared.raw)
        with (
            patch.object(
                mail_e2e, "open_imap", return_value=(client, {"tls": "TLSv1.3"})
            ),
            patch.object(
                mail_e2e,
                "find_run_message",
                side_effect=[None, ("INBOX", self.prepared.raw)],
            ),
            patch.object(mail_e2e.time, "sleep", return_value=None),
        ):
            located = mail_e2e.wait_for_message(
                "mail.company.local",
                RECIPIENT,
                "secret",
                ["INBOX"],
                RUN_ID,
                make_ssl_context(),
                5,
                10,
                1,
            )
        self.assertEqual(located.attempts, 2)
        self.assertEqual(client.logout_calls, 2)

    def test_only_plain_protocol_addresses_are_accepted(self):
        self.assertEqual(mail_e2e.validate_address(SENDER, "sender"), SENDER)
        with self.assertRaises(SmokeError):
            mail_e2e.validate_address("Petrova <petrova@company.local>", "sender")
        with self.assertRaises(SmokeError):
            mail_e2e.validate_address(
                "petrova@company.local\r\nBcc: victim@company.local", "sender"
            )


if __name__ == "__main__":
    unittest.main()
