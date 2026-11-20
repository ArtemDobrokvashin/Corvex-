from __future__ import annotations

import io
import json
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import login_probe


def run(answer, argv=None):
    argv = argv or ["--username", "admin@company.local"]
    with (
        patch.object(login_probe, "ask_password_with_project", return_value=json.dumps(answer)),
        redirect_stdout(io.StringIO()) as out,
        redirect_stderr(io.StringIO()) as err,
    ):
        code = login_probe.main(argv)
    return code, out.getvalue(), err.getvalue()


class LoginProbeTest(unittest.TestCase):
    def test_accepted_main_password_reports_method(self):
        code, out, _ = run({"valid": True, "userId": "u-1", "method": "PASSWORD"})
        self.assertEqual(0, code)
        self.assertIn("ПРИНЯТ", out)
        self.assertIn("основной пароль", out)

    def test_accepted_app_password_is_distinguished(self):
        code, out, _ = run({"valid": True, "userId": "u-1", "method": "APP_PASSWORD"})
        self.assertEqual(0, code)
        self.assertIn("пароль приложения", out)

    def test_rejection_lists_the_real_causes_not_guesses(self):
        code, out, _ = run({"valid": False, "userId": None, "method": None})
        self.assertEqual(1, code)
        self.assertIn("ОТКАЗ", out)
        self.assertIn("totp_enabled", out)

    def test_command_passes_the_project_and_hides_password_from_argv_echo(self):
        captured = {}

        def fake_run(command, **_kwargs):
            captured["command"] = command
            return SimpleNamespace(returncode=0, stdout='{"valid": true, "method": "PASSWORD"}', stderr="")

        args = SimpleNamespace(
            username="admin@company.local", project="corvex-fresh", timeout=30,
        )
        with (
            patch.object(login_probe.subprocess, "run", side_effect=fake_run),
            patch.dict(login_probe.os.environ, {"CORVEX_TEST_ALERT_PASSWORD": "secret-value"}),
        ):
            login_probe.ask_password_with_project(args)

        command = captured["command"]
        self.assertIn("-p", command)
        self.assertEqual("corvex-fresh", command[command.index("-p") + 1])
        self.assertIn("exec", command)
        self.assertIn("CORVEX_PROBE_PASSWORD=secret-value", command)

    def test_empty_password_is_refused_before_docker(self):
        with (
            patch.object(login_probe.getpass, "getpass", return_value=""),
            patch.dict(login_probe.os.environ, {}, clear=False),
            redirect_stderr(io.StringIO()) as err,
        ):
            login_probe.os.environ.pop("CORVEX_TEST_ALERT_PASSWORD", None)
            code = login_probe.main(["--username", "admin@company.local"])
        self.assertEqual(2, code)
        self.assertIn("Пустой пароль", err.getvalue())

    def test_project_name_is_validated(self):
        with self.assertRaises(SystemExit):
            login_probe.parse_args(["--username", "a@b.c", "--project", "bad name"])

    def test_docker_failure_does_not_echo_the_password(self):
        def fake_run(command, **_kwargs):
            return SimpleNamespace(
                returncode=1, stdout="", stderr="docker failed for secret-value reason",
            )

        args = SimpleNamespace(
            username="admin@company.local", project=None, timeout=30,
        )
        with (
            patch.object(login_probe.subprocess, "run", side_effect=fake_run),
            patch.dict(login_probe.os.environ, {"CORVEX_TEST_ALERT_PASSWORD": "secret-value"}),
        ):
            with self.assertRaises(RuntimeError) as caught:
                login_probe.ask_password_with_project(args)

        self.assertNotIn("secret-value", str(caught.exception))
        self.assertIn("***", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
