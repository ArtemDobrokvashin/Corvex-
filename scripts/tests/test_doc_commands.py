"""Команды из документации должны существовать у скриптов.

Это не педантизм: в `docs/OPERATIONS.md` команда восстановления однажды была написана
без обязательного `-BackupDir`, и скрипт честно падал на обязательном параметре. Ни один
прогон этого не видел, потому что документацию не запускают. Такой класс ошибок ловят
только сверкой текста с `param(...)`.

Проверяется для каждой команды `scripts\\name.ps1 ...` **внутри блоков кода**:

1. файл существует;
2. каждый упомянутый `-Flag` объявлен в `param(...)` этого скрипта;
3. обязательные параметры переданы — либо флагом, либо позиционно
   (`alerts.ps1 silence` — это `-Action silence`, если у `$Action` есть `Position = 0`).

Упоминание скрипта в прозе командой не считается. Переносы строк (`` ` `` в PowerShell,
`\\` в bash) склеиваются: без этого флаги со следующей строки «теряются», и проверка
обязательных параметров становится вакуумной.
"""

from __future__ import annotations

import re
import unittest
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"

# Аргументы самого powershell.exe, а не проверяемого скрипта.
POWERSHELL_FLAGS = {"ExecutionPolicy", "File", "NoProfile", "Command", "Bypass"}

DOC_FILES = sorted((ROOT / "docs").rglob("*.md")) + [
    ROOT / "scripts/README.md",
    ROOT / "README.md",
    ROOT / "CONTRIBUTING.md",
]

INVOCATION = re.compile(r"scripts[\\/]([A-Za-z0-9_.-]+\.ps1)([^\n]*)")
FENCE = re.compile(r"```[a-zA-Z]*\n(.*?)```", re.DOTALL)
CONTINUATION = re.compile(r"[`\\]\n")

# Минимум, ниже которого проверка перестаёт что-либо доказывать.
MIN_INVOCATIONS = 5


@dataclass(frozen=True)
class Invocation:
    doc: str
    script: str
    flags: frozenset[str]
    tail: str


def documented_invocations() -> list[Invocation]:
    result: list[Invocation] = []
    for doc in DOC_FILES:
        if not doc.exists():
            continue
        text = doc.read_text(encoding="utf-8", errors="ignore")
        for block in FENCE.finditer(text):
            body = CONTINUATION.sub(" ", block.group(1))
            for match in INVOCATION.finditer(body):
                tail = match.group(2)
                # Граница токена обязательна: иначе `corvex-restore-drill` даёт флаг
                # `-restore`, а `C:\\corvex-pki\\...` — флаг `-pki`.
                flags = frozenset(
                    flag for flag in re.findall(r"(?:^|\s)-([A-Za-z][A-Za-z0-9_]*)", tail)
                    if flag not in POWERSHELL_FLAGS
                )
                result.append(
                    Invocation(
                        doc=doc.relative_to(ROOT).as_posix(),
                        script=match.group(1),
                        flags=flags,
                        tail=tail,
                    )
                )
    return result


def param_block(text: str) -> str | None:
    """Тело `param(...)` с учётом вложенных скобок.

    Простой `param\\((.*?)\\)` ломается на `[Parameter(Mandatory = $true)]`: первая
    закрывающая скобка принадлежит атрибуту, а не блоку. Именно на этом `verify.ps1`
    с однострочным `param([switch]$DockerBuild)` давал ложное «флаг не объявлен».
    """
    match = re.search(r"\bparam\s*\(", text)
    if not match:
        return None
    depth = 0
    start = match.end() - 1
    for index in range(start, len(text)):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    return None


def parameter_specs(script: Path) -> tuple[set[str], set[str], dict[str, int]]:
    """(все параметры, обязательные, позиционные: имя → номер позиции)."""
    body = param_block(script.read_text(encoding="utf-8-sig"))
    if body is None:
        return set(), set(), {}
    all_params = set(re.findall(r"\$([A-Za-z0-9_]+)", body))
    mandatory: set[str] = set()
    positional: dict[str, int] = {}
    for match in re.finditer(
        r"\[Parameter\(([^)]*)\)\]\s*(?:\[[^\]]*\]\s*)*\$([A-Za-z0-9_]+)", body
    ):
        attribute, name = match.group(1), match.group(2)
        if "Mandatory" in attribute and "true" in attribute.lower():
            mandatory.add(name)
        position = re.search(r"Position\s*=\s*(\d+)", attribute)
        if position:
            positional[name] = int(position.group(1))
    return all_params, mandatory, positional


def positional_supplied(tail: str, position: int) -> bool:
    """Передан ли позиционный аргумент `position` (считаются токены до первого флага)."""
    before_flags = re.split(r"\s-", tail, maxsplit=1)[0]
    return len(before_flags.split()) > position


class DocumentedCommandTest(unittest.TestCase):
    def test_documented_flags_exist_in_the_script(self):
        problems: list[str] = []
        for call in documented_invocations():
            script = SCRIPTS / call.script
            if not script.exists():
                problems.append(f"{call.doc}: scripts/{call.script} не существует")
                continue
            declared, _, _ = parameter_specs(script)
            for flag in sorted(call.flags):
                if flag not in declared:
                    problems.append(f"{call.doc}: scripts/{call.script} не объявляет -{flag}")
        self.assertEqual([], problems, "\n".join(problems))

    def test_mandatory_parameters_are_present_in_documented_commands(self):
        problems: list[str] = []
        for call in documented_invocations():
            script = SCRIPTS / call.script
            if not script.exists():
                continue
            _, mandatory, positional = parameter_specs(script)
            missing = sorted(
                name for name in mandatory - set(call.flags)
                if not (name in positional and positional_supplied(call.tail, positional[name]))
            )
            if missing:
                problems.append(
                    f"{call.doc}: scripts/{call.script} без обязательных "
                    + ", ".join("-" + name for name in missing)
                )
        self.assertEqual([], problems, "\n".join(problems))

    def test_guard_is_not_vacuous(self):
        invocations = documented_invocations()
        self.assertGreaterEqual(len(invocations), MIN_INVOCATIONS)

        # Склейка переносов действительно работает: в NEXT-STEPS команда restore.ps1
        # разбита на строки, и без склейки флаг -DataOnly потерялся бы.
        restore_calls = [call for call in invocations if call.script == "restore.ps1"]
        self.assertTrue(restore_calls, "в документации нет команд restore.ps1")
        self.assertTrue(
            any("DataOnly" in call.flags for call in restore_calls),
            "перенос строки не склеился: -DataOnly не найден",
        )

        # Обязательный параметр распознаётся, а не «находится» случайно.
        _, mandatory, _ = parameter_specs(SCRIPTS / "restore.ps1")
        self.assertEqual({"BackupDir"}, mandatory)

        # Позиционный обязательный параметр распознаётся как переданный.
        _, mandatory_alerts, positional_alerts = parameter_specs(SCRIPTS / "alerts.ps1")
        self.assertIn("Action", mandatory_alerts)
        self.assertEqual(0, positional_alerts.get("Action"))
        self.assertTrue(positional_supplied(" status", 0))
        self.assertFalse(positional_supplied(" -Minutes 60", 0))


if __name__ == "__main__":
    unittest.main()
