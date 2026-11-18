"""Guard: documentation and code must not drift apart.

`docs/API.md` and `docs/api/*.yaml` are what integrators read. When a spec
promises an endpoint that does not exist, the mistake surfaces as a 404 on a
live stand; when an endpoint exists but is documented nowhere, the work is
invisible. Both happened: `PATCH /mail/folders/{id}` and
`DELETE /mail/messages/{id}` were described as working but never implemented,
and the drafts/preview endpoints were implemented but missing from the spec.

`scripts/docs_audit.py` does the comparison; this test pins the result. It also
tests the script's own parsers on small fixtures — otherwise a broken parser
would report "0 findings" and the guard would silently pass.
"""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"

_spec = importlib.util.spec_from_file_location("docs_audit", SCRIPTS / "docs_audit.py")
assert _spec and _spec.loader
docs_audit = importlib.util.module_from_spec(_spec)
sys.modules["docs_audit"] = docs_audit  # dataclasses need the module registered
_spec.loader.exec_module(docs_audit)


class RepositoryAuditTest(unittest.TestCase):
    def test_no_findings(self):
        findings = docs_audit.run(ROOT)
        messages = "\n".join(f"[{f.level}] {f.message}" for f in findings)
        self.assertEqual(
            [],
            findings,
            "Документация разошлась с кодом. Каждое расхождение нужно либо исправить, "
            "либо пометить операцию `x-corvex-status: planned` (спецификация) / "
            "добавить путь в docs/API.md:\n" + messages,
        )


class OpenApiParserTest(unittest.TestCase):
    def test_server_prefix_ignores_license_url(self):
        """`license.url` в шапке файла — не `servers`; раньше он давал префикс /licenses."""
        text = (
            "openapi: 3.0.3\n"
            "info:\n"
            "  license:\n"
            "    url: https://www.apache.org/licenses/LICENSE-2.0\n"
            "servers:\n"
            "  - url: https://mail.company.local/api/v1\n"
            "paths:\n"
            "  /chat/rooms:\n"
            "    get:\n"
            "      responses: { '200': { description: ok } }\n"
        )
        operations = docs_audit.openapi_operations(text, "fixture.yaml")
        self.assertEqual(
            [("GET", "/api/v1/chat/rooms", "")],
            [(o.method, o.path, o.status) for o in operations],
        )

    def test_path_level_servers_override_root(self):
        text = (
            "openapi: 3.0.3\n"
            "servers:\n"
            "  - url: https://mail.company.local/api/v1\n"
            "paths:\n"
            "  /oauth2/jwks:\n"
            "    servers:\n"
            "      - url: http://auth-service:8081\n"
            "    x-corvex-status: internal\n"
            "    get:\n"
            "      responses: { '200': { description: ok } }\n"
        )
        operations = docs_audit.openapi_operations(text, "fixture.yaml")
        self.assertEqual(
            [("GET", "/oauth2/jwks", "internal")],
            [(o.method, o.path, o.status) for o in operations],
        )

    def test_planned_marker_on_path_and_on_single_operation(self):
        text = (
            "openapi: 3.0.3\n"
            "servers:\n"
            "  - url: https://example.test/api/v1\n"
            "paths:\n"
            "  /a:\n"
            "    x-corvex-status: planned\n"
            "    post:\n"
            "      responses: { '201': { description: ok } }\n"
            "  /b:\n"
            "    get:\n"
            "      responses: { '200': { description: ok } }\n"
            "    delete:\n"
            "      x-corvex-status: planned\n"
            "      responses: { '204': { description: ok } }\n"
        )
        statuses = {
            (o.method, o.path): o.status
            for o in docs_audit.openapi_operations(text, "fixture.yaml")
        }
        self.assertEqual("planned", statuses[("POST", "/api/v1/a")])
        self.assertEqual("", statuses[("GET", "/api/v1/b")])
        self.assertEqual("planned", statuses[("DELETE", "/api/v1/b")])


