"""`.env.example` и `docker-compose.yml` не расходятся.

Почему это отдельный тест, хотя сейчас 94 = 94:

`docker-compose.yml` подставляет значения из `.env` через `${VAR}`. Если ключ
появился в compose, но не в `.env.example`, человек, ставящий сервер по
`docs/INSTALL.md`, о нём не узнает: он скопирует пример, а приложение молча
возьмёт встроенное значение (или упадёт на `:?required`). Обратный случай
безвреднее, но означает мусор в примере: ключ, который никуда не передаётся.

Тест ловит расхождение на ревью, а не на ноутбуке во время установки.
Секреты при этом не сравниваются: значения в `.env.example` — заглушки,
и их содержимое здесь никого не интересует.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
EXAMPLE = (ROOT / ".env.example").read_text(encoding="utf-8")

# Переменные, которые compose читает, но в пример сознательно не кладёт.
# Оставлено пустым намеренно: пустой список — это утверждение «расхождений нет»,
# и оно проверяется тестом `test_allowlist_has_no_stale_entries`.
ALLOWED_EXTRA_IN_COMPOSE: set[str] = set()


def compose_variables() -> set[str]:
    return set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)(?::?[-?][^}]*)?\}", COMPOSE))


def example_keys() -> set[str]:
    return set(re.findall(r"^([A-Z][A-Z0-9_]*)=", EXAMPLE, re.MULTILINE))


class EnvExampleParityTest(unittest.TestCase):
    def test_every_variable_compose_reads_is_documented_in_example(self):
        missing = sorted(compose_variables() - example_keys() - ALLOWED_EXTRA_IN_COMPOSE)
        self.assertEqual(
            [],
            missing,
            "переменная есть в docker-compose.yml, но не в .env.example: " + ", ".join(missing),
        )

    def test_example_has_no_keys_that_nothing_reads(self):
        unused = sorted(example_keys() - compose_variables())
        self.assertEqual(
            [],
            unused,
            "ключ в .env.example никуда не передаётся: " + ", ".join(unused),
        )

    def test_allowlist_has_no_stale_entries(self):
        stale = sorted(ALLOWED_EXTRA_IN_COMPOSE - compose_variables())
        self.assertEqual([], stale, "в ALLOWED_EXTRA_IN_COMPOSE остались несуществующие ключи")

    def test_guard_is_not_vacuous(self):
        # Если парсер сломается, оба множества окажутся пустыми и тесты выше
        # пройдут «зелёными» ни на чём. Это и есть ловушка вакуумного стража.
        self.assertGreaterEqual(len(compose_variables()), 80)
        self.assertGreaterEqual(len(example_keys()), 80)
        self.assertIn("POSTGRES_PASSWORD", compose_variables())
        self.assertIn("POSTGRES_PASSWORD", example_keys())

    def test_required_secrets_are_not_committed_with_real_values(self):
        # `:?` в compose означает «без этого контейнер не стартует». Такие ключи
        # обязаны иметь пустую или явную заглушку в примере, иначе пример
        # выглядит рабочим, а установка падает на старте.
        required = set(re.findall(r"\$\{([A-Z][A-Z0-9_]*):\?", COMPOSE))
        self.assertTrue(required, "в compose не найдено ни одного обязательного ключа")
        placeholders = dict(
            re.findall(r"^([A-Z][A-Z0-9_]*)=(.*)$", EXAMPLE, re.MULTILINE)
        )
        for key in required:
            self.assertIn(key, placeholders, f"обязательный ключ {key} отсутствует в примере")


if __name__ == "__main__":
    unittest.main()
