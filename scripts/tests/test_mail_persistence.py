from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import mail_e2e
import mail_persistence
from smoke_common import SmokeError, make_ssl_context

SENDER = "petrova@company.local"
RECIPIENT = "admin@company.local"
RUN_ID = "180c0e58ddae469eb5e22cfccf3f9139"


class FakeMailbox:
    def __init__(self, messages):
        self.messages = messages
        self.current = None
        self.events = []
        self.next_uid = 42

    def select(self, folder, readonly=False):
        self.current = folder
        self.events.append(("select", folder, readonly))
        if folder not in self.messages:
            return "NO", [b"no such folder"]
        return "OK", [str(len(self.messages[folder])).encode()]

    def _message(self, uid):
        for message in self.messages[self.current]:
            if message["uid"] == uid:
                return message
        raise AssertionError("UID not found")

    def uid(self, command, *args):
        self.events.append((command, *args))
        if command == "SEARCH":
            flagged = "FLAGGED" in args
            identifiers = [
                str(message["uid"]).encode()
                for message in self.messages[self.current]
                if not flagged or "\\Flagged" in message["flags"]
            ]
            return "OK", [b" ".join(identifiers)]
        if command == "FETCH":
            uid = int(args[0])
            query = args[1]
            message = self._message(uid)
            if query == "(FLAGS)":
                flags = " ".join(sorted(message["flags"])).encode()
                return "OK", [b"%d FETCH (FLAGS (%s))" % (uid, flags)]
            return "OK", [
                (b"%d (BODY[] {%d}" % (uid, len(message["raw"])), message["raw"]),
                b")",
            ]
        if command == "MOVE":
            source_uid = int(args[0])
            destination = args[1]
            message = self._message(source_uid)
            self.messages[self.current] = [
                item
                for item in self.messages[self.current]
                if item["uid"] != source_uid
            ]
            moved = dict(message, uid=self.next_uid)
            self.messages[destination].append(moved)
            return "OK", [b"Moved"]
        if command == "STORE":
            uid = int(args[0])
            flags = args[-1]
            message = self._message(uid)
            if "\\Flagged" in flags:
                message["flags"].add("\\Flagged")
            if "\\Deleted" in flags:
                message["flags"].add("\\Deleted")
            return "OK", [b"Stored"]
        if command == "EXPUNGE":
            uid = int(args[0])
            message = self._message(uid)
            assert "\\Deleted" in message["flags"]
            self.messages[self.current] = [
                item for item in self.messages[self.current] if item["uid"] != uid
            ]
            return "OK", [b"Expunged"]
        raise AssertionError(f"Unexpected UID command: {command}")

    def create(self, folder):
        self.events.append(("create", folder))
        if folder in self.messages:
            return "NO", [b"exists"]
        self.messages[folder] = []
        return "OK", [b"created"]

    def delete(self, folder):
        self.events.append(("delete", folder))
        assert not self.messages[folder]
        del self.messages[folder]
        return "OK", [b"deleted"]

    def expunge(self):
        raise AssertionError("Global EXPUNGE is forbidden in this test")

    def logout(self):
        self.events.append(("logout",))
        return "BYE", [b"logout"]


def message(uid, raw, *flags):
    return {"uid": uid, "raw": raw, "flags": set(flags)}


