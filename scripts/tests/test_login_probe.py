#!/usr/bin/env python3
"""Тесты scripts/login_probe.py: приговор, подсказки по форме и IMAP-ветка."""

from __future__ import annotations

import json
import ssl
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import login_probe  # noqa: E402


class VerdictTests(unittest.TestCase):
    def test_valid_password_reports_the_method_and_user(self):
        code, lines = login_probe.verdict(
            {"valid": True, "method": "APP_PASSWORD", "userId": "abc-123"}
        )
        self.assertEqual(code, 0)
        self.assertIn("SUCCESS: пароль подошёл — пароль приложения", lines[0])
        self.assertIn("abc-123", lines[1])

    def test_success_without_method_does_not_invent_one(self):
        code, lines = login_probe.verdict({"valid": True})
        self.assertEqual(code, 0)
        self.assertIn("метод не указан", lines[0])

    def test_invalid_password_returns_a_checklist_with_shape_notes(self):
        code, lines = login_probe.verdict(
            {"valid": False}, ["форма: 20 символов без дефисов — пароль приложения состоит из 20"]
        )
        self.assertEqual(code, 1)
        self.assertTrue(lines[0].startswith("FAIL"))
        self.assertIn("форма: 20 символов", lines[1])
        self.assertTrue(any("ACTIVE" in line for line in lines))

    def test_missing_valid_flag_is_a_failure_not_a_success(self):
        code, _ = login_probe.verdict({})
        self.assertEqual(code, 1)


class ShapeNotesTests(unittest.TestCase):
    def test_twenty_characters_are_recognised_as_an_app_password(self):
        notes = login_probe.shape_notes_for("abcd" * 5)
        self.assertIn("похоже на пароль приложения", notes[0])

    def test_dashes_are_optional_and_do_not_change_the_length(self):
        grouped = login_probe.shape_notes_for("-".join(["abcd"] * 5))
        self.assertIn("похоже на пароль приложения", grouped[0])
        # В интерфейсе группы по четыре: 20 символов + 4 дефиса = 24 знака.
        self.assertEqual(len("-".join(["abcd"] * 5)), 24)

    def test_wrong_length_is_reported_without_printing_the_password(self):
        notes = login_probe.shape_notes_for("secret")
        self.assertIn("6 символов", notes[0])
        self.assertNotIn("secret", " ".join(notes))

    def test_forbidden_characters_are_named(self):
        notes = login_probe.shape_notes_for("ab0d" * 5)
        self.assertTrue(any("0, O, 1, l или I" in note for note in notes))


class ParseTests(unittest.TestCase):
    def test_username_is_required(self):
        with self.assertRaises(SystemExit):
            login_probe.parse_args([])

    def test_project_defaults_to_the_environment(self):
        with mock.patch.dict("os.environ", {"COMPOSE_PROJECT_NAME": "corvex-fresh"}):
            args = login_probe.parse_args(["--username", "admin@company.local"])
        self.assertEqual(args.project, "corvex-fresh")
        self.assertFalse(args.imap)

    def test_project_with_a_shell_metacharacter_is_rejected(self):
        with self.assertRaises(SystemExit):
            login_probe.parse_args(["--username", "a@b", "--project", "x;rm -rf /"])

    def test_imap_flag_and_timeout(self):
        args = login_probe.parse_args(["--username", "a@b", "--imap", "--timeout", "5"])
        self.assertTrue(args.imap)
        self.assertEqual(args.timeout, 5)

    def test_zero_timeout_is_rejected(self):
        with self.assertRaises(SystemExit):
            login_probe.parse_args(["--username", "a@b", "--timeout", "0"])


class PasswordInputTests(unittest.TestCase):
    def test_environment_password_is_not_echoed(self):
        with mock.patch.dict("os.environ", {"CORVEX_TEST_ALERT_PASSWORD": "abcd" * 5}):
            with mock.patch("getpass.getpass") as hidden:
                self.assertEqual(len(login_probe.prompt_password("a@b")), 20)
        hidden.assert_not_called()

    def test_hidden_prompt_is_used_when_the_variable_is_absent(self):
        with mock.patch.dict("os.environ", {}, clear=True):
            with mock.patch("getpass.getpass", return_value="abcd" * 5) as hidden:
                self.assertEqual(len(login_probe.prompt_password("admin@company.local")), 20)
        hidden.assert_called_once()

    def test_empty_password_is_rejected(self):
        with mock.patch.dict("os.environ", {}, clear=True):
            with mock.patch("getpass.getpass", return_value=""):
                with self.assertRaises(ValueError):
                    login_probe.prompt_password("a@b")


class ComposeCommandTests(unittest.TestCase):
    def test_project_is_passed_explicitly(self):
        command = login_probe.compose_command("corvex-fresh", ["exec", "-T", "svc", "true"])
        self.assertEqual(command[:4], ["docker", "compose", "-p", "corvex-fresh"])

    def test_without_project_compose_uses_the_working_directory(self):
        self.assertEqual(
            login_probe.compose_command(None, ["ps"]), ["docker", "compose", "ps"]
        )


