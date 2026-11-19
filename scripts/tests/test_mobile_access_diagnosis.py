"""Страж диагностики доступа с телефона.

Скрипт нельзя прогнать в CI: нужен Windows, поднятый стенд и второй браузер. Поэтому
проверяется то, что можно проверить без них, — и ровно то, что ломалось бы молча:

1. скрипт **только читает**: никаких `Set-Content`, `Remove-Item`, `docker compose up`,
   `restart`, `down -v`. Диагностика, которая что-то меняет, опаснее дефекта;
2. он проверяет живой `/ws/chat/info` **с заголовком Origin**, а не «просто пинг»:
   именно в этом месте `chat-service` отвечает 403 на чужой origin;
3. он сравнивает origin с `CORVEX_WEB_ORIGINS`, а не печатает «наверное, дело в CORS»;
4. он предупреждает про `CORVEX_AUTH_COOKIE_SECURE`, иначе телефон молча выходит из
   сессии через 15 минут;
5. при найденном расхождении он печатает готовую строку для `.env` и точную команду
   перезапуска двух сервисов, а не «перезапустите всё».

Плюс документация: правило «origin телефона — это другой origin» должно быть в
`OPERATIONS.md`, иначе через месяц его снова найдут в чате.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/diagnose-mobile-access.ps1"
OPERATIONS = (ROOT / "docs/OPERATIONS.md").read_text(encoding="utf-8")
README = (ROOT / "scripts/README.md").read_text(encoding="utf-8")
ENV_EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")
TEXT = SCRIPT.read_text(encoding="utf-8-sig")

# Команды, которые меняют стенд. В read-only диагностике их быть не должно.
DESTRUCTIVE = (
    "Set-Content",
    "Add-Content",
    "Out-File",
    "Remove-Item",
    "Remove-ItemProperty",
    "New-Item",
    "docker compose up",
    "docker compose down",
    "docker compose restart",
    "docker restart",
    "Stop-Process",
    "Start-Process",
    "down -v",
)


def executed_lines() -> str:
    """Скрипт без комментариев и без строк, которые он печатает человеку.

    Подсказка «сделайте docker compose up -d --force-recreate chat-service web-gateway» —
    это текст для человека, а не действие скрипта. Проверка read-only должна смотреть на
    исполняемые строки, иначе она запрещает полезные советы.
    """
    text = re.sub(r"<#.*?#>", "", TEXT, flags=re.DOTALL)
    kept = []
    for line in text.splitlines():
        if line.strip().startswith("#"):
            continue
        if "Write-Host" in line or "write-host" in line:
            continue
        kept.append(line)
    return "\n".join(kept)


class MobileAccessDiagnosisTest(unittest.TestCase):
    def test_script_is_read_only(self):
        code = executed_lines().lower()
        for word in DESTRUCTIVE:
            with self.subTest(command=word):
                self.assertNotIn(word.lower(), code)

    def test_probes_sockjs_info_with_origin_header(self):
        self.assertIn("/ws/chat/info", TEXT)
        self.assertIn("Origin", TEXT)
        self.assertIn("Invoke-WebRequest", TEXT)

    def test_compares_against_corvex_web_origins(self):
        self.assertIn("CORVEX_WEB_ORIGINS", TEXT)
        self.assertIn("CORVEX_AUTH_COOKIE_SECURE", TEXT)

    def test_reports_403_as_the_origin_problem(self):
        self.assertIn("403", TEXT)
        # 403 должен объясняться пользователю, а не печататься как код.
        self.assertIn("не разрешён", TEXT)

    def test_prints_actionable_fix(self):
        self.assertIn("CORVEX_WEB_ORIGINS", TEXT)
        self.assertIn("--force-recreate chat-service web-gateway", TEXT)
        # Массовый перезапуск всего стека — не «то же самое, только дольше».
        self.assertNotIn("docker compose restart\n", TEXT)

    def test_lists_every_lan_address(self):
        self.assertIn("NetworkInterface", TEXT)
        self.assertIn("169.254", TEXT)  # link-local адреса не предлагаются телефону

    def test_handles_unreachable_stand_without_crashing(self):
        self.assertIn("catch", TEXT)
        self.assertIn("TimeoutSec", TEXT)

    def test_guard_is_not_vacuous(self):
        # Если бы скрипт был пустым, все assertNotIn выше прошли бы «успешно».
        self.assertGreater(len(TEXT.splitlines()), 80)
        self.assertIn("param(", TEXT)
        self.assertIn("Add-Finding", TEXT)
        self.assertGreaterEqual(len(re.findall(r"Add-Finding", TEXT)), 8)


class MobileAccessDocumentationTest(unittest.TestCase):
    def test_operations_explains_the_phone_origin(self):
        self.assertIn("diagnose-mobile-access.ps1", OPERATIONS)
        self.assertIn("CORVEX_WEB_ORIGINS", OPERATIONS)
        self.assertIn("403", OPERATIONS)

    def test_scripts_readme_lists_the_script(self):
        self.assertIn("diagnose-mobile-access.ps1", README)

    def test_env_example_warns_about_the_phone_origin(self):
        self.assertIn("192.168", ENV_EXAMPLE)
        self.assertIn("403", ENV_EXAMPLE)


if __name__ == "__main__":
    unittest.main()
