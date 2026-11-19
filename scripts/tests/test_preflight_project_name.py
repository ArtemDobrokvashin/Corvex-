"""Имя compose-проекта и предупреждение о чужих томах.

На стенде клон в новый каталог дал `password authentication failed for user "corvex"`:
`docker-compose.yml` фиксирует `name: corvex`, поэтому compose переиспользовал тома
старого стенда, а пароль PostgreSQL задаётся один раз при инициализации тома.
Preflight обязан предупредить об этом до `docker compose up`, иначе приёмка P4
молча проверяет старую базу вместо чистой установки.
"""

import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("corvex_preflight", ROOT / "scripts" / "preflight.py")
preflight = importlib.util.module_from_spec(SPEC)
# dataclasses ищут модуль в sys.modules по __module__: без регистрации падает
# на _is_type(None). Регистрируем до exec_module.
sys.modules[SPEC.name] = preflight
assert SPEC and SPEC.loader
SPEC.loader.exec_module(preflight)


class ComposeProjectNameTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.env_backup = os.environ.get("COMPOSE_PROJECT_NAME")
        os.environ.pop("COMPOSE_PROJECT_NAME", None)

    def tearDown(self):
        os.environ.pop("COMPOSE_PROJECT_NAME", None)
        if self.env_backup is not None:
            os.environ["COMPOSE_PROJECT_NAME"] = self.env_backup
        self.tmp.cleanup()

    def compose(self, text):
        (self.root / "docker-compose.yml").write_text(text, encoding="utf-8")

    def test_name_field_from_compose_file(self):
        self.compose("name: corvex\nservices:\n  a:\n")
        self.assertEqual("corvex", preflight.compose_project_name(self.root))

    def test_environment_overrides_file(self):
        self.compose("name: corvex\n")
        os.environ["COMPOSE_PROJECT_NAME"] = "corvex-fresh"
        self.assertEqual("corvex-fresh", preflight.compose_project_name(self.root))

    def test_missing_file_returns_none(self):
        self.assertIsNone(preflight.compose_project_name(self.root))

    def test_warning_text_points_at_distinct_project_name(self):
        """Текст обязан называть выход, а не просто констатировать проблему."""
        source = (ROOT / "scripts" / "preflight.py").read_text(encoding="utf-8")
        self.assertIn("это не чистая установка", source)
        self.assertIn("-p {project}-fresh", source)


if __name__ == "__main__":
    unittest.main()
