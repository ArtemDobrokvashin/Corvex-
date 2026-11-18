"""Портируемость скриптов: кодировка PowerShell и права bash.

Два дефекта, которые не видны в Linux и не ловятся ни одним прогоном в CI:

1. **`.ps1` с русским текстом без BOM.** Windows PowerShell 5.1 (а это версия по
   умолчанию на Windows Server 2019 и Windows 10) читает файл без BOM в **ANSI**-
   кодировке системы. На русской локали это Windows-1251 и текст ещё читается, на
   английской — нет: сообщения превращаются в мусор, а строки с кириллицей внутри
   условий могут разобраться неправильно. Bash-версии при этом выглядят нормально,
   поэтому расхождение замечают уже на сервере.
2. **`.sh` без бита исполнения.** `docs/INSTALL.md` вызывает `./scripts/first-run.sh`,
   и при потере режима `755` установка падает на `Permission denied` — после того как
   человек уже настроился на длинную сборку.

Проверка идёт по байтам в git-индексе, а не по рабочему дереву: именно индекс уезжает
на сервер.
"""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
BOM = b"\xef\xbb\xbf"


def tracked(mode_prefix: str) -> dict[str, str]:
    """Путь → режим из git-индекса для файлов, начинающихся с mode_prefix."""
    result = subprocess.run(
        ["git", "ls-files", "-s", "--", "scripts"], cwd=ROOT, capture_output=True, text=True
    )
    if result.returncode != 0:
        raise unittest.SkipTest("git недоступен")
    mapping: dict[str, str] = {}
    for line in result.stdout.splitlines():
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        if path.endswith(mode_prefix):
            mapping[path] = mode
    return mapping


def has_non_ascii(path: Path) -> bool:
    raw = path.read_bytes()
    try:
        raw.decode("ascii")
        return False
    except UnicodeDecodeError:
        return True


class PowerShellEncodingTest(unittest.TestCase):
    def test_every_powershell_script_with_non_ascii_has_a_bom(self):
        offenders = []
        for path in sorted(SCRIPTS.glob("*.ps1")):
            if has_non_ascii(path) and not path.read_bytes().startswith(BOM):
                offenders.append(path.name)
        self.assertEqual(
            [],
            offenders,
            "Windows PowerShell 5.1 прочитает такой файл как ANSI и испортит текст: "
            + ", ".join(offenders),
        )

    def test_bom_scripts_still_decode_as_utf8(self):
        for path in sorted(SCRIPTS.glob("*.ps1")):
            raw = path.read_bytes()
            if raw.startswith(BOM):
                with self.subTest(script=path.name):
                    raw[3:].decode("utf-8")

    def test_guard_is_not_vacuous(self):
        scripts = sorted(SCRIPTS.glob("*.ps1"))
        self.assertGreaterEqual(len(scripts), 25)
        with_non_ascii = [p for p in scripts if has_non_ascii(p)]
        self.assertTrue(
            with_non_ascii,
            "ни одного .ps1 с не-ASCII текстом: проверка ничего не значит — правило пора пересмотреть",
        )

    def test_powershell_scripts_do_not_switch_console_encoding_silently(self):
        # `chcp` меняет кодовую страницу всего окна и ломает вывод следующих команд.
        for path in sorted(SCRIPTS.glob("*.ps1")):
            with self.subTest(script=path.name):
                self.assertNotIn("chcp", path.read_text(encoding="utf-8-sig").lower())


class ShellScriptModeTest(unittest.TestCase):
    def test_shell_scripts_are_executable_in_the_index(self):
        modes = tracked(".sh")
        self.assertGreaterEqual(len(modes), 3, "не найдено ни одного .sh в индексе")
        wrong = sorted(path for path, mode in modes.items() if mode != "100755")
        self.assertEqual(
            [],
            wrong,
            "docs/INSTALL.md вызывает ./scripts/…sh, без 755 будет Permission denied: "
            + ", ".join(wrong),
        )

    def test_shell_scripts_use_lf(self):
        offenders = []
        for path in sorted(SCRIPTS.glob("*.sh")):
            if b"\r\n" in path.read_bytes():
                offenders.append(path.name)
        self.assertEqual([], offenders, "CRLF в bash-скрипте: " + ", ".join(offenders))

    def test_shell_scripts_parse(self):
        for path in sorted(SCRIPTS.glob("*.sh")):
            with self.subTest(script=path.name):
                result = subprocess.run(
                    ["bash", "-n", str(path)], capture_output=True, text=True
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_gitattributes_keeps_shell_scripts_lf(self):
        text = (ROOT / ".gitattributes").read_text(encoding="utf-8")
        self.assertIn("*.sh text eol=lf", text)


if __name__ == "__main__":
    unittest.main()
