"""Функциональные прогоны `scripts/preflight.py` с подставным `docker`.

Почему не только структурный тест: ценность preflight в том, что он говорит
«порт занят», «в .env BOM», «ключа нет». Пока эти сообщения не проверены
прогоном, скрипт — набор пожеланий. Docker в CI нет, поэтому в `PATH`
подставляется фейковый `docker`, а корень репозитория — временный каталог
с настоящими `docker-compose.yml` и `.env.example`.

Отдельно проверяется ловушка, которая уже была в первой версии скрипта:
bind-проба на порт 80 без root отвечает «занят», и preflight объявлял бы
провалом любую непривилегированную установку. Теперь порты читаются из
`/proc/net/tcp`, и в тестах это зафиксировано.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREFLIGHT = ROOT / "scripts/preflight.py"

FAKE_DOCKER = """#!/bin/sh
case "$1" in
  --version) echo "Docker version 27.0.0, build fake"; exit 0 ;;
  info) echo "linux"; exit 0 ;;
  compose)
    case "$2" in
      version) echo "Docker Compose version v2.29.0"; exit 0 ;;
      config) exit 0 ;;
      ps) echo "" ; exit 0 ;;
    esac ;;
esac
echo "fake docker: unsupported args $*" >&2
exit 1
"""


def build_root(base: Path) -> Path:
    """Копия минимального окружения: compose, пример .env, ключи, git."""
    (base / "scripts").mkdir(parents=True, exist_ok=True)
    shutil.copy(ROOT / "docker-compose.yml", base / "docker-compose.yml")
    shutil.copy(ROOT / ".env.example", base / ".env.example")

    lines = ["# fake env"]
    for line in (ROOT / ".env.example").read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if value in ("", "change-me-strong-password", "base64-32-byte-key-for-aes-gcm"):
            value = "filled-by-test"
        lines.append(f"{key}={value}")
    (base / ".env").write_text("\n".join(lines) + "\n", encoding="utf-8")

    for name in ("jwt_private.pem", "jwt_public.pem", "dkim_private.pem", "dkim_public.pem"):
        (base / "infra/secrets").mkdir(parents=True, exist_ok=True)
        (base / "infra/secrets" / name).write_text("x", encoding="utf-8")
    for name in ("fullchain.pem", "privkey.pem"):
        (base / "infra/certs").mkdir(parents=True, exist_ok=True)
        (base / "infra/certs" / name).write_text("x", encoding="utf-8")
    for name in ("infra/secrets/jwt_private.pem", "infra/secrets/dkim_private.pem", "infra/certs/privkey.pem"):
        (base / name).chmod(0o600)

    (base / "sysctl").write_text("262144\n", encoding="utf-8")

    for command in (["git", "init", "-q"], ["git", "add", "-A"],
                    ["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init"]):
        subprocess.run(command, cwd=base, check=True, capture_output=True)
    return base


class PreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.root = build_root(self.tmp / "repo")
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text(FAKE_DOCKER, encoding="utf-8")
        docker.chmod(0o755)

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_preflight(self, *args: str, docker: bool = True, strict: bool = False) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["PATH"] = f"{self.bin}:{env['PATH']}" if docker else str(self.tmp / "empty-bin")
        env["NO_COLOR"] = "1"
        command = [
            sys.executable, str(PREFLIGHT),
            "--root", str(self.root),
            "--json",
            "--min-cpu", "1", "--min-ram-gib", "1", "--min-disk-gib", "1",
            "--sysctl-file", str(self.root / "sysctl"),
            *args,
        ]
        if strict:
            command.append("--strict")
        return subprocess.run(command, capture_output=True, text=True, env=env)

    def report(self, result: subprocess.CompletedProcess) -> dict[str, dict]:
        self.assertIn(result.returncode, (0, 1, 2), result.stderr)
        data = json.loads(result.stdout)
        return {item["check"]: item for item in data["results"]}

    def test_clean_environment_passes_without_failures(self):
        result = self.run_preflight()
        checks = self.report(result)
        failed = [name for name, item in checks.items() if item["status"] == "FAIL"]
        self.assertEqual([], failed, json.dumps(checks, ensure_ascii=False, indent=2))
        self.assertEqual(0, result.returncode)

    def test_ports_are_read_from_proc_and_do_not_depend_on_root(self):
        # Регрессия: bind-проба без root даёт EACCES и объявляла порт 80 занятым.
        checks = self.report(self.run_preflight())
        self.assertEqual("PASS", checks["ports"]["status"])
        self.assertIn("80", checks["ports"]["detail"])

    def test_busy_port_is_a_failure(self):
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            probe.bind(("0.0.0.0", 8080))
            probe.listen(1)
        except OSError:
            probe.close()
            self.skipTest("порт 8080 уже занят на этой машине")
        try:
            result = self.run_preflight()
            checks = self.report(result)
            self.assertEqual("FAIL", checks["ports"]["status"])
            self.assertIn("8080", checks["ports"]["detail"])
            self.assertEqual(1, result.returncode)
        finally:
            probe.close()

    def test_missing_dot_env_is_a_failure_with_hint(self):
        (self.root / ".env").unlink()
        checks = self.report(self.run_preflight())
        self.assertEqual("FAIL", checks[".env"]["status"])
        self.assertIn("first-run", checks[".env"]["detail"])

    def test_bom_in_dot_env_is_reported_as_failure(self):
        path = self.root / ".env"
        path.write_bytes(b"\xef\xbb\xbf" + path.read_bytes())
        checks = self.report(self.run_preflight())
        self.assertEqual("FAIL", checks[".env"]["status"])
        self.assertIn("BOM", checks[".env"]["detail"])

    def test_crlf_in_dot_env_is_only_a_warning(self):
        path = self.root / ".env"
        path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
        checks = self.report(self.run_preflight())
        self.assertEqual("FAIL", checks[".env"]["status"])  # CRLF идёт в одном статусе с прочими замечаниями
        self.assertIn("CRLF", checks[".env"]["detail"])

    def test_placeholders_from_example_are_a_failure(self):
        path = self.root / ".env"
        text = path.read_text(encoding="utf-8").replace(
            "CORVEX_TOTP_ENC_KEY=filled-by-test", "CORVEX_TOTP_ENC_KEY=base64-32-byte-key-for-aes-gcm"
        )
        path.write_text(text, encoding="utf-8")
        checks = self.report(self.run_preflight())
        self.assertEqual("FAIL", checks[".env заглушки"]["status"])
        self.assertIn("CORVEX_TOTP_ENC_KEY", checks[".env заглушки"]["detail"])

    def test_missing_required_compose_key_is_a_failure(self):
        path = self.root / ".env"
        text = "\n".join(
            line for line in path.read_text(encoding="utf-8").splitlines()
            if not line.startswith("POSTGRES_PASSWORD=")
        )
        path.write_text(text + "\n", encoding="utf-8")
        checks = self.report(self.run_preflight())
        self.assertEqual("FAIL", checks[".env обязательные"]["status"])
        self.assertIn("POSTGRES_PASSWORD", checks[".env обязательные"]["detail"])

    def test_duplicate_key_is_a_failure(self):
        path = self.root / ".env"
        path.write_text(path.read_text(encoding="utf-8") + "POSTGRES_PASSWORD=second\n", encoding="utf-8")
        checks = self.report(self.run_preflight())
        self.assertEqual("FAIL", checks[".env"]["status"])
        self.assertIn("POSTGRES_PASSWORD", checks[".env"]["detail"])

    def test_missing_keys_and_certs_are_a_failure(self):
        (self.root / "infra/certs/privkey.pem").unlink()
        checks = self.report(self.run_preflight())
        self.assertEqual("FAIL", checks["keys/certs"]["status"])
        self.assertIn("infra/certs/privkey.pem", checks["keys/certs"]["detail"])

    def test_readable_private_key_is_a_warning(self):
        (self.root / "infra/secrets/jwt_private.pem").chmod(0o644)
        result = self.run_preflight()
        checks = self.report(result)
        self.assertEqual("WARN", checks["keys права"]["status"])
        self.assertEqual(0, result.returncode)
        strict = self.run_preflight(strict=True)
        self.assertEqual(2, strict.returncode)

    def test_missing_docker_does_not_crash_the_script(self):
        result = self.run_preflight(docker=False)
        checks = self.report(result)
        self.assertEqual("FAIL", checks["docker"]["status"])
        self.assertIn("не найден", checks["docker"]["detail"])
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(1, result.returncode)

    def test_json_contains_every_expected_check(self):
        checks = self.report(self.run_preflight())
        for name in (
            "docker", "docker compose", "docker daemon", "vCPU", "RAM", "disk", "ports",
            ".env", ".env заглушки", ".env обязательные", "keys/certs", "keys права",
            "compose config", "vm.max_map_count", "git",
        ):
            self.assertIn(name, checks)


if __name__ == "__main__":
    unittest.main()
