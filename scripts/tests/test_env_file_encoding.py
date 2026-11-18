"""Кодировка `.env`: файл, созданный на Windows, обязан читаться Docker'ом.

Разбор `.env` в `docker compose` идёт через compose-go, и там `unicode.IsSpace(U+FEFF)`
ложно: BOM не считается пробелом, попадает в имя первой переменной и первая же строка
падает с `unexpected character "\\ufeff" in variable name`. Windows PowerShell 5.1
пишет `Set-Content -Encoding UTF8` **с BOM**, то есть шаг 4 из `docs/INSTALL.md`
(создание `.env` на хосте) оставлял машину в состоянии, где `docker compose` не
работает вообще.

Здесь три уровня проверки:

1. `.env.example` — источник копирования — сам без BOM и с LF;
2. `first-run.sh` — проверяется прогоном, байты читаются как есть;
3. `first-run.ps1` — прогона нет (в CI нет Windows), поэтому проверяется контракт:
   запись только через `UTF8Encoding($false)`, никаких `Set-Content`/`Add-Content`
   по `.env`, и явная проверка BOM после записи.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SH = REPO / "scripts/first-run.sh"
PS1 = REPO / "scripts/first-run.ps1"
EXAMPLE = REPO / ".env.example"
PREFLIGHT = REPO / "scripts/preflight.py"

BOM = b"\xef\xbb\xbf"
AES_KEYS = ("CORVEX_TOTP_ENC_KEY", "CORVEX_EXTERNAL_MAIL_ENC_KEY", "CORVEX_ALERT_CONFIG_ENC_KEY")


class EnvExampleEncodingTest(unittest.TestCase):
    def test_example_has_no_bom_and_uses_lf(self):
        raw = EXAMPLE.read_bytes()
        self.assertFalse(raw.startswith(BOM), ".env.example начинается с BOM — его скопируют в .env")
        self.assertNotIn(b"\r\n", raw, ".env.example сохранён с CRLF")

    def test_example_keys_are_unique(self):
        keys = re.findall(r"^([A-Z][A-Z0-9_]*)=", EXAMPLE.read_text(encoding="utf-8"), re.MULTILINE)
        duplicates = sorted({key for key in keys if keys.count(key) > 1})
        self.assertEqual([], duplicates, "дубли ключей в примере: " + ", ".join(duplicates))


class FirstRunShEncodingTest(unittest.TestCase):
    """Настоящий прогон bash-версии: она доступна и в CI, и на сервере."""

    def setUp(self) -> None:
        self.workdir = Path(tempfile.mkdtemp(prefix="corvex-env-"))
        (self.workdir / "scripts").mkdir()
        shutil.copy2(SH, self.workdir / "scripts/first-run.sh")
        shutil.copy2(EXAMPLE, self.workdir / ".env.example")
        env = dict(os.environ, CORVEX_FIRST_RUN_NO_CHOWN="1")
        self.result = subprocess.run(
            ["bash", str(self.workdir / "scripts/first-run.sh")],
            cwd=self.workdir, env=env, capture_output=True, text=True, timeout=180,
        )

    def tearDown(self) -> None:
        shutil.rmtree(self.workdir, ignore_errors=True)

    def test_env_is_utf8_without_bom_and_with_lf(self):
        self.assertEqual(0, self.result.returncode, self.result.stderr)
        raw = (self.workdir / ".env").read_bytes()
        self.assertFalse(raw.startswith(BOM), "first-run.sh создал .env с BOM")
        self.assertNotIn(b"\r\n", raw, "first-run.sh создал .env с CRLF")
        raw.decode("utf-8")  # не должно падать

    def test_generated_env_has_no_placeholder(self):
        text = (self.workdir / ".env").read_text(encoding="utf-8")
        self.assertNotIn("change-me-strong-password", text)
        self.assertNotIn("base64-32-byte-key-for-aes-gcm", text)


class FirstRunPowerShellContractTest(unittest.TestCase):
    """Windows-версия не запускается в CI, поэтому проверяется её контракт."""

    def setUp(self) -> None:
        self.text = PS1.read_text(encoding="utf-8")

    def test_writes_env_without_bom(self):
        self.assertIn("UTF8Encoding($false)", self.text)
        self.assertIn("[System.IO.File]::WriteAllText", self.text)

    def test_no_set_content_or_add_content_for_env(self):
        # `Set-Content -Encoding UTF8` в PowerShell 5.1 пишет BOM, `Add-Content`
        # сохраняет CRLF и дописывает в конец файла, который уже прочитан в память.
        self.assertNotRegex(self.text, r"Set-Content\s+(\.env|\$envPath)")
        self.assertNotRegex(self.text, r"Add-Content\s+(\.env|\$envPath)")
        self.assertNotIn("Set-Content .env", self.text)

    def test_fails_loudly_if_bom_appears(self):
        self.assertIn("0xEF", self.text)
        self.assertIn("BOM", self.text)

    def test_runs_from_repository_root_not_from_current_directory(self):
        # Без этого запуск из другого каталога создаёт infra\certs и .env не в репозитории.
        self.assertIn('Set-Location (Join-Path $PSScriptRoot "..")', self.text)

    def test_keeps_every_upgrade_path(self):
        for key in AES_KEYS + ("CORVEX_INTERNAL_API_TOKEN", "ALERT_SMTP_HOST"):
            with self.subTest(key=key):
                self.assertIn(key, self.text)

    def test_does_not_re_expand_existing_values(self):
        # Регрессия: раньше upgrade-путь заменял все заглушки, включая уже введённые
        # человеком секреты. Теперь ветка для существующего .env меняет только
        # конкретные строки.
        self.assertIn("secrets NOT regenerated", self.text)


class PreflightCatchesBomTest(unittest.TestCase):
    def test_preflight_explains_bom_consequence(self):
        text = PREFLIGHT.read_text(encoding="utf-8")
        self.assertIn("BOM", text)
        self.assertIn("compose-go", text)


if __name__ == "__main__":
    unittest.main()
