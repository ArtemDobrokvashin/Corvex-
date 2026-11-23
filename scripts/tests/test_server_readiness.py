"""Проверки `scripts/server_readiness.py` — запасного варианта на случай сервера без домена.

Тест не ходит по сети и не запускает ssh: проверяется то, что можно проверить без
сервера — разбор фактов, пороги ресурсов, коды возврата и отчёт. Ровно эти части
ломаются молча: скрипт печатает `PASS` на 4 ГиБ памяти, и установка падает позже на
ClamAV.
"""

from __future__ import annotations

import io
import json
import sys
import unittest
import contextlib
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import server_readiness as sr  # noqa: E402


class ParseRemoteFactsTest(unittest.TestCase):
    def test_four_numbers_become_facts(self):
        facts = sr.parse_remote_facts("8\n31\n209715200\n1795447015\n")
        self.assertEqual(8, facts["cpu"])
        self.assertEqual(31, facts["ram_gib"])
        # df отдаёт килобайты; в отчёт идёт ГиБ.
        self.assertEqual(200, facts["disk_free_gib"])
        self.assertEqual(1795447015, facts["server_epoch"])

    def test_empty_or_partial_output_is_an_error_not_zeros(self):
        # Молчаливое «0 CPU, 0 ГиБ» превратилось бы в FAIL «мало ресурсов»
        # вместо честного «сервер не ответил».
        for broken in ("", "8\n31\n", "nginx: command not found\n8\n31\n209715200\n1\n"):
            with self.assertRaises(ValueError):
                sr.parse_remote_facts(broken)


class ThresholdTest(unittest.TestCase):
    def check(self, facts: dict[str, int]) -> dict[str, sr.Check]:
        checks: list[sr.Check] = []
        sr.check_resources(checks, facts)
        return {check.name: check for check in checks}

    def test_minimum_configuration_passes(self):
        result = self.check({"cpu": 4, "ram_gib": 16, "disk_free_gib": 100})
        self.assertEqual({"PASS"}, {check.status for check in result.values()})

    def test_below_minimum_fails_with_the_needed_number(self):
        result = self.check({"cpu": 2, "ram_gib": 4, "disk_free_gib": 19})
        self.assertEqual({"FAIL"}, {check.status for check in result.values()})
        self.assertIn("16", result["ram"].detail)
        self.assertIn("нужно ≥ 4", result["cpu"].detail)


class PortTest(unittest.TestCase):
    def test_ports_are_not_claimed_free_without_an_external_check(self):
        checks: list[sr.Check] = []
        sr.check_ports(checks, "203.0.113.10", publicly_reachable=False)
        self.assertTrue(checks)
        self.assertEqual({"WARN"}, {check.status for check in checks})
        self.assertTrue(all("не проверялся снаружи" in check.detail for check in checks))

    def test_busy_mail_port_is_a_warning_and_an_open_database_is_a_failure(self):
        checks: list[sr.Check] = []
        # 25 занят, 5432 открыт, остальное свободно.
        with mock.patch.object(sr, "tcp_open", side_effect=lambda host, port: port in (25, 5432)):
            sr.check_ports(checks, "203.0.113.10", publicly_reachable=True)
        by_port = {check.name: check for check in checks}
        self.assertEqual("WARN", by_port["port 25"].status)
        self.assertEqual("FAIL", by_port["port 5432"].status)
        self.assertEqual("PASS", by_port["port 443"].status)


class ExitCodeTest(unittest.TestCase):
    def test_fail_wins_over_warn(self):
        checks = [sr.Check("a", "WARN", ""), sr.Check("b", "FAIL", "")]
        self.assertEqual(1, sr.exit_code(checks, strict=False))

    def test_strict_turns_warning_into_failure(self):
        checks = [sr.Check("a", "WARN", "")]
        self.assertEqual(0, sr.exit_code(checks, strict=False))
        self.assertEqual(2, sr.exit_code(checks, strict=True))

    def test_all_pass_is_zero(self):
        self.assertEqual(0, sr.exit_code([sr.Check("a", "PASS", "")], strict=True))


class ReportTest(unittest.TestCase):
    def test_report_is_written_and_names_the_host(self):
        import tempfile

        checks = [sr.Check("cpu", "PASS", "8 vCPU")]
        with tempfile.TemporaryDirectory() as directory:
            path = sr.write_report(checks, Path(directory), "203.0.113.10")
            self.assertTrue(path.exists())
            payload = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual("203.0.113.10", payload["host"])
        self.assertEqual("cpu", payload["checks"][0]["name"])


class SshCommandTest(unittest.TestCase):
    def test_command_has_no_password_and_does_not_prompt(self):
        command = sr.ssh_command("203.0.113.10", "deploy", 2222, "uname -a")
        joined = " ".join(command)
        self.assertIn("deploy@203.0.113.10", joined)
        self.assertIn("-p 2222", joined)
        self.assertIn("BatchMode=yes", joined)
        # Пароля здесь быть не может, а любой флаг вида -o Password... был бы регрессом.
        self.assertNotIn("password", joined.lower())

    def test_without_user_the_current_one_is_used(self):
        self.assertIn("203.0.113.10", sr.ssh_command("203.0.113.10", None, 22, "true")[-2])


class MainTest(unittest.TestCase):
    def test_unreachable_server_fails_instead_of_passing_ports(self):
        output = io.StringIO()
        with mock.patch.object(sr, "run_ssh", return_value=(255, "Connection refused")), \
                mock.patch.object(sr, "write_report", return_value=Path("/tmp/x.json")), \
                contextlib.redirect_stdout(output):
            code = sr.main(["--host", "203.0.113.10"])
        self.assertEqual(1, code)
        self.assertIn("ssh", output.getvalue())

    def test_no_domain_checks_are_claimed(self):
        # В выводе должно быть прямо сказано, чего прогон не доказывает: иначе
        # зелёный результат примут за «сервер готов».
        output = io.StringIO()
        with mock.patch.object(sr, "run_ssh", return_value=(255, "no route")), \
                mock.patch.object(sr, "write_report", return_value=Path("/tmp/x.json")), \
                contextlib.redirect_stdout(output):
            sr.main(["--host", "203.0.113.10"])
        self.assertIn("не проверяет", output.getvalue())
        self.assertIn("MX", output.getvalue())


if __name__ == "__main__":
    unittest.main()
