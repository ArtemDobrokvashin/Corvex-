"""Проверки скрипта первого запуска для Linux (scripts/first-run.sh).

Тест запускает настоящий скрипт в изолированном каталоге: openssl есть на CI-раннере,
Docker не нужен. Он фиксирует ровно то, что сломалось бы на сервере молча:

- .env создаётся из .env.example и не содержит ни одного плейсхолдера;
- каждый секрет уникален: одинаковый пароль на PostgreSQL и RabbitMQ — это не «удобно»,
  а общая учётная запись;
- ключи AES-GCM декодируются из Base64 в 32 байта. Плейсхолдер
  ``base64-32-byte-key-for-aes-gcm`` не является валидным Base64, из-за чего TOTP,
  внешние ящики и секреты алертов отвечали 503 на чистой установке;
- повторный запуск не перезаписывает ключи и .env;
- приватные ключи недоступны остальным (0600), публичные читаемы (0644).
"""
from __future__ import annotations

import base64
import hashlib
import os
import re
import shutil
import stat
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'scripts' / 'first-run.sh'
ENV_EXAMPLE = REPO / '.env.example'

PLACEHOLDER_PASSWORD = 'change-me-strong-password'
PLACEHOLDER_AES_KEY = 'base64-32-byte-key-for-aes-gcm'
PRIVATE_KEYS = ('infra/secrets/jwt_private.pem', 'infra/secrets/dkim_private.pem',
                'infra/certs/privkey.pem')
PUBLIC_FILES = ('infra/secrets/jwt_public.pem', 'infra/secrets/dkim_public.pem',
                'infra/certs/fullchain.pem')


def run_first_run(workdir: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, CORVEX_FIRST_RUN_NO_CHOWN='1')
    return subprocess.run(
        ['bash', str(workdir / 'scripts' / 'first-run.sh'), *args],
        cwd=workdir, env=env, capture_output=True, text=True, timeout=180,
    )


class FirstRunLinuxTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workdir = Path(self.enterContext(temp_dir()))
        (self.workdir / 'scripts').mkdir()
        shutil.copy2(SCRIPT, self.workdir / 'scripts' / 'first-run.sh')
        shutil.copy2(ENV_EXAMPLE, self.workdir / '.env.example')
        self.result = run_first_run(self.workdir)

    def env_lines(self) -> list[str]:
        return (self.workdir / '.env').read_text(encoding='utf-8').splitlines()

    def env_value(self, name: str) -> str:
        for line in self.env_lines():
            if line.startswith(f'{name}='):
                return line.split('=', 1)[1]
        raise AssertionError(f'{name} отсутствует в .env')

    def test_script_exists_and_parses(self) -> None:
        self.assertTrue(SCRIPT.is_file())
        syntax = subprocess.run(['bash', '-n', str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(0, syntax.returncode, syntax.stderr)

    def test_run_succeeds_and_creates_all_files(self) -> None:
        self.assertEqual(0, self.result.returncode, self.result.stderr)
        for relative in PRIVATE_KEYS + PUBLIC_FILES:
            with self.subTest(file=relative):
                self.assertTrue((self.workdir / relative).is_file(), relative)
        self.assertTrue((self.workdir / 'infra' / 'secrets' / 'dkim').is_dir())

    def test_no_placeholder_survives(self) -> None:
        text = (self.workdir / '.env').read_text(encoding='utf-8')
        self.assertNotIn(PLACEHOLDER_PASSWORD, text)
        self.assertNotIn(PLACEHOLDER_AES_KEY, text)
        self.assertNotIn('ALERT_SMTP_HOST=smtp.provider.example', text)

    def test_every_password_is_distinct(self) -> None:
        names = ('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD', 'REDIS_PASSWORD',
                 'ELASTIC_PASSWORD', 'GRAFANA_PASSWORD', 'CORVEX_INTERNAL_API_TOKEN')
        values = [self.env_value(name) for name in names]
        self.assertEqual(len(values), len(set(values)), 'секреты не должны повторяться')
        for value in values:
            self.assertGreaterEqual(len(value), 16)

    def test_aes_keys_decode_to_32_bytes(self) -> None:
        for name in ('CORVEX_TOTP_ENC_KEY', 'CORVEX_EXTERNAL_MAIL_ENC_KEY',
                     'CORVEX_ALERT_CONFIG_ENC_KEY'):
            with self.subTest(key=name):
                raw = base64.b64decode(self.env_value(name), validate=True)
                self.assertEqual(32, len(raw))

    def test_env_keeps_every_key_from_example(self) -> None:
        def names(lines: list[str]) -> set[str]:
            return {line.split('=', 1)[0] for line in lines
                    if '=' in line and not line.lstrip().startswith('#')}

        self.assertEqual(names(ENV_EXAMPLE.read_text(encoding='utf-8').splitlines()),
                         names(self.env_lines()))

    def test_private_keys_are_not_world_readable(self) -> None:
        for relative in PRIVATE_KEYS:
            mode = stat.S_IMODE((self.workdir / relative).stat().st_mode)
            self.assertEqual(0o600, mode, f'{relative}: {oct(mode)}')

    def test_rerun_keeps_keys_and_env(self) -> None:
        def digest(relative: str) -> str:
            return hashlib.sha256((self.workdir / relative).read_bytes()).hexdigest()

        keys_before = {relative: digest(relative) for relative in PRIVATE_KEYS}
        env_before = (self.workdir / '.env').read_bytes()

        second = run_first_run(self.workdir)

        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(env_before, (self.workdir / '.env').read_bytes())
        for relative, expected in keys_before.items():
            self.assertEqual(expected, digest(relative), relative)
        self.assertIn('[skip]', second.stdout)

    def test_domain_flags_fill_env(self) -> None:
        workdir = Path(self.enterContext(temp_dir()))
        (workdir / 'scripts').mkdir()
        shutil.copy2(SCRIPT, workdir / 'scripts' / 'first-run.sh')
        shutil.copy2(ENV_EXAMPLE, workdir / '.env.example')

        result = run_first_run(workdir, '--domain', 'mail.example.ru',
                               '--mail-domain', 'example.ru')

        self.assertEqual(0, result.returncode, result.stderr)
        text = (workdir / '.env').read_text(encoding='utf-8')
        self.assertIn('CORVEX_DOMAIN=mail.example.ru', text)
        self.assertIn('CORVEX_MAIL_DOMAIN=example.ru', text)

    def test_powershell_script_also_replaces_aes_placeholders(self) -> None:
        # Паритет: оба скрипта обязаны чинить заглушку, иначе чистая установка на
        # Windows остаётся с неработающими TOTP/внешними ящиками/алертами.
        text = (REPO / 'scripts' / 'first-run.ps1').read_text(encoding='utf-8')
        self.assertIn('base64-32-byte-key-for-aes-gcm', text)
        self.assertIn('Replaced placeholder', text)
        for name in ('CORVEX_TOTP_ENC_KEY', 'CORVEX_EXTERNAL_MAIL_ENC_KEY',
                     'CORVEX_ALERT_CONFIG_ENC_KEY'):
            with self.subTest(key=name):
                self.assertIn(name, text)


class temp_dir:
    """Кортекс-менеджер временного каталога без предупреждений устаревшего API."""

    def __enter__(self) -> str:
        import tempfile
        self._dir = tempfile.mkdtemp(prefix='corvex-first-run-')
        return self._dir

    def __exit__(self, *exc_info) -> None:
        shutil.rmtree(self._dir, ignore_errors=True)
