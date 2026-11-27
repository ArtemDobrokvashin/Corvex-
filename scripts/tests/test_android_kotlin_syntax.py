"""Kotlin syntax guard for the Android client.

There is no Android build in CI and no Android SDK in the repository, so a
Kotlin file that does not parse reaches `main` and only fails on the user's
machine. That is exactly what happened with `var open by remember { ... }`:
`open` is a Kotlin modifier and cannot be a variable name.

This test parses every `.kt` file with tree-sitter-kotlin and fails on any
syntax error. It is not a type check — it catches broken braces, a keyword used
as an identifier, and half-applied edits.

The parser is an optional dependency: without it the test is skipped rather
than failed, so a machine without network access can still run the suite.
Install it before running the suite:

    pip install tree-sitter==0.27.0 tree-sitter-kotlin==1.1.0

CI не ставит эти пакеты: правка `.github/workflows/ci.yml` требует прав
`workflows` у GitHub-приложения, а без шага установки тест в CI просто
пропускается. Локально и у пользователя перед сборкой он работает.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID_SRC = ROOT / 'android' / 'app' / 'src'

try:
    from tree_sitter import Language, Parser
    import tree_sitter_kotlin

    _LANGUAGE = Language(tree_sitter_kotlin.language())
    _PARSER = Parser(_LANGUAGE)
    HAVE_PARSER = True
except Exception:  # pragma: no cover - depends on the environment
    HAVE_PARSER = False


def kotlin_files() -> list[Path]:
    return sorted(ANDROID_SRC.rglob('*.kt')) if ANDROID_SRC.is_dir() else []


def parse_errors(source: bytes) -> list[str]:
    """First broken node per file, as `строка:колонка: тип` — enough to find it."""
    tree = _PARSER.parse(source)
    if not tree.root_node.has_error:
        return []
    found: list[str] = []
    stack = [tree.root_node]
    while stack:
        node = stack.pop()
        if node.type == 'ERROR' or node.is_missing:
            row, column = node.start_point
            found.append(f'{row + 1}:{column + 1}: {node.type}')
        stack.extend(node.children)
    return found[:5]


@unittest.skipUnless(HAVE_PARSER, 'tree-sitter-kotlin не установлен: pip install tree-sitter tree-sitter-kotlin')
class KotlinSyntaxTest(unittest.TestCase):

    def test_android_sources_are_present(self):
        # Иначе тест «проходит» на пустом списке и ничего не охраняет.
        self.assertTrue(kotlin_files(), f'нет .kt файлов в {ANDROID_SRC}')

    def test_every_kotlin_file_parses(self):
        broken = {}
        for path in kotlin_files():
            errors = parse_errors(path.read_bytes())
            if errors:
                broken[str(path.relative_to(ROOT))] = errors
        self.assertEqual({}, broken, 'Kotlin не разбирается — сборка упадёт у пользователя')

    def test_the_guard_catches_the_known_bug(self):
        # Сторож обязан ловить ровно ту ошибку, из-за которой появился:
        # попытку присвоить значение переменной с именем-ключевым словом.
        # (Одно объявление `var open = false` парсер ещё пропускает — сама
        # ошибка проявляется на присваивании, и именно так она и выглядела.)
        bad = b'package x\n\nfun f() {\n    var open = false\n    open = true\n}\n'
        self.assertTrue(parse_errors(bad))

    def test_valid_file_passes(self):
        good = b'package x\n\nfun f(): Int {\n    val expanded = true\n    return if (expanded) 1 else 0\n}\n'
        self.assertEqual([], parse_errors(good))


if __name__ == '__main__':
    unittest.main()