class ProbeTests(unittest.TestCase):
    def setUp(self):
        self.args = login_probe.parse_args(
            ["--username", "admin@company.local", "--project", "corvex-fresh"]
        )

    def test_password_goes_through_the_environment_not_the_command_line(self):
        finished = mock.Mock(returncode=0, stdout='{"valid":true}', stderr="")
        seen = {}

        def capture(command, **kwargs):
            seen["command"], seen["env"] = command, dict(kwargs["env"])
            return finished

        with mock.patch("subprocess.run", side_effect=capture):
            login_probe.probe_auth_service(self.args, "secret-value")
        command = seen["command"]
        self.assertNotIn("secret-value", " ".join(command))
        self.assertIn("CORVEX_PROBE_PASSWORD", command)
        self.assertIn("alert-relay", command)
        self.assertEqual(seen["env"]["CORVEX_PROBE_PASSWORD"], "secret-value")
        self.assertEqual(seen["env"]["CORVEX_PROBE_USERNAME"], "admin@company.local")

    def test_stderr_is_scrubbed_of_the_password(self):
        finished = mock.Mock(returncode=1, stdout="", stderr="auth failed for secret-value")
        with mock.patch("subprocess.run", return_value=finished):
            with self.assertRaises(RuntimeError) as raised:
                login_probe.probe_auth_service(self.args, "secret-value")
        self.assertNotIn("secret-value", str(raised.exception))
        self.assertIn("***", str(raised.exception))


class ImapCheckTests(unittest.TestCase):
    def setUp(self):
        self.args = login_probe.parse_args(["--username", "admin@company.local", "--imap"])

    def test_successful_login_reports_the_tls_version(self):
        client = mock.Mock()
        client.sock.version.return_value = "TLSv1.3"
        with mock.patch("imaplib.IMAP4_SSL", return_value=client):
            ok, detail = login_probe.imap_login_check(self.args, "secret")
        self.assertTrue(ok)
        self.assertIn("TLSv1.3", detail)
        client.login.assert_called_once_with("admin@company.local", "secret")
        client.logout.assert_called_once()

    def test_rejection_text_is_returned_as_bytes_decoded(self):
        client = mock.Mock()
        client.login.side_effect = login_probe.imaplib.IMAP4.error(
            b"LOGIN failed. Invalid login/password."
        )
        with mock.patch("imaplib.IMAP4_SSL", return_value=client):
            ok, detail = login_probe.imap_login_check(self.args, "secret")
        self.assertFalse(ok)
        self.assertIn("Invalid login/password", detail)
        self.assertNotIn("secret", detail)

    def test_network_failure_is_not_reported_as_a_bad_password(self):
        with mock.patch("imaplib.IMAP4_SSL", side_effect=OSError("connection refused")):
            ok, detail = login_probe.imap_login_check(self.args, "secret")
        self.assertFalse(ok)
        self.assertIn("OSError", detail)

    def test_broken_ca_file_is_reported_before_connecting(self):
        args = login_probe.parse_args(
            ["--username", "a@b", "--imap", "--ca-file", "/nope/absent.pem"]
        )
        with mock.patch("imaplib.IMAP4_SSL") as connect:
            ok, detail = login_probe.imap_login_check(args, "secret")
        self.assertFalse(ok)
        self.assertIn("TLS не настроен", detail)
        connect.assert_not_called()


class MainTests(unittest.TestCase):
    def test_success_with_imap_confirms_both_links(self):
        args = login_probe.parse_args(["--username", "a@b", "--imap"])
        with mock.patch.object(login_probe, "parse_args", return_value=args):
            with mock.patch.object(login_probe, "prompt_password", return_value="abcd" * 5):
                with mock.patch.object(
                    login_probe, "probe_auth_service",
                    return_value=json.dumps({"valid": True, "method": "APP_PASSWORD"}),
                ):
                    with mock.patch.object(
                        login_probe, "imap_login_check", return_value=(True, "mail:993")
                    ):
                        code = login_probe.main([])
        self.assertEqual(code, 0)

    def test_auth_ok_but_imap_failing_points_at_the_transport(self):
        args = login_probe.parse_args(["--username", "a@b", "--imap"])
        with mock.patch.object(login_probe, "parse_args", return_value=args):
            with mock.patch.object(login_probe, "prompt_password", return_value="abcd" * 5):
                with mock.patch.object(
                    login_probe, "probe_auth_service",
                    return_value=json.dumps({"valid": True}),
                ):
                    with mock.patch.object(
                        login_probe, "imap_login_check",
                        return_value=(False, "LOGIN failed. Invalid login/password."),
                    ):
                        with mock.patch("sys.stdout", new_callable=lambda: __import__("io").StringIO()) as out:
                            code = login_probe.main([])
        self.assertEqual(code, 1)
        self.assertIn("проблема не в пароле", out.getvalue())

    def test_unreachable_stack_returns_code_two(self):
        args = login_probe.parse_args(["--username", "a@b"])
        with mock.patch.object(login_probe, "parse_args", return_value=args):
            with mock.patch.object(login_probe, "prompt_password", return_value="abcd" * 5):
                with mock.patch.object(
                    login_probe, "probe_auth_service", side_effect=RuntimeError("no container")
                ):
                    with mock.patch("sys.stderr", new_callable=lambda: __import__("io").StringIO()):
                        self.assertEqual(login_probe.main([]), 2)


if __name__ == "__main__":
    unittest.main()
