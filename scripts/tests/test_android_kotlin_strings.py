"""Guard: Kotlin string literals in the Android app use only valid escapes.

Why this exists: the client compiled with

    e: .../ImapMailService.kt:73:48 Unsupported escape sequence.
    e: .../ImapMailService.kt:232:60 Unsupported escape sequence.
    e: .../ImapMailService.kt:233:36 Unsupported escape sequence.

All three were regexes written as `Regex("\\s+")` and edited by hand until the
backslash was single: `Regex("\s+")`. In Kotlin `\s` is not an escape
sequence at all, so this is a compile error — and nothing in CI noticed,
because there is no Android job. The Python guards only checked brace balance.

The property checked here: inside a double-quoted Kotlin string literal, every
backslash must start one of Kotlin's actual escape sequences. Raw strings
(`\"\"\"...\"\"\"`) are full of regex backslashes by design and are skipped, as
are comments and character literals.

This is not a Kotlin parser and it does not replace a build. It catches the
one class of mistake that already reached `main` twice.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP_SRC = ROOT / 'android' / 'app' / 'src'

# Kotlin's escape sequences in a regular string: \t \b \n \r \' \" \\ \$ \uXXXX.
VALID_ESCAPE = set("tbnr'"'"'"\\" + "$")


def _strip_comments(text: str) -> str:
    """Remove // and /* */ comments, keeping string literals balanced."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '*':
            end = text.find('*/', i + 2)
            end = n if end < 0 else end + 2
            # Переводы строк внутри комментария сохраняем, иначе все номера
            # строк после него сдвинутся и сторож будет врать.
            out.append('\n' * text.count('\n', i, end))
            i = end
            continue
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            # Raw string: пробелы вместо содержимого, переводы строк на месте.
            out.append(''.join(
                '\n' if c == '\n' else ' ' for c in text[i:end]
            ))
            i = end
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


def _bad_escapes(source: str) -> list[tuple[int, str]]:
    """Return (line, snippet) for every invalid escape in a double-quoted string."""
    text = _strip_comments(source)
    problems: list[tuple[int, str]] = []
    in_string = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if not in_string:
            # Character literals like '\n' are single chars; skip them whole.
            if ch == "'":
                j = i + 1
                while j < n and text[j] != "'":
                    j += 2 if text[j] == '\\' else 1
                i = j + 1
                continue
            if ch == '"':
                in_string = True
            i += 1
            continue
        if ch == '\\':
            nxt = text[i + 1] if i + 1 < n else ''
            if nxt == 'u':
                digits = text[i + 2:i + 6]
                if not re.fullmatch(r'[0-9a-fA-F]{4}', digits):
                    problems.append((text.count('\n', 0, i) + 1, '\\u' + digits))
                i += 6
                continue
            if nxt not in VALID_ESCAPE:
                line = text.count('\n', 0, i) + 1
                problems.append((line, '\\' + nxt))
            i += 2
            continue
        if ch == '"':
            in_string = False
        i += 1
    return problems


class KotlinStringEscapesTest(unittest.TestCase):
    def test_no_invalid_escapes_in_android_sources(self):
        paths = sorted(APP_SRC.rglob('*.kt'))
        self.assertTrue(paths, 'не найдено ни одного .kt — проверка бессмысленна')

        failures = []
        for path in paths:
            for line, snippet in _bad_escapes(path.read_text(encoding='utf-8')):
                rel = path.relative_to(ROOT).as_posix()
                failures.append(f'{rel}:{line}: Unsupported escape sequence ({snippet})')

        self.assertEqual(
            [],
            failures,
            'В Kotlin-строках недопустимая escape-последовательность.\n'
            'Для регулярки используйте raw-строку: Regex("""\\s+""").\n' + '\n'.join(failures),
        )

    def test_the_guard_itself_catches_the_known_bug(self):
        # Тот самый код, на котором упала сборка. Без этой проверки сторож
        # мог бы «проходить» просто потому, что ничего не находит.
        broken = 'val r = Regex("\\s+")'
        self.assertEqual([(1, '\\s')], _bad_escapes(broken))

        fixed_raw = 'val r = Regex("""\\s+""")'
        self.assertEqual([], _bad_escapes(fixed_raw))

        fixed_escaped = 'val r = Regex("\\\\s+")'
        self.assertEqual([], _bad_escapes(fixed_escaped))


if __name__ == '__main__':
    unittest.main()