class MailPersistenceTest(unittest.TestCase):
    def setUp(self):
        self.prepared = mail_e2e.prepare_message(SENDER, RECIPIENT, RUN_ID)
        self.folder = mail_persistence.temp_folder(RUN_ID)
        self.tls = {"tls": "TLSv1.3"}

    def test_run_id_is_strictly_scoped(self):
        self.assertEqual(mail_persistence.validate_run_id(RUN_ID.upper()), RUN_ID)
        for invalid in ("*", "1234", "z" * 32, RUN_ID + "00"):
            with self.subTest(invalid=invalid), self.assertRaises(SmokeError):
                mail_persistence.validate_run_id(invalid)

    def test_find_message_parses_flags_and_rejects_duplicates(self):
        client = FakeMailbox(
            {"INBOX": [message(7, self.prepared.raw, "\\Seen", "\\Flagged")]}
        )
        found = mail_persistence.find_message(client, "INBOX", RUN_ID, readonly=True)
        self.assertEqual(found.uid, 7)
        self.assertEqual(found.flags, frozenset({"\\Seen", "\\Flagged"}))
        client = FakeMailbox(
            {"INBOX": [message(7, self.prepared.raw), message(8, self.prepared.raw)]}
        )
        with self.assertRaisesRegex(SmokeError, "дубли"):
            mail_persistence.find_message(client, "INBOX", RUN_ID, readonly=True)

    def test_move_uses_uid_move_and_preserves_mime(self):
        client = FakeMailbox({"INBOX": [message(7, self.prepared.raw)]})
        with patch.object(
            mail_persistence, "open_imap", return_value=(client, self.tls)
        ):
            details = mail_persistence.move_to_temp(
                "mail.company.local",
                RECIPIENT,
                "secret",
                self.folder,
                RUN_ID,
                SENDER,
                RECIPIENT,
                self.prepared,
                make_ssl_context(),
                5,
            )
        self.assertEqual(details["source_uid"], 7)
        self.assertEqual(details["destination_uid"], 42)
        self.assertEqual(client.messages["INBOX"], [])
        self.assertEqual(len(client.messages[self.folder]), 1)
        self.assertFalse(any(event[0] == "EXPUNGE" for event in client.events))

    def test_flag_survives_reconnect(self):
        messages = {self.folder: [message(42, self.prepared.raw)]}
        first = FakeMailbox(messages)
        second = FakeMailbox(messages)
        with patch.object(
            mail_persistence,
            "open_imap",
            side_effect=[(first, self.tls), (second, self.tls)],
        ):
            details = mail_persistence.flag_search_reconnect(
                "mail.company.local",
                RECIPIENT,
                "secret",
                self.folder,
                RUN_ID,
                SENDER,
                RECIPIENT,
                self.prepared,
                make_ssl_context(),
                5,
            )
        self.assertTrue(details["reconnect_verified"])
        self.assertIn("\\Flagged", details["flags"])
        self.assertTrue(any(event[0] == "STORE" for event in first.events))

    def test_restart_is_limited_and_healthchecked(self):
        with self.assertRaises(SmokeError):
            mail_persistence.restart_service("postgres", 5, 5)
        with (
            patch.object(mail_persistence, "run_command", return_value="") as command,
            patch.object(
                mail_persistence, "check_service", return_value={"instances": []}
            ) as health,
        ):
            mail_persistence.restart_service("mail-storage-service", 5, 5)
        command.assert_called_once_with(
            ["docker", "compose", "restart", "--timeout", "30", "mail-storage-service"],
            180,
        )
        health.assert_called_once_with("mail-storage-service", 5, 5)

    def test_uid_mismatch_after_restart_fails(self):
        with (
            patch.object(
                mail_persistence,
                "check_existing",
                return_value={"uid": 43, "flags": []},
            ),
            self.assertRaisesRegex(SmokeError, "изменился"),
        ):
            mail_persistence.check_persistence(
                "mail.company.local",
                RECIPIENT,
                "secret",
                self.folder,
                RUN_ID,
                SENDER,
                RECIPIENT,
                self.prepared,
                make_ssl_context(),
                5,
                42,
            )

    def test_cleanup_uses_only_uid_expunge_and_deletes_empty_folder(self):
        client = FakeMailbox(
            {
                self.folder: [message(42, self.prepared.raw)],
                "INBOX": [],
            }
        )
        with patch.object(
            mail_persistence, "open_imap", return_value=(client, self.tls)
        ):
            details = mail_persistence.cleanup_temp_folder(
                "mail.company.local",
                RECIPIENT,
                "secret",
                self.folder,
                RUN_ID,
                make_ssl_context(),
                5,
                42,
            )
        self.assertEqual(details["expunged_uid"], 42)
        self.assertNotIn(self.folder, client.messages)
        self.assertTrue(
            any(event[0] == "EXPUNGE" and event[1] == "42" for event in client.events)
        )
        self.assertIn(("delete", self.folder), client.events)

    def test_cleanup_refuses_non_singleton_folder(self):
        client = FakeMailbox(
            {
                self.folder: [
                    message(42, self.prepared.raw),
                    message(43, self.prepared.raw),
                ],
                "INBOX": [],
            }
        )
        with (
            patch.object(
                mail_persistence, "open_imap", return_value=(client, self.tls)
            ),
            self.assertRaisesRegex(SmokeError, "Отказ UID EXPUNGE"),
        ):
            mail_persistence.cleanup_temp_folder(
                "mail.company.local",
                RECIPIENT,
                "secret",
                self.folder,
                RUN_ID,
                make_ssl_context(),
                5,
                42,
            )
        self.assertFalse(
            any(event[0] in {"STORE", "EXPUNGE"} for event in client.events)
        )

    def args(self, full):
        return SimpleNamespace(
            host="mail.company.local",
            sender=SENDER,
            recipient=RECIPIENT,
            run_id=RUN_ID,
            ca_file=None,
            timeout=5,
            wait_seconds=5,
            allow_mutating=full,
            allow_restart=full,
            report=None,
        )

    def test_full_orchestration_preserves_uid_and_restart_order(self):
        details = {"uid": 42, "flags": ["\\Flagged"]}
        with (
            patch.object(mail_persistence, "parse_args", return_value=self.args(True)),
            patch.object(mail_persistence.getpass, "getpass", return_value="secret"),
            patch.object(mail_persistence, "check_existing", return_value=details),
            patch.object(mail_persistence, "move_to_temp", return_value=details),
            patch.object(
                mail_persistence, "flag_search_reconnect", return_value=details
            ),
            patch.object(
                mail_persistence, "restart_service", return_value={}
            ) as restart,
            patch.object(mail_persistence, "check_persistence", return_value=details),
            patch.object(
                mail_persistence, "cleanup_temp_folder", return_value={}
            ) as cleanup,
            redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(mail_persistence.main(), 0)
        self.assertEqual(
            [call.args[0] for call in restart.call_args_list],
            ["mail-storage-service", "mail-imap-service"],
        )
        cleanup.assert_called_once()

    def test_precheck_orchestration_never_mutates(self):
        details = {"uid": 42, "flags": []}
        with (
            patch.object(mail_persistence, "parse_args", return_value=self.args(False)),
            patch.object(mail_persistence.getpass, "getpass", return_value="secret"),
            patch.object(mail_persistence, "check_existing", return_value=details),
            patch.object(mail_persistence, "move_to_temp") as move,
            redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(mail_persistence.main(), 0)
        move.assert_not_called()

    def test_full_precheck_failure_stops_before_mutating(self):
        details = {"uid": 42, "flags": []}
        with (
            patch.object(mail_persistence, "parse_args", return_value=self.args(True)),
            patch.object(mail_persistence.getpass, "getpass", return_value="secret"),
            patch.object(
                mail_persistence,
                "check_existing",
                side_effect=[SmokeError("missing"), details],
            ),
            patch.object(mail_persistence, "move_to_temp") as move,
            patch.object(mail_persistence, "restart_service") as restart,
            redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(mail_persistence.main(), 1)
        move.assert_not_called()
        restart.assert_not_called()

    def test_partial_permission_flags_are_rejected(self):
        argv = [
            "mail_persistence.py",
            "--sender",
            SENDER,
            "--recipient",
            RECIPIENT,
            "--run-id",
            RUN_ID,
            "--allow-mutating",
        ]
        with (
            patch.object(sys, "argv", argv),
            redirect_stderr(io.StringIO()),
            self.assertRaises(SystemExit) as exit,
        ):
            mail_persistence.parse_args()
        self.assertEqual(exit.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