class SpringParserTest(unittest.TestCase):
    def test_reads_mappings_and_normalizes_variables(self):
        source = (
            "import org.springframework.web.bind.annotation.*;\n"
            "@RestController\n"
            '@RequestMapping("/api/v1/demo")\n'
            "class DemoController {\n"
            '    @GetMapping("/items/{itemId}")\n'
            "    String get() { return \"\"; }\n"
            "    @DeleteMapping\n"
            "    void clear() { }\n"
            '    @PatchMapping(value = "/items/{id}", consumes = "application/json")\n'
            "    void patch() { }\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "services" / "demo" / "src" / "main"
            target = target / "java" / "ru" / "example"
            target.mkdir(parents=True)
            (target / "DemoController.java").write_text(source, encoding="utf-8")
            routes = docs_audit.spring_routes(root)

        self.assertEqual(
            {
                ("GET", "/api/v1/demo/items/{}"),
                ("DELETE", "/api/v1/demo"),
                ("PATCH", "/api/v1/demo/items/{}"),
            },
            set(routes),
        )

    def test_ignores_non_controllers(self):
        source = "class Helper {\n  @GetMapping(\"/nope\")\n  void x() {}\n}\n"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "services" / "demo" / "src" / "main" / "java"
            target.mkdir(parents=True)
            (target / "Helper.java").write_text(source, encoding="utf-8")
            self.assertEqual({}, docs_audit.spring_routes(root))


class MarkdownParserTest(unittest.TestCase):
    def test_extracts_table_paths_with_body_and_shared_methods(self):
        text = (
            "| `GET /drive/usage` | квота |\n"
            "| `PATCH /drive/{id}/name {name}` | переименовать |\n"
            "| `GET/POST /drive/{id}/shares` | ссылки |\n"
            "| `/mail/**` | префикс gateway, не эндпоинт |\n"
        )
        found = docs_audit.documented_in_markdown(text)
        self.assertIn(("GET", "/api/v1/drive/usage"), found)
        self.assertIn(("PATCH", "/api/v1/drive/{}/name"), found)
        self.assertIn(("GET", "/api/v1/drive/{}/shares"), found)
        self.assertIn(("POST", "/api/v1/drive/{}/shares"), found)
        self.assertFalse(any(path.endswith("/**") for _, path in found))


class EnvironmentCheckTest(unittest.TestCase):
    def test_dead_variable_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".env.example").write_text(
                "CORVEX_USED=1\nCORVEX_DEAD=2\n", encoding="utf-8"
            )
            (root / "docker-compose.yml").write_text(
                "services:\n  app:\n    environment:\n      X: ${CORVEX_USED}\n",
                encoding="utf-8",
            )
            findings = docs_audit.check_env_example(root)
        self.assertEqual(1, len(findings))
        self.assertIn("CORVEX_DEAD", findings[0].message)

    def test_missing_example_file_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            findings = docs_audit.check_env_example(Path(tmp))
        self.assertEqual("env", findings[0].check)
        self.assertEqual("error", findings[0].level)


class ComposeRequirementTest(unittest.TestCase):
    def test_variable_without_default_must_be_in_example(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".env.example").write_text("CORVEX_KNOWN=1\n", encoding="utf-8")
            (root / "docker-compose.yml").write_text(
                "services:\n"
                "  app:\n"
                "    environment:\n"
                "      A: ${CORVEX_KNOWN}\n"
                "      B: ${CORVEX_OPTIONAL:-false}\n"
                "      C: ${CORVEX_FORGOTTEN}\n",
                encoding="utf-8",
            )
            findings = docs_audit.check_compose_requires_documented(root)
        self.assertEqual(1, len(findings))
        self.assertIn("CORVEX_FORGOTTEN", findings[0].message)


class MigrationCheckTest(unittest.TestCase):
    def test_claim_beyond_latest_migration_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            migrations = root / "services" / "s" / "src" / "main" / "resources"
            migrations = migrations / "db" / "migration"
            migrations.mkdir(parents=True)
            (migrations / "V1__init.sql").write_text("select 1;", encoding="utf-8")
            docs = root / "docs"
            docs.mkdir()
            (docs / "STATUS.md").write_text("миграции `V1…V9`", encoding="utf-8")
            findings = docs_audit.check_migrations(root)
        self.assertEqual(1, len(findings))
        self.assertEqual("error", findings[0].level)


if __name__ == "__main__":
    unittest.main()
